package net.techniqstone.buildReplay.maintenance;

import static net.techniqstone.buildReplay.maintenance.Rows.ALEX;
import static net.techniqstone.buildReplay.maintenance.Rows.CURRENT_INSTANCE;
import static net.techniqstone.buildReplay.maintenance.Rows.OLD_INSTANCE;
import static net.techniqstone.buildReplay.maintenance.Rows.SERVER;
import static net.techniqstone.buildReplay.maintenance.Rows.STEVE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import net.techniqstone.buildReplay.config.EnvResolver;
import net.techniqstone.buildReplay.config.PluginConfig;
import net.techniqstone.buildReplay.recording.ReplayNames;
import net.techniqstone.buildReplay.server.ServerIdentity;
import net.techniqstone.buildReplay.state.SharedEvent;
import net.techniqstone.buildReplay.storage.StorageUnavailableException;
import net.techniqstone.buildReplay.storage.model.RecordingStatus;
import net.techniqstone.buildReplay.storage.model.SegmentStatus;
import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;

class RetentionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-29T16:00:00Z");

    private final FakeReplayStore store = new FakeReplayStore();
    private final FakeReplayEngine engine = new FakeReplayEngine();
    private final FakeSharedState shared = new FakeSharedState();
    private final SyncTaskRunner tasks = new SyncTaskRunner();
    private final MutableClock clock = new MutableClock(NOW);
    private final ServerIdentity identity = new ServerIdentity(SERVER, CURRENT_INSTANCE, "dev", "-1", true);
    private final List<UUID> online = new ArrayList<>();

    private RetentionService service(PluginConfig config) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        return new RetentionService(config, store, shared, engine, identity, tasks, clock, logger, () -> online);
    }

    private static PluginConfig config(int retentionDays, int maxPerPlayer) {
        MemoryConfiguration yaml = new MemoryConfiguration();
        yaml.set("storage.retention-days", retentionDays);
        yaml.set("storage.max-segments-per-player", maxPerPlayer);
        return PluginConfig.from(yaml, new EnvResolver(name -> null), new ArrayList<>());
    }

    private void storedSegment(String name, UUID player, Instant startedAt) {
        store.segments.add(Rows.segment(name, player, startedAt, SegmentStatus.AVAILABLE));
        engine.stored.add(name);
    }

    private void storedSegment(String name, UUID player, String server, Instant startedAt) {
        store.segments.add(Rows.segment(name, player, server, startedAt, Duration.ofMinutes(2),
                SegmentStatus.AVAILABLE, startedAt.plus(Duration.ofMinutes(2))));
        engine.stored.add(name);
    }

    @Test
    void segmentsOlderThanTheRetentionPeriodAreDeletedMarkedAndAnnounced() throws Exception {
        storedSegment("br-old", STEVE, NOW.minus(Duration.ofDays(40)));
        storedSegment("br-young", STEVE, NOW.minus(Duration.ofDays(2)));
        storedSegment("br-old-elsewhere", STEVE, "build", NOW.minus(Duration.ofDays(40)));

        RetentionResult result = service(config(30, 0)).runNow(false).get(5, TimeUnit.SECONDS);

        assertEquals(new RetentionResult(1, 1, 0, false), result);
        assertEquals(List.of("br-old"), engine.deleted);
        assertEquals(List.of(new FakeReplayStore.StatusUpdate("br-old", SegmentStatus.DELETED,
                "Aufbewahrungsfrist (30 Tage) abgelaufen")), store.statusUpdates);
        assertEquals(1, shared.published.size());
        SharedEvent event = shared.published.getFirst();
        assertEquals(SharedEvent.SEGMENT_DELETED, event.type());
        assertEquals(CURRENT_INSTANCE, event.originInstance());
        assertEquals("br-old", event.data().get("replayName"));
        assertEquals(STEVE.toString(), event.data().get("player"));
        assertTrue(engine.threadViolations.isEmpty(), engine.threadViolations.toString());
        assertTrue(store.readThreadViolations.isEmpty(), store.readThreadViolations.toString());
        assertTrue(shared.threadViolations.isEmpty(), shared.threadViolations.toString());
        assertTrue(shared.held.isEmpty(), "Sperre wieder freigegeben");
    }

    @Test
    void aDryRunOnlyCountsAndChangesNothing() throws Exception {
        storedSegment("br-old-1", STEVE, NOW.minus(Duration.ofDays(40)));
        storedSegment("br-old-2", ALEX, NOW.minus(Duration.ofDays(35)));
        String orphan = Rows.rawName(NOW.minus(Duration.ofDays(3)), "orphan0001");
        engine.stored.add(orphan);

        RetentionResult result = service(config(30, 0)).runNow(true).get(5, TimeUnit.SECONDS);

        assertEquals(new RetentionResult(3, 3, 0, true), result);
        assertTrue(engine.deleted.isEmpty());
        assertTrue(store.statusUpdates.isEmpty());
        assertTrue(shared.published.isEmpty());
        assertTrue(engine.stored.containsAll(Set.of("br-old-1", "br-old-2", orphan)));
    }

    @Test
    void aRetentionOfZeroDaysKeepsOldSegmentsForever() throws Exception {
        storedSegment("br-ancient", STEVE, NOW.minus(Duration.ofDays(900)));

        RetentionResult result = service(config(0, 0)).runNow(false).get(5, TimeUnit.SECONDS);

        assertEquals(0, result.deleted());
        assertTrue(engine.deleted.isEmpty());
    }

    @Test
    void thePerPlayerLimitDeletesTheOldestSegmentsOfPresentPlayersOnThisServer() throws Exception {
        for (int i = 0; i < 5; i++) {
            storedSegment("br-steve-" + i, STEVE, NOW.minus(Duration.ofDays(5 - i)));
            storedSegment("br-alex-" + i, ALEX, NOW.minus(Duration.ofDays(5 - i)));
        }
        online.add(STEVE);

        RetentionResult result = service(config(30, 3)).runNow(false).get(5, TimeUnit.SECONDS);

        assertEquals(2, result.deleted());
        assertEquals(List.of("br-steve-0", "br-steve-1"), engine.deleted, "die ältesten zuerst");
        assertTrue(store.statusUpdates.stream().allMatch(u -> u.note().contains("Limit von 3")));
        assertTrue(store.flushes > 0, "andere Server sollen die neue Anzahl sehen");
        assertTrue(shared.acquired.contains("retention:player:" + STEVE));
    }

    @Test
    void segmentsRemovedByTheRetentionPeriodCountTowardsThePlayerLimit() throws Exception {
        storedSegment("br-expired", STEVE, NOW.minus(Duration.ofDays(40)));
        for (int i = 0; i < 4; i++) {
            storedSegment("br-recent-" + i, STEVE, NOW.minus(Duration.ofDays(5 - i)));
        }
        online.add(STEVE);

        RetentionResult result = service(config(30, 3)).runNow(false).get(5, TimeUnit.SECONDS);

        assertEquals(List.of("br-expired", "br-recent-0"), engine.deleted);
        assertEquals(2, result.deleted());
    }

    @Test
    void aDryRunOfThePlayerLimitDoesNotCountExpiredSegmentsTwice() throws Exception {
        storedSegment("br-expired", STEVE, NOW.minus(Duration.ofDays(40)));
        for (int i = 0; i < 4; i++) {
            storedSegment("br-recent-" + i, STEVE, NOW.minus(Duration.ofDays(5 - i)));
        }
        online.add(STEVE);

        RetentionResult result = service(config(30, 3)).runNow(true).get(5, TimeUnit.SECONDS);

        assertEquals(new RetentionResult(2, 2, 0, true), result);
    }

    @Test
    void orphanedRawRecordingsOlderThanOneDayAreDeletedButLiveAndPendingOnesAreKept() throws Exception {
        String orphan = Rows.rawName(NOW.minus(Duration.ofDays(2)), "orphan0001");
        String finalized = Rows.rawName(NOW.minus(Duration.ofDays(3)), "final00001");
        String pending = Rows.rawName(NOW.minus(Duration.ofDays(4)), "pending001");
        String recording = Rows.rawName(NOW.minus(Duration.ofDays(2)), "record0001");
        String young = Rows.rawName(NOW.minus(Duration.ofHours(3)), "young00001");
        String active = Rows.rawName(NOW.minus(Duration.ofDays(2)), "active0001");
        String foreign = "brraw-not!a-timestamp";
        engine.stored.addAll(List.of(orphan, finalized, pending, recording, young, active, foreign, "br-segment"));
        engine.activeRaw.add(active);
        store.recordings.add(Rows.recording(finalized, STEVE, OLD_INSTANCE, RecordingStatus.FINALIZED,
                NOW.minus(Duration.ofDays(3)), 4));
        store.recordings.add(Rows.recording(pending, STEVE, OLD_INSTANCE, RecordingStatus.RAW_PENDING,
                NOW.minus(Duration.ofDays(4)), 4));
        store.recordings.add(Rows.recording(recording, STEVE, OLD_INSTANCE, RecordingStatus.RECORDING,
                NOW.minus(Duration.ofDays(2)), 4));

        RetentionResult result = service(config(0, 0)).runNow(false).get(5, TimeUnit.SECONDS);

        assertEquals(Set.of(orphan, finalized), Set.copyOf(engine.deleted));
        assertEquals(2, result.deleted());
        assertTrue(shared.published.isEmpty(), "Rohaufnahmen sind keine Abschnitte");
        assertTrue(engine.stored.containsAll(Set.of(pending, recording, young, active, foreign, "br-segment")));
    }

    @Test
    void orphanedRawRecordingsAreLeftAloneOnSharedStorageBackends() throws Exception {
        engine.backend = "MYSQL";
        String orphan = Rows.rawName(NOW.minus(Duration.ofDays(2)), "orphan0001");
        engine.stored.add(orphan);

        RetentionResult result = service(config(0, 0)).runNow(false).get(5, TimeUnit.SECONDS);

        assertEquals(0, result.deleted());
        assertTrue(engine.stored.contains(orphan));
    }

    @Test
    void nothingIsDeletedWhileTheDatabaseIsUnreachable() {
        storedSegment("br-old", STEVE, NOW.minus(Duration.ofDays(40)));
        engine.stored.add(Rows.rawName(NOW.minus(Duration.ofDays(2)), "orphan0001"));
        store.unavailable = true;

        ExecutionException error = assertThrows(ExecutionException.class,
                () -> service(config(30, 1000)).runNow(false).get(5, TimeUnit.SECONDS));

        assertInstanceOf(StorageUnavailableException.class, error.getCause());
        assertTrue(engine.deleted.isEmpty());
        assertTrue(shared.held.isEmpty(), "Sperre auch im Fehlerfall freigegeben");
    }

    @Test
    void aFailedDeleteIsCountedAndTheSegmentStaysAvailable() throws Exception {
        storedSegment("br-locked", STEVE, NOW.minus(Duration.ofDays(40)));
        engine.failingDeletes.add("br-locked");

        RetentionResult result = service(config(30, 0)).runNow(false).get(5, TimeUnit.SECONDS);

        assertEquals(new RetentionResult(1, 0, 1, false), result);
        assertTrue(store.statusUpdates.isEmpty());
        assertTrue(shared.published.isEmpty());
    }

    @Test
    void aRunHeldByAnotherInstanceIsRejectedManuallyAndSkippedWhenScheduled() throws Exception {
        storedSegment("br-old", STEVE, NOW.minus(Duration.ofDays(40)));
        shared.heldElsewhere.add("retention:" + SERVER);
        RetentionService service = service(config(30, 0));

        ExecutionException error = assertThrows(ExecutionException.class,
                () -> service.runNow(false).get(5, TimeUnit.SECONDS));
        assertInstanceOf(MaintenanceException.class, error.getCause());

        RetentionResult scheduled = service.run(false, false).get(5, TimeUnit.SECONDS);
        assertEquals(new RetentionResult(0, 0, 0, false), scheduled);
        assertTrue(engine.deleted.isEmpty());
    }

    @Test
    void aDisabledDatabaseFailsAManualRunWithAClearReason() {
        store.enabled = false;

        ExecutionException error = assertThrows(ExecutionException.class,
                () -> service(config(30, 0)).runNow(false).get(5, TimeUnit.SECONDS));

        assertInstanceOf(StorageUnavailableException.class, error.getCause());
    }

    @Test
    void withoutAdvancedReplayAManualRunExplainsWhyNothingHappens() {
        engine.available = false;

        ExecutionException error = assertThrows(ExecutionException.class,
                () -> service(config(30, 0)).runNow(false).get(5, TimeUnit.SECONDS));

        assertInstanceOf(MaintenanceException.class, error.getCause());
    }

    @Test
    void theCleanupIsScheduledAndRescheduledWhenTheIntervalChanges() throws Exception {
        RetentionService service = service(config(30, 0));
        service.start();

        assertEquals(1, tasks.scheduler.active().size());
        var first = tasks.scheduler.active().getFirst();
        assertEquals(RetentionService.INITIAL_DELAY.toMillis(), first.delayMillis());
        assertEquals(Duration.ofMinutes(60).toMillis(), first.periodMillis());

        MemoryConfiguration yaml = new MemoryConfiguration();
        yaml.set("storage.cleanup-interval-minutes", 15);
        service.updateConfig(PluginConfig.from(yaml, new EnvResolver(name -> null), new ArrayList<>()));

        assertTrue(first.future().isCancelled());
        assertEquals(Duration.ofMinutes(15).toMillis(), tasks.scheduler.active().getFirst().periodMillis());

        storedSegment("br-old", STEVE, NOW.minus(Duration.ofDays(40)));
        tasks.scheduler.runAll();
        assertEquals(List.of("br-old"), engine.deleted);

        service.close();
        assertTrue(tasks.scheduler.active().isEmpty());
    }

    @Test
    void rawNameTimestampsAreReadFromTheBase36Prefix() {
        Instant created = Instant.parse("2026-10-27T08:15:30.123Z");

        assertEquals(Optional.of(created), RetentionService.rawNameTimestamp(Rows.rawName(created, "abcdefghij")));
        assertTrue(RetentionService.rawNameTimestamp(ReplayNames.newRawName()).isPresent());
        assertEquals(Optional.empty(), RetentionService.rawNameTimestamp("brraw-!!!-x"));
        assertEquals(Optional.empty(), RetentionService.rawNameTimestamp("br-" + Long.toString(created.toEpochMilli(), 36)));
        assertEquals(Optional.empty(), RetentionService.rawNameTimestamp("brraw-1-x"), "unplausibel alt");
    }
}
