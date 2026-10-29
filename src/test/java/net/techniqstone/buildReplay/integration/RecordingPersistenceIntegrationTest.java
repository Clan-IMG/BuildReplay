package net.techniqstone.buildReplay.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import net.techniqstone.buildReplay.activity.ActivityType;
import net.techniqstone.buildReplay.activity.impl.ActivityTracker;
import net.techniqstone.buildReplay.activity.impl.SegmentPlanner;
import net.techniqstone.buildReplay.config.PluginConfig;
import net.techniqstone.buildReplay.integration.IntegrationSupport.NetworkCloud;
import net.techniqstone.buildReplay.integration.IntegrationSupport.QuietLog;
import net.techniqstone.buildReplay.recording.ReplayNames;
import net.techniqstone.buildReplay.recording.service.RecordingService;
import net.techniqstone.buildReplay.recording.service.fakes.FakePlayer;
import net.techniqstone.buildReplay.recording.service.fakes.FakeReplayEngine;
import net.techniqstone.buildReplay.recording.service.fakes.FakeTaskRunner;
import net.techniqstone.buildReplay.recording.service.fakes.TestSupport;
import net.techniqstone.buildReplay.server.ServerIdentity;
import net.techniqstone.buildReplay.session.service.SessionService;
import net.techniqstone.buildReplay.simplecloud.CloudSession;
import net.techniqstone.buildReplay.state.SharedEvent;
import net.techniqstone.buildReplay.state.redis.LocalSharedState;
import net.techniqstone.buildReplay.storage.model.RecordingRow;
import net.techniqstone.buildReplay.storage.model.RecordingStatus;
import net.techniqstone.buildReplay.storage.model.SegmentRow;
import net.techniqstone.buildReplay.storage.model.SegmentStatus;
import net.techniqstone.buildReplay.storage.model.SessionRow;
import net.techniqstone.buildReplay.storage.model.SessionSource;
import net.techniqstone.buildReplay.storage.model.VisitEndReason;
import net.techniqstone.buildReplay.storage.model.VisitRow;
import net.techniqstone.buildReplay.storage.sql.EmbeddedMariaDb;
import net.techniqstone.buildReplay.storage.sql.SqlReplayStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Aufnahmekette mit echten Modulen bis in MariaDB: Sitzungsdienst (SimpleCloud nachgebildet), Aktivitäts- und
 * AFK-Erkennung, Abschnittsplanung, Aufnahmeverwaltung und {@link SqlReplayStore} auf MariaDB4j. Nur AdvancedReplay
 * ist durch eine Fake-Engine ersetzt. Die gespeicherten Abschnitte müssen unter der SimpleCloud-Sitzung liegen.
 */
class RecordingPersistenceIntegrationTest {

    private static final Instant START = Instant.parse("2026-10-29T15:00:00Z");
    private static final ServerIdentity DEV = new ServerIdentity("dev", "instance-dev-it", "dev", "-1", true);

    @TempDir
    Path dataFolder;

    private final QuietLog log = new QuietLog("recording");
    private final TestSupport.MutableClock clock = new TestSupport.MutableClock(START);
    private final FakeTaskRunner tasks = new FakeTaskRunner();
    private final FakeReplayEngine engine = new FakeReplayEngine();
    private final FakePlayer steve = new FakePlayer("Steve");
    private final List<SharedEvent> events = Collections.synchronizedList(new ArrayList<>());
    private SqlReplayStore store;
    private LocalSharedState shared;
    private SessionService sessions;
    private ActivityTracker activity;
    private RecordingService recordings;

    @AfterEach
    void close() {
        if (sessions != null) {
            sessions.close();
        }
        if (shared != null) {
            shared.close();
        }
        if (store != null) {
            store.close();
        }
    }

    private void startServer(PluginConfig config, NetworkCloud cloud) {
        store = new SqlReplayStore(EmbeddedMariaDb.newSettings(), config.resilience(), dataFolder, clock,
                log.logger());
        store.start();
        shared = new LocalSharedState(clock, log.logger());
        shared.subscribe(events::add);
        sessions = new SessionService(config, DEV, tasks, clock, cloud, store, shared, log.logger());
        activity = new ActivityTracker(config.activity(), clock, log.logger());
        recordings = new RecordingService(config, DEV, tasks, clock, engine, sessions, store, shared,
                new SegmentPlanner(config.recording(), config.activity()), dataFolder, log.logger());
        engine.install(recordings);
        activity.addObserver(recordings);
    }

    /** Wie der Join-Ablauf des Plugins (PlayerLifecycleListener): Sitzung, Aktivität, Aufnahme. */
    private void join() {
        sessions.handleJoin(steve.uuid, steve.name, clock.instant());
        activity.start(steve.uuid, clock.instant());
        recordings.onJoin(steve.player());
    }

    private void quit() {
        recordings.onQuit(steve.player());
        activity.forget(steve.uuid);
        sessions.handleQuit(steve.uuid, steve.name, clock.instant(), VisitEndReason.QUIT);
    }

    /** {@code seconds} Sekunden Servertakt; beim Bauen setzt Steve jede Sekunde einen Block. */
    private void play(int seconds, boolean building) {
        for (int i = 0; i < seconds; i++) {
            clock.advance(Duration.ofSeconds(1));
            engine.advance(20);
            if (building) {
                activity.record(steve.uuid, ActivityType.BLOCK_PLACE, clock.instant());
            }
            activity.tick(clock.instant());
            recordings.tick(clock.instant());
        }
    }

    /** Pub/Sub liefert auf einem Hintergrundthread aus. */
    private void awaitSavedEvents(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            synchronized (events) {
                if (events.stream().filter(event -> SharedEvent.SEGMENT_SAVED.equals(event.type())).count() >= expected) {
                    return;
                }
            }
            Thread.sleep(20);
        }
    }

    @Test
    void segmentsOfTwoBuildPhasesArePersistedUnderTheSimpleCloudSession() throws Exception {
        PluginConfig config = IntegrationSupport.config();
        NetworkCloud cloud = new NetworkCloud(true);
        CloudSession login = cloud.login(steve.uuid, START.minusSeconds(5));
        startServer(config, cloud);

        join();
        play(20, true);      // erste Bauphase
        play(100, false);    // nach 60 s ohne Eingabe AFK: Aufnahme wird abgeschlossen und zerlegt
        play(15, true);      // zweite Bauphase in einer neuen Pufferaufnahme
        quit();
        store.flush().get(20, TimeUnit.SECONDS);

        assertEquals(0, store.health().queuedWrites());
        assertEquals(0, store.health().droppedWrites());
        SessionRow session = store.findSessionByKey(login.id()).orElseThrow();
        assertEquals(SessionSource.SIMPLECLOUD, session.source());
        assertEquals(login.id(), session.cloudSessionId());

        List<SegmentRow> segments = store.segmentsOfSession(session.id());
        assertEquals(2, segments.size(), segments.toString());
        assertEquals(List.of(20, 15), segments.stream().map(SegmentRow::relevantActions).toList());
        for (SegmentRow segment : segments) {
            assertEquals(session.id(), segment.sessionId(), "Abschnitt hängt an der Netzwerksitzung");
            assertTrue(segment.replayName().startsWith(ReplayNames.SEGMENT_PREFIX), segment.replayName());
            assertEquals(SegmentStatus.AVAILABLE, segment.status());
            assertEquals("dev", segment.serverName());
            assertEquals(steve.uuid, segment.playerUuid());
            assertEquals(segment.relevantActions(), segment.actionSummary().get(ActivityType.BLOCK_PLACE.configKey()));
            assertTrue(segment.preRollMillis() >= 0 && segment.preRollMillis() <= 10_000, segment.toString());
            assertTrue(segment.postRollMillis() >= 0 && segment.postRollMillis() <= 10_000, segment.toString());
            assertTrue(segment.recordingId() != null, "Verweis auf die Pufferaufnahme");
            assertTrue(engine.stored.contains(segment.replayName()), "Replay im AdvancedReplay-Speicher");
        }
        assertFalse(segments.get(1).startedAt().isBefore(segments.get(0).endedAt()), "AFK-Zeit liegt dazwischen");

        List<RecordingRow> finished = store.recordingsByStatus("dev", EnumSet.of(RecordingStatus.FINALIZED), 10);
        assertEquals(2, finished.size(), finished.toString());
        assertTrue(finished.stream().allMatch(row -> Long.valueOf(session.id()).equals(row.sessionId())));
        assertTrue(store.recordingsByStatus("dev", EnumSet.of(RecordingStatus.RECORDING, RecordingStatus.FAILED,
                RecordingStatus.FINALIZING), 10).isEmpty());

        List<VisitRow> visits = store.visitsOfSession(session.id());
        assertEquals(1, visits.size());
        assertEquals(VisitEndReason.QUIT, visits.getFirst().endReason());

        awaitSavedEvents(2);
        Set<String> savedEventKeys;
        synchronized (events) {
            savedEventKeys = events.stream().filter(event -> SharedEvent.SEGMENT_SAVED.equals(event.type()))
                    .map(event -> event.data().get("sessionKey")).collect(Collectors.toSet());
        }
        assertEquals(Set.of(login.id()), savedEventKeys, "SEGMENT_SAVED mit dem Sitzungsschlüssel");
        assertEquals(2, recordings.stats().segmentsSaved());
        assertEquals(0, recordings.stats().failures());
        assertTrue(log.problems().isEmpty(), log.problems().toString());
    }
}
