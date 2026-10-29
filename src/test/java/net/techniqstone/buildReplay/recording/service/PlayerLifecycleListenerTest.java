package net.techniqstone.buildReplay.recording.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.LogRecord;
import net.kyori.adventure.text.Component;
import net.techniqstone.buildReplay.activity.ActivityType;
import net.techniqstone.buildReplay.config.Messages;
import net.techniqstone.buildReplay.config.PluginConfig;
import net.techniqstone.buildReplay.playback.service.PlaybackService;
import net.techniqstone.buildReplay.recording.service.fakes.FakeActivityTracking;
import net.techniqstone.buildReplay.recording.service.fakes.FakePlanner;
import net.techniqstone.buildReplay.recording.service.fakes.FakePlatform;
import net.techniqstone.buildReplay.recording.service.fakes.FakePlayer;
import net.techniqstone.buildReplay.recording.service.fakes.FakeReplayEngine;
import net.techniqstone.buildReplay.recording.service.fakes.FakeSessions;
import net.techniqstone.buildReplay.recording.service.fakes.FakeSharedState;
import net.techniqstone.buildReplay.recording.service.fakes.FakeStore;
import net.techniqstone.buildReplay.recording.service.fakes.FakeTaskRunner;
import net.techniqstone.buildReplay.recording.service.fakes.TestSupport;
import net.techniqstone.buildReplay.storage.model.RecordingStatus;
import org.bukkit.GameMode;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlayerLifecycleListenerTest {

    private static final Instant START = Instant.parse("2026-10-29T12:00:00Z");

    @TempDir
    Path dataFolder;

    private final List<String> calls = new ArrayList<>();
    private final List<LogRecord> logs = new ArrayList<>();
    private TestSupport.MutableClock clock;
    private FakeTaskRunner tasks;
    private FakeReplayEngine engine;
    private FakeSessions sessions;
    private FakeSharedState shared;
    private FakeStore store;
    private RecordingService recordings;
    private PlayerLifecycleListener listener;
    private FakePlayer steve;

    @BeforeEach
    void setUp() {
        clock = new TestSupport.MutableClock(START);
        tasks = new FakeTaskRunner();
        engine = new FakeReplayEngine();
        sessions = new FakeSessions(calls);
        shared = new FakeSharedState();
        store = new FakeStore();
        steve = new FakePlayer("Steve");
        sessions.cloudSession(steve.uuid, "59dc83e5-1111-2222-3333-444455556666");
        PluginConfig config = TestSupport.config();
        recordings = new RecordingService(config, TestSupport.DEV, tasks, clock, engine, sessions, store, shared,
                new FakePlanner(), dataFolder, TestSupport.quietLogger(logs));
        engine.install(recordings);
        PlaybackService playback = new PlaybackService(config, TestSupport.DEV, tasks, clock, engine, store, shared,
                new FakePlatform(), Messages.fallbackOnly(), dataFolder, TestSupport.quietLogger(logs));
        listener = new PlayerLifecycleListener(sessions, new FakeActivityTracking(calls), recordings, playback, clock);
    }

    @Test
    void joiningResolvesTheSessionStartsActivityTrackingAndRecordingAndChecksForAHandoff() {
        listener.onJoin(new PlayerJoinEvent(steve.player(), Component.empty()));

        assertEquals(List.of("join:Steve", "activity.start"), calls);
        assertEquals(1, engine.running.size());
        assertTrue(shared.calls.contains("takeHandoff"), "Wiedergabe-Übergabe wird beim Join abgefragt");
    }

    @Test
    void quittingFinalizesTheRecordingBeforeForgettingActivityAndEndingTheVisit() {
        listener.onJoin(new PlayerJoinEvent(steve.player(), Component.empty()));
        String raw = engine.only().rawName;
        for (int i = 0; i < 5; i++) {
            clock.advance(Duration.ofSeconds(1));
            engine.advance(20);
            recordings.onRelevant(steve.uuid, ActivityType.BLOCK_PLACE, clock.instant());
        }
        calls.clear();

        PlayerQuitEvent quit = new PlayerQuitEvent(steve.player(), Component.empty(),
                PlayerQuitEvent.QuitReason.DISCONNECTED);
        listener.onQuitEarly(quit);
        listener.onQuit(quit);

        assertEquals(RecordingStatus.FINALIZED, store.statusOf(raw));
        assertEquals(List.of("activity.forget", "quit:Steve:QUIT"), calls);
        assertTrue(recordings.recordingOf(steve.uuid).isEmpty());
    }

    @Test
    void worldAndGameModeChangesAreReevaluatedOnTheNextTick() {
        listener.onJoin(new PlayerJoinEvent(steve.player(), Component.empty()));

        steve.gameMode = GameMode.SPECTATOR;
        listener.onGameModeChange(new PlayerGameModeChangeEvent(steve.player(), GameMode.SPECTATOR,
                PlayerGameModeChangeEvent.Cause.COMMAND, null));
        assertEquals(1, engine.running.size());
        tasks.runDelayed();
        assertTrue(engine.running.isEmpty());

        steve.gameMode = GameMode.CREATIVE;
        listener.onWorldChange(new PlayerChangedWorldEvent(steve.player(), FakePlayer.world("world_nether")));
        tasks.runDelayed();
        assertEquals(1, engine.running.size());
    }

    @Test
    void theInventoryIsRestoredBeforeAdvancedReplaysQuitHandlerAndTheRestAfterEveryoneElse() throws Exception {
        assertEquals(EventPriority.LOWEST, priorityOf("onQuitEarly", PlayerQuitEvent.class));
        assertEquals(EventPriority.MONITOR, priorityOf("onQuit", PlayerQuitEvent.class));
        assertEquals(EventPriority.MONITOR, priorityOf("onJoin", PlayerJoinEvent.class));
    }

    private static EventPriority priorityOf(String method, Class<?> event) throws NoSuchMethodException {
        Method handler = PlayerLifecycleListener.class.getMethod(method, event);
        return handler.getAnnotation(EventHandler.class).priority();
    }
}
