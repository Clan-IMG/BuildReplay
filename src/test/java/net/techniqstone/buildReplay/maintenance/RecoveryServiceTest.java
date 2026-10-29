package net.techniqstone.buildReplay.maintenance;

import static net.techniqstone.buildReplay.maintenance.Rows.ALEX;
import static net.techniqstone.buildReplay.maintenance.Rows.CURRENT_INSTANCE;
import static net.techniqstone.buildReplay.maintenance.Rows.OLD_INSTANCE;
import static net.techniqstone.buildReplay.maintenance.Rows.SERVER;
import static net.techniqstone.buildReplay.maintenance.Rows.STEVE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import net.techniqstone.buildReplay.server.ServerIdentity;
import net.techniqstone.buildReplay.storage.model.RecordingRow;
import net.techniqstone.buildReplay.storage.model.RecordingStatus;
import net.techniqstone.buildReplay.storage.model.SegmentStatus;
import net.techniqstone.buildReplay.storage.model.VisitEndReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RecoveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-29T16:00:00Z");

    private final FakeReplayStore store = new FakeReplayStore();
    private final FakeReplayEngine engine = new FakeReplayEngine();
    private final FakeRecordingRecovery recovery = new FakeRecordingRecovery();
    private final SyncTaskRunner tasks = new SyncTaskRunner();
    private final MutableClock clock = new MutableClock(NOW);
    private final ServerIdentity identity = new ServerIdentity(SERVER, CURRENT_INSTANCE, "dev", "-1", true);
    private RecoveryService service;

    @BeforeEach
    void setUp() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        service = new RecoveryService(store, engine, recovery, identity, tasks, clock, logger);
    }

    /** Zustand, den ein Absturz während einer laufenden Aufnahme hinterlässt. */
    private void crashDuringRecording() {
        store.visits.add(Rows.openVisit("old:steve", STEVE, OLD_INSTANCE, NOW.minus(Duration.ofMinutes(30))));
        store.visits.add(Rows.openVisit("current:alex", ALEX, CURRENT_INSTANCE, NOW.minusSeconds(5)));
        store.recordings.add(Rows.recording("brraw-a", STEVE, OLD_INSTANCE, RecordingStatus.RECORDING,
                NOW.minus(Duration.ofMinutes(12)), 40));
        store.recordings.add(Rows.recording("brraw-b", STEVE, OLD_INSTANCE, RecordingStatus.FINALIZING,
                NOW.minus(Duration.ofMinutes(35)), 7));
        store.recordings.add(Rows.recording("brraw-c", ALEX, CURRENT_INSTANCE, RecordingStatus.RECORDING,
                NOW.minusSeconds(4), 0));
        store.recordings.add(Rows.recording("brraw-d", STEVE, OLD_INSTANCE, RecordingStatus.RAW_PENDING,
                NOW.minus(Duration.ofHours(2)), 15));
        store.recordings.add(Rows.recording("brraw-e", STEVE, OLD_INSTANCE, RecordingStatus.FINALIZED,
                NOW.minus(Duration.ofHours(3)), 15));
    }

    @Test
    void aServerRestartDuringARecordingClosesVisitsInterruptsRecordingsAndRecoversPendingRaw() throws Exception {
        crashDuringRecording();

        RecoveryResult result = service.start().get(5, TimeUnit.SECONDS);

        assertEquals(1, result.visitsClosed());
        assertEquals(List.of(new FakeReplayStore.EndedVisit("old:steve", NOW, VisitEndReason.RECOVERED)),
                store.endedVisits, "nur der Aufenthalt der alten Instanz wird geschlossen");

        assertEquals(2, result.recordingsInterrupted());
        Map<String, FakeReplayStore.FinishedRecording> finished = store.finishedRecordings.stream()
                .collect(Collectors.toMap(FakeReplayStore.FinishedRecording::rawName, f -> f));
        assertEquals(Map.of(
                "brraw-a", new FakeReplayStore.FinishedRecording("brraw-a", RecordingStatus.INTERRUPTED, NOW, 40,
                        "Instanz während der Aufnahme beendet"),
                "brraw-b", new FakeReplayStore.FinishedRecording("brraw-b", RecordingStatus.INTERRUPTED, NOW, 7,
                        "Instanz während der Aufnahme beendet")), finished);

        assertEquals(1, recovery.calls.size());
        assertEquals(List.of("brraw-d"), recovery.calls.getFirst().stream().map(RecordingRow::rawName).toList());
        assertEquals(1, result.pendingFound());
        assertEquals(1, result.pendingRecovered());
        assertFalse(result.skipped());
        assertTrue(engine.threadViolations.isEmpty(), engine.threadViolations.toString());
        assertTrue(store.readThreadViolations.isEmpty(), store.readThreadViolations.toString());
    }

    @Test
    void segmentsStuckInSavingForMoreThanTenMinutesBecomeAvailableOrFailed() throws Exception {
        store.segments.add(Rows.segment("br-saved", STEVE, SERVER, NOW.minus(Duration.ofMinutes(40)),
                Duration.ofMinutes(5), SegmentStatus.SAVING, NOW.minus(Duration.ofMinutes(30))));
        store.segments.add(Rows.segment("br-lost", STEVE, SERVER, NOW.minus(Duration.ofMinutes(40)),
                Duration.ofMinutes(5), SegmentStatus.SAVING, NOW.minus(Duration.ofMinutes(30))));
        store.segments.add(Rows.segment("br-fresh", STEVE, SERVER, NOW.minus(Duration.ofMinutes(20)),
                Duration.ofMinutes(5), SegmentStatus.SAVING, NOW.minus(Duration.ofMinutes(2))));
        store.segments.add(Rows.segment("br-fine", STEVE, SERVER, NOW.minus(Duration.ofHours(2)),
                Duration.ofMinutes(5), SegmentStatus.AVAILABLE, NOW.minus(Duration.ofHours(2))));
        store.segments.add(Rows.segment("br-other-server", STEVE, "build", NOW.minus(Duration.ofHours(2)),
                Duration.ofMinutes(5), SegmentStatus.SAVING, NOW.minus(Duration.ofHours(2))));
        engine.stored.add("br-saved");

        RecoveryResult result = service.start().get(5, TimeUnit.SECONDS);

        assertEquals(1, result.savingAvailable());
        assertEquals(1, result.savingFailed());
        assertEquals(List.of(
                        new FakeReplayStore.StatusUpdate("br-saved", SegmentStatus.AVAILABLE,
                                RecoveryService.NOTE_AVAILABLE),
                        new FakeReplayStore.StatusUpdate("br-lost", SegmentStatus.FAILED, RecoveryService.NOTE_FAILED)),
                store.statusUpdates);
        assertTrue(engine.threadViolations.isEmpty(), engine.threadViolations.toString());
    }

    @Test
    void anUnreachableDatabaseIsRetriedLaterUntilTheRecoverySucceeds() throws Exception {
        crashDuringRecording();
        store.unavailable = true;

        var future = service.start();

        assertFalse(future.isDone(), "Ergebnis erst nach erfolgreichem Versuch");
        assertEquals(1, tasks.scheduler.active().size());
        assertEquals(RecoveryService.RETRY_DELAY.toMillis(), tasks.scheduler.active().getFirst().delayMillis());

        store.unavailable = false;
        tasks.scheduler.runAll();

        RecoveryResult result = future.get(5, TimeUnit.SECONDS);
        assertEquals(1, result.visitsClosed());
        assertEquals(2, result.recordingsInterrupted());
    }

    @Test
    void aDisabledDatabaseSkipsTheRecoveryWithoutTouchingAnything() throws Exception {
        crashDuringRecording();
        store.enabled = false;

        RecoveryResult result = service.start().get(5, TimeUnit.SECONDS);

        assertTrue(result.skipped());
        assertTrue(store.endedVisits.isEmpty());
        assertTrue(store.finishedRecordings.isEmpty());
        assertTrue(recovery.calls.isEmpty());
    }

    @Test
    void withoutAdvancedReplayPendingRawRecordingsWaitButVisitsAndRecordingsAreStillRepaired() throws Exception {
        crashDuringRecording();
        store.segments.add(Rows.segment("br-saved", STEVE, SERVER, NOW.minus(Duration.ofMinutes(40)),
                Duration.ofMinutes(5), SegmentStatus.SAVING, NOW.minus(Duration.ofMinutes(30))));
        engine.available = false;

        RecoveryResult result = service.start().get(5, TimeUnit.SECONDS);

        assertEquals(1, result.visitsClosed());
        assertEquals(2, result.recordingsInterrupted());
        assertEquals(1, result.pendingFound());
        assertEquals(0, result.pendingRecovered());
        assertTrue(recovery.calls.isEmpty());
        assertTrue(store.statusUpdates.isEmpty(), "ohne AdvancedReplay keine Existenzprüfung");
    }

    @Test
    void aFailingRawRecoveryDoesNotStopTheRemainingSteps() throws Exception {
        crashDuringRecording();
        recovery.failure = new IllegalStateException("kaputt");
        store.segments.add(Rows.segment("br-lost", STEVE, SERVER, NOW.minus(Duration.ofMinutes(40)),
                Duration.ofMinutes(5), SegmentStatus.SAVING, NOW.minus(Duration.ofMinutes(30))));

        RecoveryResult result = service.start().get(5, TimeUnit.SECONDS);

        assertEquals(0, result.pendingRecovered());
        assertEquals(1, result.savingFailed());
    }

    @Test
    void startingTwiceRunsTheRecoveryOnlyOnce() throws Exception {
        crashDuringRecording();

        var first = service.start();
        var second = service.start();

        assertSame(first, second);
        first.get(5, TimeUnit.SECONDS);
        assertEquals(1, recovery.calls.size());
        assertEquals(1, store.endedVisits.size());
    }
}
