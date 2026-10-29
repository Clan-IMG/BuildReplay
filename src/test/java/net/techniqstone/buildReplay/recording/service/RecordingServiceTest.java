package net.techniqstone.buildReplay.recording.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.LogRecord;
import java.util.stream.Collectors;
import net.techniqstone.buildReplay.activity.ActivityType;
import net.techniqstone.buildReplay.command.Permissions;
import net.techniqstone.buildReplay.config.PluginConfig;
import net.techniqstone.buildReplay.recording.ActiveRecordingInfo;
import net.techniqstone.buildReplay.recording.RecordingStats;
import net.techniqstone.buildReplay.recording.ReplayEngineException;
import net.techniqstone.buildReplay.recording.ReplayNames;
import net.techniqstone.buildReplay.recording.SavedSegment;
import net.techniqstone.buildReplay.recording.SegmentCut;
import net.techniqstone.buildReplay.recording.service.fakes.FakePlanner;
import net.techniqstone.buildReplay.recording.service.fakes.FakePlayer;
import net.techniqstone.buildReplay.recording.service.fakes.FakeReplayEngine;
import net.techniqstone.buildReplay.recording.service.fakes.FakeSessions;
import net.techniqstone.buildReplay.recording.service.fakes.FakeSharedState;
import net.techniqstone.buildReplay.recording.service.fakes.FakeStore;
import net.techniqstone.buildReplay.recording.service.fakes.FakeTaskRunner;
import net.techniqstone.buildReplay.recording.service.fakes.TestSupport;
import net.techniqstone.buildReplay.session.NetworkSession;
import net.techniqstone.buildReplay.state.SharedEvent;
import net.techniqstone.buildReplay.storage.model.RecordingRow;
import net.techniqstone.buildReplay.storage.model.RecordingStatus;
import net.techniqstone.buildReplay.storage.model.RecordingUpsert;
import net.techniqstone.buildReplay.storage.model.SegmentStatus;
import net.techniqstone.buildReplay.storage.model.SegmentUpsert;
import org.bukkit.GameMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordingServiceTest {

    private static final Instant START = Instant.parse("2026-10-29T12:00:00Z");
    private static final String SESSION_ID = "59dc83e5-1111-2222-3333-444455556666";

    @TempDir
    Path dataFolder;

    private final List<LogRecord> logs = new ArrayList<>();
    private TestSupport.MutableClock clock;
    private FakeTaskRunner tasks;
    private FakeReplayEngine engine;
    private FakeSessions sessions;
    private FakeStore store;
    private FakeSharedState shared;
    private FakePlanner planner;
    private RecordingService service;
    private FakePlayer steve;

    @BeforeEach
    void setUp() {
        clock = new TestSupport.MutableClock(START);
        tasks = new FakeTaskRunner();
        engine = new FakeReplayEngine();
        sessions = new FakeSessions();
        store = new FakeStore();
        shared = new FakeSharedState();
        planner = new FakePlanner();
        steve = new FakePlayer("Steve");
        sessions.cloudSession(steve.uuid, SESSION_ID);
        service = create(TestSupport.config());
    }

    private RecordingService create(PluginConfig config) {
        RecordingService created = new RecordingService(config, TestSupport.DEV, tasks, clock, engine, sessions, store,
                shared, planner, dataFolder, TestSupport.quietLogger(logs));
        engine.install(created);
        return created;
    }

    /** {@code seconds} Sekunden vergehen; bei {@code building} setzt jeder Spieler pro Sekunde einen Block. */
    private void pass(int seconds, boolean building, FakePlayer... builders) {
        for (int i = 0; i < seconds; i++) {
            clock.advance(Duration.ofSeconds(1));
            engine.advance(20);
            if (building) {
                for (FakePlayer builder : builders) {
                    service.onRelevant(builder.uuid, ActivityType.BLOCK_PLACE, clock.instant());
                }
            }
            service.tick(clock.instant());
        }
    }

    private String currentRaw() {
        return engine.only().rawName;
    }

    // ------------------------------------------------------------------ Start

    @Test
    void startsARawRecordingWhenAnEligiblePlayerJoins() {
        service.onJoin(steve.player());

        assertEquals(1, engine.started.size());
        String raw = engine.started.get(0);
        assertTrue(ReplayNames.isRaw(raw));
        assertTrue(ReplayNames.isValid(raw));
        RecordingUpsert upsert = store.recordingUpserts.get(0);
        assertEquals(RecordingStatus.RECORDING, upsert.status());
        assertEquals(SESSION_ID, upsert.sessionKey());
        assertEquals("dev", upsert.serverName());
        assertEquals("instance-dev-1", upsert.instanceId());
        assertEquals("world", upsert.worldName());
        assertEquals(raw, shared.recordingStates.get(steve.uuid).rawName());

        ActiveRecordingInfo info = service.recordingOf(steve.uuid).orElseThrow();
        assertEquals(raw, info.rawName());
        assertEquals(NetworkSession.shortRef(SESSION_ID), info.sessionRef());
        assertEquals(1, service.stats().active());
        assertEquals(1, service.stats().rawRecordingsStarted());
    }

    @Test
    void doesNotRecordExemptPlayersExcludedWorldsSpectatorsOrWithoutAnEngine() {
        FakePlayer exempt = new FakePlayer("Exempt").grant(Permissions.EXEMPT);
        FakePlayer lobby = new FakePlayer("Lobby");
        lobby.world = "spawn";
        FakePlayer spectator = new FakePlayer("Spectator");
        spectator.gameMode = GameMode.SPECTATOR;
        service = create(TestSupport.config("recording.excluded-worlds", List.of("spawn")));

        service.onJoin(exempt.player());
        service.onJoin(lobby.player());
        service.onJoin(spectator.player());
        assertTrue(engine.started.isEmpty());

        engine.available = false;
        service.onJoin(steve.player());
        assertTrue(engine.started.isEmpty());

        engine.available = true;
        service.tick(clock.instant());
        assertEquals(1, engine.started.size(), "Startet, sobald AdvancedReplay verfügbar ist");
    }

    @Test
    void recordingCanBeDisabledInTheConfiguration() {
        service = create(TestSupport.config("recording.enabled", false));

        service.onJoin(steve.player());
        pass(5, true, steve);

        assertTrue(engine.started.isEmpty());
    }

    @Test
    void attachesTheSessionKeyOnceTheResolutionCompletes() {
        sessions.currentKnown = false;
        CompletableFuture<NetworkSession> resolution = new CompletableFuture<>();
        sessions.pending.put(steve.uuid, resolution);

        service.onJoin(steve.player());
        assertNull(store.recordingUpserts.get(0).sessionKey());

        resolution.complete(sessions.sessions.get(steve.uuid));

        assertEquals(2, store.recordingUpserts.size());
        RecordingUpsert attached = store.recordingUpserts.get(1);
        assertEquals(SESSION_ID, attached.sessionKey());
        assertEquals(RecordingStatus.RECORDING, attached.status());
        assertEquals(store.recordingUpserts.get(0).rawName(), attached.rawName());
    }

    // ------------------------------------------------------------------ AFK (Testfälle 4/5)

    @Test
    void anAfkPlayerStopsBeingRecordedAndANewRecordingStartsWhenHeIsActiveAgain() {
        service.onJoin(steve.player());
        String first = currentRaw();
        pass(5, true, steve);

        service.onAfk(steve.uuid, clock.instant());

        assertTrue(engine.running.isEmpty());
        assertEquals(List.of(first), engine.stoppedKept);
        assertEquals(RecordingStatus.FINALIZED, store.statusOf(first));
        assertEquals(1, store.segmentUpserts.size());
        assertTrue(service.recordingOf(steve.uuid).isEmpty());

        pass(30, false);
        assertTrue(engine.running.isEmpty(), "Während AFK wird nicht neu gestartet");

        service.onActive(steve.uuid, clock.instant());

        String second = currentRaw();
        assertNotEquals(first, second);
        assertEquals(RecordingStatus.RECORDING, store.statusOf(second));
        assertEquals(2, service.stats().rawRecordingsStarted());
    }

    @Test
    void aRelevantActionWhileNotRecordingStartsANewRecording() {
        service.onJoin(steve.player());
        pass(3, true, steve);
        service.onAfk(steve.uuid, clock.instant());
        assertTrue(engine.running.isEmpty());

        clock.advance(Duration.ofSeconds(1));
        service.onRelevant(steve.uuid, ActivityType.BLOCK_BREAK, clock.instant());

        assertEquals(1, engine.running.size());
        assertEquals(1, service.recordingOf(steve.uuid).orElseThrow().relevantActions());
    }

    @Test
    void anAfkPlayerKeepsRecordingWhenStopWhileAfkIsDisabled() {
        service = create(TestSupport.config("recording.stop-while-afk", false));
        service.onJoin(steve.player());
        pass(3, true, steve);

        service.onAfk(steve.uuid, clock.instant());

        assertEquals(1, engine.running.size());
        assertTrue(service.recordingOf(steve.uuid).orElseThrow().afk());
    }

    // ------------------------------------------------------------------ Abschnitte (Testfall 6)

    @Test
    void severalSegmentsFromOneRecordingBecomeSeveralRowsWithTheSameSessionKey() {
        service.onJoin(steve.player());
        String raw = currentRaw();
        pass(3, true, steve);
        pass(120, false);
        pass(3, true, steve);

        service.onQuit(steve.player());

        assertEquals(1, engine.savedCuts.size());
        List<SegmentCut> cuts = engine.savedCuts.get(0);
        assertEquals(2, cuts.size());
        assertEquals(List.of(new SegmentCut(cuts.get(0).replayName(), 0, 260),
                new SegmentCut(cuts.get(1).replayName(), 2280, 2520)), cuts);

        assertEquals(2, store.segmentUpserts.size());
        SegmentUpsert first = store.segmentUpserts.get(0);
        SegmentUpsert second = store.segmentUpserts.get(1);
        assertEquals(SESSION_ID, first.sessionKey());
        assertEquals(SESSION_ID, second.sessionKey());
        assertNotEquals(first.replayName(), second.replayName());
        assertTrue(first.replayName().startsWith(ReplayNames.SEGMENT_PREFIX));
        assertEquals(raw, first.rawName());
        assertEquals(raw, second.rawName());
        assertEquals("dev", first.serverName());
        assertEquals("FILE", first.storageBackend());
        assertEquals("plugins/AdvancedReplay/replays/" + first.replayName() + ".replay", first.storageRef());
        assertEquals(SegmentStatus.AVAILABLE, first.status());
        assertEquals(2048L, first.sizeBytes());
        assertEquals(Map.of("block-place", 3), first.actionSummary());
        assertEquals(3, first.relevantActions());

        // Vorlauf durch den Aufnahmebeginn begrenzt, Nachlauf durch das Aufnahmeende
        assertEquals(1_000, first.preRollMillis());
        assertEquals(10_000, first.postRollMillis());
        assertEquals(10_000, second.preRollMillis());
        assertEquals(0, second.postRollMillis());

        assertEquals(START, first.startedAt());
        assertEquals(START.plusSeconds(1), first.firstActionAt());
        assertEquals(START.plusSeconds(3), first.lastActionAt());
        assertEquals(START.plusSeconds(13), first.endedAt());
        assertEquals(START.plusSeconds(124), second.firstActionAt());
        assertEquals(260, first.durationTicks());

        assertEquals(RecordingStatus.FINALIZED, store.statusOf(raw));
        assertEquals(List.of(RecordingStatus.RECORDING, RecordingStatus.FINALIZING, RecordingStatus.FINALIZED),
                store.statusHistory(raw));
        assertEquals(6, store.lastFinish(raw).relevantActions());
        assertEquals(2, service.stats().segmentsSaved());

        List<SharedEvent> events = shared.published.stream()
                .filter(event -> event.type().equals(SharedEvent.SEGMENT_SAVED)).toList();
        assertEquals(2, events.size());
        assertEquals(first.replayName(), events.get(0).data().get("replayName"));
        assertEquals(SESSION_ID, events.get(0).data().get("sessionKey"));
        assertEquals("instance-dev-1", events.get(0).originInstance());
    }

    @Test
    void segmentTimesFollowTheSampledTickClockWhenTheServerLags() {
        service.onJoin(steve.player());
        // Server läuft mit halber Geschwindigkeit: 10 Ticks pro Sekunde
        for (int i = 0; i < 60; i++) {
            clock.advance(Duration.ofSeconds(1));
            engine.advance(10);
            if (i >= 40 && i < 45) {
                service.onRelevant(steve.uuid, ActivityType.BLOCK_PLACE, clock.instant());
            }
            service.tick(clock.instant());
        }

        service.onQuit(steve.player());

        SegmentUpsert segment = store.segmentUpserts.get(0);
        assertEquals(START.plusSeconds(41), segment.firstActionAt());
        assertEquals(START.plusSeconds(45), segment.lastActionAt());
        // 200 Ticks Vorlauf entsprechen hier 20 Sekunden Wanduhr
        assertEquals(START.plusSeconds(21), segment.startedAt());
    }

    @Test
    void quittingFinalizesTheRecordingAndClearsTheSharedState() {
        service.onJoin(steve.player());
        String raw = currentRaw();
        pass(4, true, steve);

        service.onQuit(steve.player());

        assertEquals(RecordingStatus.FINALIZED, store.statusOf(raw));
        assertTrue(service.recordingOf(steve.uuid).isEmpty());
        assertFalse(shared.recordingStates.containsKey(steve.uuid));
        assertEquals(0, service.stats().pendingFinalizations());
        assertEquals(1, planner.calls.size());
        assertEquals(80, planner.calls.get(0).endTick());
    }

    @Test
    void aRecordingWithoutEnoughRelevantActionsIsDiscarded() {
        service.onJoin(steve.player());
        String raw = currentRaw();
        pass(2, true, steve);
        pass(10, false);

        service.onQuit(steve.player());

        assertEquals(List.of(raw), engine.stoppedDiscarded);
        assertTrue(engine.stoppedKept.isEmpty());
        assertEquals(RecordingStatus.DISCARDED, store.statusOf(raw));
        assertTrue(planner.calls.isEmpty());
        assertTrue(store.segmentUpserts.isEmpty());
        assertEquals(1, service.stats().recordingsDiscarded());
    }

    @Test
    void aRecordingWithoutAnyActionsInAdvancedReplayIsDiscarded() {
        engine.defaultActions = 0;
        service.onJoin(steve.player());
        String raw = currentRaw();
        pass(4, true, steve);

        service.onQuit(steve.player());

        assertEquals(List.of(raw), engine.stoppedKept);
        assertEquals(RecordingStatus.DISCARDED, store.statusOf(raw));
        assertTrue(planner.calls.isEmpty());
        assertEquals(0, service.stats().pendingFinalizations());
    }

    @Test
    void aPlanWithoutSegmentsDiscardsTheRecording() {
        planner.override = (events, endTick) -> List.of();
        service.onJoin(steve.player());
        String raw = currentRaw();
        pass(4, true, steve);

        service.onQuit(steve.player());

        assertEquals(RecordingStatus.DISCARDED, store.statusOf(raw));
        assertTrue(engine.savedCuts.isEmpty());
    }

    @Test
    void aFailedSegmentSaveMarksTheRecordingFailed() {
        engine.segmentResult = cut -> new SavedSegment(cut.replayName(), 0, null, null, false, "Platte voll");
        service.onJoin(steve.player());
        String raw = currentRaw();
        pass(4, true, steve);

        service.onQuit(steve.player());

        assertEquals(RecordingStatus.FAILED, store.statusOf(raw));
        assertTrue(store.lastFinish(raw).error().contains("Platte voll"));
        assertTrue(store.segmentUpserts.isEmpty());
        assertEquals(1, service.stats().failures());
    }

    @Test
    void withoutAnySessionTheSegmentsAreStoredUnderAProvisionalKey() {
        sessions.sessions.clear();
        service.onJoin(steve.player());
        pass(4, true, steve);

        service.onQuit(steve.player());

        String key = store.segmentUpserts.get(0).sessionKey();
        assertTrue(key.startsWith(NetworkSession.PROVISIONAL_PREFIX), key);
    }

    // ------------------------------------------------------------------ Welt- und Spielmoduswechsel

    @Test
    void changingToANonRecordedWorldStopsTheRecordingAndComingBackStartsANewOne() {
        service = create(TestSupport.config("recording.excluded-worlds", List.of("spawn")));
        service.onJoin(steve.player());
        String first = currentRaw();
        pass(4, true, steve);

        steve.world = "spawn";
        service.reevaluateSoon(steve.player());
        assertEquals(1, engine.running.size(), "Neu bewertet wird erst im nächsten Tick");
        tasks.runDelayed();

        assertTrue(engine.running.isEmpty());
        assertEquals(RecordingStatus.FINALIZED, store.statusOf(first));

        steve.world = "world";
        service.reevaluateSoon(steve.player());
        tasks.runDelayed();

        assertEquals(1, engine.running.size());
        assertNotEquals(first, currentRaw());
    }

    @Test
    void switchingToAnExcludedGameModeStopsTheRecording() {
        service.onJoin(steve.player());
        pass(4, true, steve);

        steve.gameMode = GameMode.SPECTATOR;
        service.reevaluateSoon(steve.player());
        tasks.runDelayed();

        assertTrue(engine.running.isEmpty());
        assertEquals(1, store.segmentUpserts.size());
    }

    @Test
    void aPlayerWatchingAReplayIsNotRecorded() {
        service.onJoin(steve.player());
        pass(4, true, steve);

        engine.watching.add(steve.uuid);
        pass(1, false);

        assertTrue(engine.running.isEmpty());
        pass(5, false);
        assertTrue(engine.running.isEmpty());

        engine.watching.remove(steve.uuid);
        pass(1, false);
        assertEquals(1, engine.running.size());
    }

    // ------------------------------------------------------------------ Rotation und AR-Grenzen

    @Test
    void rotatesAtALullAfterTheRotationInterval() {
        service = create(TestSupport.config("recording.rotation-minutes", 1));
        service.onJoin(steve.player());
        String first = currentRaw();

        pass(70, true, steve);
        assertEquals(first, currentRaw(), "Keine Rotation, solange gebaut wird");

        pass(11, false);
        assertEquals(first, currentRaw(), "Nachlauf + 2 s sind noch nicht vorbei");

        pass(1, false);
        assertNotEquals(first, currentRaw());
        assertEquals(RecordingStatus.FINALIZED, store.statusOf(first));
        SegmentUpsert segment = store.segmentUpserts.get(0);
        assertEquals(10_000, segment.postRollMillis(), "Der volle Nachlauf liegt in der alten Aufnahme");
    }

    @Test
    void rotatesUnconditionallyAtOneAndAHalfTimesTheRotationInterval() {
        service = create(TestSupport.config("recording.rotation-minutes", 1));
        service.onJoin(steve.player());
        String first = currentRaw();

        pass(89, true, steve);
        assertEquals(first, currentRaw());

        pass(1, true, steve);
        assertNotEquals(first, currentRaw());
        assertEquals(RecordingStatus.FINALIZED, store.statusOf(first));
    }

    @Test
    void rotatesBeforeAdvancedReplaysMaxLengthWouldDiscardTheRecording() {
        engine.maxLengthSeconds = 120;
        service.onJoin(steve.player());
        String first = currentRaw();

        pass(59, true, steve);
        assertEquals(first, currentRaw());

        pass(1, true, steve);
        assertNotEquals(first, currentRaw(), "max_length 120 s − 60 s Sicherheitsabstand");
    }

    @Test
    void anUnexpectedStopByAdvancedReplayMarksTheRecordingFailedAndStartsANewOne() {
        service.onJoin(steve.player());
        String first = currentRaw();
        pass(4, true, steve);

        engine.discardSilently(first);
        pass(1, false);

        assertEquals(RecordingStatus.FAILED, store.statusOf(first));
        assertTrue(store.lastFinish(first).error().contains("max_length"));
        assertEquals(1, engine.running.size());
        assertNotEquals(first, currentRaw());
        assertEquals(1, service.stats().failures());
    }

    @Test
    void aRecordingSavedByAdvancedReplayItselfIsStillSplitIntoSegments() {
        service.onJoin(steve.player());
        String first = currentRaw();
        pass(4, true, steve);

        engine.saveByAdvancedReplay(first);

        assertEquals(RecordingStatus.FINALIZED, store.statusOf(first));
        assertEquals(1, store.segmentUpserts.size());

        pass(1, false);
        assertEquals(1, engine.running.size());
        assertNotEquals(first, currentRaw());
        assertEquals(0, service.stats().pendingFinalizations());
    }

    @Test
    void aFailedStartIsRetriedAfterThirtySeconds() {
        engine.startFailure = new ReplayEngineException("ProtocolLib fehlt");
        service.onJoin(steve.player());
        assertTrue(engine.started.isEmpty());
        assertEquals(1, service.stats().failures());

        engine.startFailure = null;
        pass(29, false);
        assertTrue(engine.started.isEmpty());

        pass(1, false);
        assertEquals(1, engine.started.size());
    }

    @Test
    void theRecordingStateIsRefreshedByTheHeartbeat() {
        service.onJoin(steve.player());
        String raw = currentRaw();
        shared.calls.clear();

        pass(1, false);
        pass(14, false);
        long beats = shared.calls.stream().filter(call -> call.equals("putRecordingState:" + raw)).count();
        assertEquals(1, beats);

        pass(15, false);
        beats = shared.calls.stream().filter(call -> call.equals("putRecordingState:" + raw)).count();
        assertEquals(2, beats);
    }

    @Test
    void activeRecordingsReportTickActionsAndAfkState() {
        FakePlayer alex = new FakePlayer("Alex");
        service.onJoin(steve.player());
        service.onJoin(alex.player());
        pass(5, true, steve);

        List<ActiveRecordingInfo> active = service.activeRecordings();
        assertEquals(2, active.size());
        ActiveRecordingInfo steveInfo = service.recordingOf(steve.uuid).orElseThrow();
        assertEquals(100, steveInfo.currentTick());
        assertEquals(5, steveInfo.relevantActions());
        assertEquals("world", steveInfo.worldName());
        assertEquals(START, steveInfo.startedAt());
        assertNull(service.recordingOf(alex.uuid).orElseThrow().sessionRef());

        RecordingStats stats = service.stats();
        assertEquals(2, stats.active());
    }

    @Test
    void indicatorsAreKeptOnTheTimelineButDoNotCountAsRelevant() {
        service.onJoin(steve.player());
        pass(3, true, steve);
        clock.advance(Duration.ofSeconds(1));
        engine.advance(20);
        service.onIndicator(steve.uuid, ActivityType.MOVE, clock.instant());

        service.onQuit(steve.player());

        var events = planner.calls.get(0).events();
        assertEquals(4, events.size());
        assertEquals(3, events.stream().filter(event -> event.relevant()).count());
        assertEquals(ActivityType.MOVE, events.get(3).type());
        assertEquals(80, events.get(3).tick());
    }

    // ------------------------------------------------------------------ Herunterfahren / Neustart

    @Test
    void shutdownFinalizesEveryRecordingInsideTheBudget() {
        FakePlayer alex = new FakePlayer("Alex");
        sessions.cloudSession(alex.uuid, "aaaa0000-1111-2222-3333-444455556666");
        service.onJoin(steve.player());
        service.onJoin(alex.player());
        pass(5, true, steve, alex);

        service.shutdown(5_000);

        assertEquals(2, store.segmentUpserts.size());
        Set<RecordingStatus> statuses = engine.stoppedKept.stream().map(store::statusOf).collect(Collectors.toSet());
        assertEquals(Set.of(RecordingStatus.FINALIZED), statuses);
        assertTrue(engine.savedRaw.isEmpty());
        assertFalse(Files.exists(dataFolder.resolve("pending")) && hasJson(dataFolder.resolve("pending")));
        assertEquals(0, service.stats().pendingFinalizations());

        // nach dem Herunterfahren werden keine Aufnahmen mehr gestartet
        service.onJoin(new FakePlayer("Late").player());
        assertTrue(engine.running.isEmpty());
    }

    @Test
    void aServerRestartDuringARecordingKeepsTheRawRecordingPendingAndSplitsItAfterwards() throws Exception {
        FakePlayer alex = new FakePlayer("Alex");
        sessions.cloudSession(alex.uuid, "aaaa0000-1111-2222-3333-444455556666");
        service.onJoin(steve.player());
        service.onJoin(alex.player());
        String steveRaw = engine.started.get(0);
        String alexRaw = engine.started.get(1);
        pass(5, true, steve, alex);
        pass(1, false);
        planner.calls.clear();

        service.shutdown(0);

        assertEquals(RecordingStatus.RAW_PENDING, store.statusOf(steveRaw));
        assertEquals(RecordingStatus.RAW_PENDING, store.statusOf(alexRaw));
        assertEquals(Set.of(steveRaw, alexRaw), engine.savedRaw.keySet());
        assertTrue(Files.isRegularFile(dataFolder.resolve("pending").resolve(steveRaw + ".json")));
        assertTrue(Files.isRegularFile(dataFolder.resolve("pending").resolve(alexRaw + ".json")));
        assertTrue(planner.calls.isEmpty(), "Nichts wurde mehr zerlegt");
        assertTrue(store.segmentUpserts.isEmpty());

        // Neustart: neuer Dienst, neuer Store, Sitzungen sind noch nicht wieder bekannt
        FakeStore restartedStore = new FakeStore();
        FakeSessions restartedSessions = new FakeSessions();
        RecordingService restarted = new RecordingService(TestSupport.config(), TestSupport.DEV, tasks, clock, engine,
                restartedSessions, restartedStore, shared, planner, dataFolder, TestSupport.quietLogger(logs));
        List<RecordingRow> rows = List.of(pendingRow(1, steveRaw, steve), pendingRow(2, alexRaw, alex));

        int recovered = restarted.recoverPending(rows).get();

        assertEquals(2, recovered);
        assertEquals(2, restartedStore.segmentUpserts.size());
        SegmentUpsert steveSegment = restartedStore.segmentUpserts.stream()
                .filter(upsert -> upsert.rawName().equals(steveRaw)).findFirst().orElseThrow();
        assertEquals(SESSION_ID, steveSegment.sessionKey(), "Sitzung kommt aus der gesicherten Zeitachse");
        assertEquals(START.plusSeconds(1), steveSegment.firstActionAt());
        assertEquals(RecordingStatus.FINALIZED, restartedStore.statusOf(steveRaw));
        assertEquals(RecordingStatus.FINALIZED, restartedStore.statusOf(alexRaw));
        assertTrue(engine.deleted.containsAll(List.of(steveRaw, alexRaw)));
        assertFalse(Files.exists(dataFolder.resolve("pending").resolve(steveRaw + ".json")));
        assertEquals(5, restartedStore.lastFinish(steveRaw).relevantActions());
    }

    @Test
    void recoveryMarksRecordingsWithMissingPiecesFailed() throws Exception {
        service.onJoin(steve.player());
        String raw = currentRaw();
        pass(5, true, steve);
        service.shutdown(0);
        engine.savedRaw.remove(raw);

        FakeStore restartedStore = new FakeStore();
        RecordingService restarted = new RecordingService(TestSupport.config(), TestSupport.DEV, tasks, clock, engine,
                sessions, restartedStore, shared, planner, dataFolder, TestSupport.quietLogger(logs));
        String withoutTimeline = ReplayNames.newRawName();
        engine.saveRaw(new FakeReplayEngine.FakeRaw(withoutTimeline, "Steve", steve.uuid, 100, 5, "world"));

        int recovered = restarted.recoverPending(List.of(pendingRow(1, raw, steve),
                pendingRow(2, withoutTimeline, steve))).get();

        assertEquals(0, recovered);
        assertEquals(RecordingStatus.FAILED, restartedStore.statusOf(raw));
        assertTrue(restartedStore.lastFinish(raw).error().contains("Rohaufnahme fehlt"));
        assertEquals(RecordingStatus.FAILED, restartedStore.statusOf(withoutTimeline));
        assertTrue(restartedStore.lastFinish(withoutTimeline).error().contains("Zeitachse"));
        assertTrue(engine.deleted.contains(withoutTimeline));
        assertFalse(Files.exists(dataFolder.resolve("pending").resolve(raw + ".json")));
    }

    @Test
    void recoveringTheSameRowsTwiceNeitherDuplicatesSegmentsNorOverwritesTheResult() throws Exception {
        service.onJoin(steve.player());
        String raw = currentRaw();
        pass(5, true, steve);
        service.shutdown(0);

        FakeStore restartedStore = new FakeStore();
        RecordingService restarted = new RecordingService(TestSupport.config(), TestSupport.DEV, tasks, clock, engine,
                sessions, restartedStore, shared, planner, dataFolder, TestSupport.quietLogger(logs));
        List<RecordingRow> staleRows = List.of(pendingRow(1, raw, steve));

        assertEquals(1, restarted.recoverPending(staleRows).get());
        assertEquals(0, restarted.recoverPending(staleRows).get());

        assertEquals(1, restartedStore.segmentUpserts.size());
        assertEquals(List.of(RecordingStatus.FINALIZED), restartedStore.statusHistory(raw));
    }

    @Test
    void recoverySkipsRowsOfOtherServers() throws Exception {
        RecordingRow foreign = new RecordingRow(9, ReplayNames.newRawName(), null, steve.uuid, "Steve", "build",
                "other", "world", START, START, RecordingStatus.RAW_PENDING, 4, null);

        assertEquals(0, service.recoverPending(List.of(foreign)).get());
        assertTrue(store.finishes.isEmpty());
    }

    @Test
    void whenTheIoPoolIsGoneTheRawRecordingIsParkedInsteadOfLost() {
        service.onJoin(steve.player());
        String raw = currentRaw();
        pass(5, true, steve);

        tasks.rejectIo = true;
        service.onQuit(steve.player());

        assertEquals(RecordingStatus.RAW_PENDING, store.statusOf(raw));
        assertTrue(engine.savedRaw.containsKey(raw));
        assertTrue(Files.isRegularFile(dataFolder.resolve("pending").resolve(raw + ".json")));
    }

    private RecordingRow pendingRow(long id, String rawName, FakePlayer player) {
        return new RecordingRow(id, rawName, null, player.uuid, player.name, "dev", "instance-dev-1", "world", START,
                START.plusSeconds(6), RecordingStatus.RAW_PENDING, 5, null);
    }

    private static boolean hasJson(Path directory) {
        try (var files = Files.list(directory)) {
            return files.anyMatch(file -> file.toString().endsWith(".json"));
        } catch (java.io.IOException e) {
            return false;
        }
    }

    @Test
    void theTimelineFileRoundTripsEventsAndSamples() throws Exception {
        TimelineFiles files = new TimelineFiles(dataFolder.resolve("pending"));
        ActiveRecording recording = new ActiveRecording(new RecordingMeta(ReplayNames.newRawName(), steve.uuid,
                "Steve", "world", START), SESSION_ID);
        recording.record(20, START.plusSeconds(1), ActivityType.BLOCK_PLACE, true);
        recording.record(40, START.plusSeconds(2), ActivityType.CHAT, false);
        recording.sample(100, START.plusSeconds(5));

        files.write(new TimelineFiles.Pending(recording.meta(), SESSION_ID, START.plusSeconds(6), 1,
                recording.timeline().snapshot()));
        TimelineFiles.Pending read = files.read(recording.rawName()).orElseThrow();

        assertEquals(recording.meta(), read.meta());
        assertEquals(SESSION_ID, read.sessionKey());
        assertEquals(START.plusSeconds(6), read.endedAt());
        assertEquals(recording.timeline().snapshot(), read.timeline());
        assertNotNull(read.timeline().timeOfTick(50));
        assertTrue(files.read(ReplayNames.newRawName()).isEmpty());
    }
}
