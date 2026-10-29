package net.techniqstone.buildReplay.playback.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.LogRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.techniqstone.buildReplay.command.Permissions;
import net.techniqstone.buildReplay.config.Messages;
import net.techniqstone.buildReplay.config.PluginConfig;
import net.techniqstone.buildReplay.recording.PlaybackOptions;
import net.techniqstone.buildReplay.recording.PlaybackStart;
import net.techniqstone.buildReplay.recording.service.fakes.FakePlatform;
import net.techniqstone.buildReplay.recording.service.fakes.FakePlayer;
import net.techniqstone.buildReplay.recording.service.fakes.FakeReplayEngine;
import net.techniqstone.buildReplay.recording.service.fakes.FakeSharedState;
import net.techniqstone.buildReplay.recording.service.fakes.FakeStore;
import net.techniqstone.buildReplay.recording.service.fakes.FakeTaskRunner;
import net.techniqstone.buildReplay.recording.service.fakes.TestSupport;
import net.techniqstone.buildReplay.simplecloud.SimpleCloudPlatform.ConnectOutcome;
import net.techniqstone.buildReplay.state.HandoffRequest;
import net.techniqstone.buildReplay.storage.model.SegmentRow;
import net.techniqstone.buildReplay.storage.model.SegmentStatus;
import net.techniqstone.buildReplay.storage.model.SessionRow;
import net.techniqstone.buildReplay.storage.model.SessionSource;
import net.techniqstone.buildReplay.storage.model.SessionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlaybackServiceTest {

    private static final Instant START = Instant.parse("2026-10-29T12:00:00Z");
    private static final String SESSION_ID = "59dc83e5-1111-2222-3333-444455556666";
    private static final long SESSION_DB_ID = 10L;

    private final List<LogRecord> logs = new ArrayList<>();
    private TestSupport.MutableClock clock;
    private FakeTaskRunner tasks;
    private FakeReplayEngine engine;
    private FakeStore store;
    private FakeSharedState shared;
    private FakePlatform platform;
    private FakeInventoryProtection inventory;
    private PlaybackService service;
    private FakePlayer steve;
    private FakePlayer alex;

    @BeforeEach
    void setUp() {
        clock = new TestSupport.MutableClock(START);
        tasks = new FakeTaskRunner();
        engine = new FakeReplayEngine();
        store = new FakeStore();
        shared = new FakeSharedState();
        platform = new FakePlatform();
        inventory = new FakeInventoryProtection();
        steve = new FakePlayer("Steve").grant(Permissions.PLAY);
        alex = new FakePlayer("Alex").grant(Permissions.PLAY);
        service = create(TestSupport.config());
    }

    private PlaybackService create(PluginConfig config) {
        return new PlaybackService(config, TestSupport.DEV, tasks, clock, engine, store, shared, platform,
                Messages.fallbackOnly(), TestSupport.quietLogger(logs), inventory);
    }

    private SegmentRow segment(long id, FakePlayer owner, String server, SegmentStatus status, int startOffset) {
        Instant startedAt = START.plusSeconds(startOffset);
        SegmentRow row = new SegmentRow(id, "br-test-" + id, SESSION_DB_ID, null, owner.uuid, owner.name, server,
                "world", "FILE", null, startedAt, startedAt.plusSeconds(20), startedAt.plusSeconds(10),
                startedAt.plusSeconds(10), 400, 10_000, 10_000, 5, Map.of("block-place", 5), status, 2048L, null,
                startedAt);
        store.segments.put(id, row);
        return row;
    }

    private void session(FakePlayer owner, String ref, SegmentRow... segments) {
        SessionRow row = new SessionRow(SESSION_DB_ID, SESSION_ID, SESSION_ID, owner.uuid, owner.name,
                SessionSource.SIMPLECLOUD, SessionStatus.CLOSED, START, START.plusSeconds(3600), 3600L, null, START,
                START);
        store.sessionsByRef.put(FakeStore.refKey(owner.uuid, ref), List.of(row));
        store.sessionsById.put(SESSION_DB_ID, row);
        store.segmentsBySession.put(SESSION_DB_ID, List.of(segments));
    }

    // ------------------------------------------------------------------ Lokale Wiedergabe

    @Test
    void playsAnOwnLocalSegmentWithTheConfiguredOptions() {
        segment(7, steve, "dev", SegmentStatus.AVAILABLE, 0);

        service.playSegment(steve.player(), 7);

        assertEquals(List.of("br-test-7"), engine.played);
        assertEquals(new PlaybackOptions(true, true), engine.playOptions.get(0));
        assertEquals(List.of("backup"), inventory.calls);
        assertTrue(steve.received("Replay #7 von Steve startet (0:20)"), steve.plainMessages().toString());
        assertTrue(service.isWatching(steve.uuid));
    }

    @Test
    void finishingAPlaybackDropsTheBackupBecauseAdvancedReplayRestoredTheViewer() {
        segment(7, steve, "dev", SegmentStatus.AVAILABLE, 0);
        service.playSegment(steve.player(), 7);

        engine.finish(steve.player());

        assertEquals(List.of("backup", "discard"), inventory.calls);
        assertFalse(service.isWatching(steve.uuid));
        assertEquals(1, engine.finishListenerCount());
    }

    @Test
    void withoutInventoryProtectionNoBackupIsTaken() {
        service = create(TestSupport.config("playback.protect-inventory", false,
                "playback.force-client-side-block-changes", false, "playback.show-initial-state", false));
        segment(7, steve, "dev", SegmentStatus.AVAILABLE, 0);

        service.playSegment(steve.player(), 7);

        assertTrue(inventory.calls.isEmpty());
        assertEquals(new PlaybackOptions(false, false), engine.playOptions.get(0));
    }

    @Test
    void aMissingReplayFileMarksTheSegmentMissingAndTellsTheViewer() {
        SegmentRow row = segment(7, steve, "dev", SegmentStatus.AVAILABLE, 0);
        engine.playResults.put(row.replayName(), PlaybackStart.failed("playback.not-found", "br-test-7 existiert nicht"));

        service.playSegment(steve.player(), 7);

        assertEquals(List.of(new FakeStore.StatusUpdate("br-test-7", SegmentStatus.MISSING, "br-test-7 existiert nicht")),
                store.statusUpdates);
        assertTrue(steve.received("Die Replay-Datei von Abschnitt #7 fehlt"), steve.plainMessages().toString());
        assertEquals(List.of("backup", "discard"), inventory.calls, "AR hat das Inventar nicht angefasst");
        assertFalse(service.isWatching(steve.uuid));
    }

    @Test
    void aCorruptReplayFileMarksTheSegmentCorruptAndTellsTheViewer() {
        SegmentRow row = segment(8, steve, "dev", SegmentStatus.AVAILABLE, 0);
        engine.playResults.put(row.replayName(), PlaybackStart.failed("playback.corrupt", "StreamCorruptedException"));

        service.playSegment(steve.player(), 8);

        assertEquals(SegmentStatus.CORRUPT, store.statusUpdates.get(0).status());
        assertTrue(steve.received("ist beschädigt"), steve.plainMessages().toString());
    }

    @Test
    void aStartFailureInsideAdvancedReplayRestoresTheInventoryBackup() {
        SegmentRow row = segment(7, steve, "dev", SegmentStatus.AVAILABLE, 0);
        engine.playResults.put(row.replayName(), PlaybackStart.failed("playback.start-failed",
                "FieldAccessException: Field index 1 is out of bounds"));

        service.playSegment(steve.player(), 7);

        assertEquals(List.of("backup", "restore"), inventory.calls);
        assertTrue(steve.received("Field index 1 is out of bounds"), steve.plainMessages().toString());
        assertTrue(store.statusUpdates.isEmpty());
    }

    @Test
    void aMissingWorldIsReportedWithItsName() {
        SegmentRow row = segment(7, steve, "dev", SegmentStatus.AVAILABLE, 0);
        engine.playResults.put(row.replayName(), PlaybackStart.failed("playback.world-missing", "world"));

        service.playSegment(steve.player(), 7);

        assertTrue(steve.received("Die Welt world von Abschnitt #7 ist auf diesem Server nicht geladen"),
                steve.plainMessages().toString());
    }

    @Test
    void anUnknownSegmentIdIsReportedAsNotFound() {
        service.playSegment(steve.player(), 99);

        assertTrue(steve.received("Abschnitt #99 wurde nicht gefunden"));
        assertTrue(engine.played.isEmpty());
        assertFalse(service.isWatching(steve.uuid));
    }

    @Test
    void anUnreachableDatabaseShowsAFriendlyMessage() {
        store.unavailable = true;

        service.playSegment(steve.player(), 7);

        assertTrue(steve.received("Replay-Datenbank ist gerade nicht erreichbar"));
    }

    @Test
    void aSegmentThatIsNotPlayableShowsItsStatus() {
        segment(7, steve, "dev", SegmentStatus.DELETED, 0);

        service.playSegment(steve.player(), 7);

        assertTrue(steve.received("Abschnitt #7 ist nicht abspielbar (Status: gelöscht)"));
        assertTrue(engine.played.isEmpty());
    }

    @Test
    void anIncompatibleEngineExplainsWhyPlaybackIsImpossible() {
        engine.playbackCompatible = false;
        engine.incompatibilityReason = "ProtocolLib 5.4.0 kann auf 1.21.11 keine NPCs erzeugen";
        segment(7, steve, "dev", SegmentStatus.AVAILABLE, 0);

        service.playSegment(steve.player(), 7);

        assertTrue(steve.received("ProtocolLib 5.4.0 kann auf 1.21.11 keine NPCs erzeugen"));
        assertTrue(engine.played.isEmpty());
        assertTrue(inventory.calls.isEmpty());
    }

    @Test
    void watchingTwoReplaysAtOnceIsRefused() {
        segment(7, steve, "dev", SegmentStatus.AVAILABLE, 0);
        segment(8, steve, "dev", SegmentStatus.AVAILABLE, 30);
        service.playSegment(steve.player(), 7);

        service.playSegment(steve.player(), 8);

        assertEquals(List.of("br-test-7"), engine.played);
        assertTrue(steve.received("Du siehst bereits ein Replay"));
    }

    // ------------------------------------------------------------------ Berechtigungen

    @Test
    void playingAnotherPlayersSegmentRequiresTheOthersPermission() {
        segment(7, alex, "dev", SegmentStatus.AVAILABLE, 0);

        service.playSegment(steve.player(), 7);

        assertTrue(steve.received("Du darfst nur deine eigenen Replays abspielen"));
        assertTrue(engine.played.isEmpty());
        assertTrue(inventory.calls.isEmpty());

        steve.grant(Permissions.OTHERS);
        service.playSegment(steve.player(), 7);

        assertEquals(List.of("br-test-7"), engine.played);
        assertTrue(steve.received("Replay #7 von Alex startet"));
    }

    @Test
    void playingAnotherPlayersSessionRequiresTheOthersPermission() {
        session(alex, "59dc83e5", segment(1, alex, "dev", SegmentStatus.AVAILABLE, 0));

        service.playSession(steve.player(), alex.uuid, "59dc83e5");

        assertTrue(steve.received("Du darfst nur deine eigenen Replays abspielen"));
        assertTrue(engine.played.isEmpty());
    }

    // ------------------------------------------------------------------ Andere Server

    @Test
    void aSegmentOfAnotherServerIsHandedOffAndThePlayerIsConnected() {
        segment(7, steve, "build", SegmentStatus.AVAILABLE, 0);

        service.playSegment(steve.player(), 7);

        HandoffRequest request = shared.handoffs.get(steve.uuid);
        assertNotNull(request);
        assertEquals(7, request.segmentId());
        assertEquals("br-test-7", request.replayName());
        assertEquals("build", request.targetServer());
        assertEquals("dev", request.requestedOn());
        assertNull(request.sessionOwner());
        assertEquals(Duration.ofSeconds(60), shared.handoffTtls.get(steve.uuid));
        assertEquals(List.of(steve.uuid + "->build"), platform.connects);
        assertTrue(steve.received("Abschnitt #7 liegt auf build"));
        assertTrue(engine.played.isEmpty());
        assertTrue(inventory.calls.isEmpty());
    }

    @Test
    void withoutTheJavaApiTheViewerGetsAClickableServerHint() {
        platform.outcome = ConnectOutcome.UNSUPPORTED;
        segment(7, steve, "build", SegmentStatus.AVAILABLE, 0);

        service.playSegment(steve.player(), 7);

        Component hint = steve.messages.get(steve.messages.size() - 1);
        ClickEvent click = findClick(hint);
        assertNotNull(click, "Hinweis ist anklickbar");
        assertEquals(ClickEvent.Action.RUN_COMMAND, click.action());
        assertEquals("/server build", ((ClickEvent.Payload.Text) click.payload()).value());
        assertTrue(steve.received("Wechsle mit /server build auf build"));
        assertTrue(shared.handoffs.containsKey(steve.uuid), "Übergabe bleibt für den manuellen Wechsel bestehen");
    }

    @Test
    void anOfflineTargetServerCancelsTheHandoff() {
        platform.outcome = ConnectOutcome.SERVER_NOT_FOUND;
        segment(7, steve, "build", SegmentStatus.AVAILABLE, 0);

        service.playSegment(steve.player(), 7);

        assertFalse(shared.handoffs.containsKey(steve.uuid));
        assertTrue(steve.received("Der Server build ist gerade nicht online"));
    }

    @Test
    void withCrossServerPlaybackDisabledTheViewerIsOnlyToldWhereTheSegmentLives() {
        service = create(TestSupport.config("playback.cross-server", false));
        segment(7, steve, "build", SegmentStatus.AVAILABLE, 0);

        service.playSegment(steve.player(), 7);

        assertTrue(steve.received("wurde auf build aufgenommen und kann nur dort abgespielt werden"));
        assertTrue(shared.handoffs.isEmpty());
        assertTrue(platform.connects.isEmpty());
    }

    @Test
    void aHandoffForThisServerStartsThePlaybackShortlyAfterJoining() {
        segment(7, steve, "dev", SegmentStatus.AVAILABLE, 0);
        shared.handoffs.put(steve.uuid, new HandoffRequest(7, "br-test-7", "dev", "build", null, null,
                START.minusSeconds(5)));

        service.onJoin(steve.player());

        assertFalse(shared.handoffs.containsKey(steve.uuid));
        assertTrue(steve.received("Replay #7 startet gleich"));
        assertTrue(engine.played.isEmpty(), "Start erst nach ~2 Sekunden");
        assertEquals(PlaybackService.HANDOFF_START_DELAY_TICKS, tasks.delayed().get(0).delayTicks());

        tasks.runDelayed();

        assertEquals(List.of("br-test-7"), engine.played);
    }

    @Test
    void aHandoffForAnotherServerIsPutBackForTheRemainingTime() {
        HandoffRequest request = new HandoffRequest(7, "br-test-7", "build", "lobby", null, null, START.minusSeconds(20));
        shared.handoffs.put(steve.uuid, request);

        service.onJoin(steve.player());

        assertEquals(request, shared.handoffs.get(steve.uuid));
        assertEquals(Duration.ofSeconds(40), shared.handoffTtls.get(steve.uuid));
        assertTrue(tasks.delayed().isEmpty());
    }

    @Test
    void anExpiredHandoffIsIgnored() {
        segment(7, steve, "dev", SegmentStatus.AVAILABLE, 0);
        shared.handoffs.put(steve.uuid, new HandoffRequest(7, "br-test-7", "dev", "build", null, null,
                START.minusSeconds(61)));

        service.onJoin(steve.player());
        tasks.runDelayed();

        assertTrue(engine.played.isEmpty());
        assertFalse(shared.handoffs.containsKey(steve.uuid));
    }

    // ------------------------------------------------------------------ Sitzungen

    @Test
    void aSessionPlaysItsLocalSegmentsInOrderAndAdvancesAutomatically() {
        SegmentRow third = segment(3, steve, "dev", SegmentStatus.AVAILABLE, 300);
        SegmentRow first = segment(1, steve, "dev", SegmentStatus.AVAILABLE, 0);
        SegmentRow remote = segment(4, steve, "build", SegmentStatus.AVAILABLE, 150);
        SegmentRow second = segment(2, steve, "dev", SegmentStatus.AVAILABLE, 100);
        session(steve, "59dc83e5", third, first, remote, second);

        service.playSession(steve.player(), steve.uuid, "59dc83e5");

        assertTrue(steve.received("1 Abschnitt(e) dieser Sitzung liegen auf anderen Servern: build (1)"),
                steve.plainMessages().toString());
        assertTrue(steve.received("Spiele 3 Abschnitt(e) der Sitzung 59dc83e5 ab"));
        assertEquals(List.of("br-test-1"), engine.played);
        assertTrue(steve.received("Abschnitt 1/3 der Sitzung 59dc83e5"));

        engine.finish(steve.player());
        assertTrue(steve.received("Nächster Abschnitt (2/3) startet gleich"));
        assertEquals(List.of("br-test-1"), engine.played, "Pause zwischen den Abschnitten");
        assertTrue(tasks.delayed().stream().anyMatch(d -> d.delayTicks() == PlaybackService.NEXT_SEGMENT_DELAY_TICKS));

        tasks.runDelayed();
        assertEquals(List.of("br-test-1", "br-test-2"), engine.played);

        engine.finish(steve.player());
        tasks.runDelayed();
        assertEquals(List.of("br-test-1", "br-test-2", "br-test-3"), engine.played);

        engine.finish(steve.player());
        assertTrue(steve.received("Sitzung 59dc83e5 vollständig abgespielt"));
        tasks.runDelayed(5);
        assertEquals(3, engine.played.size());
        assertEquals(3, inventory.count("backup"));
        assertEquals(3, inventory.count("discard"));
        assertEquals(0, inventory.count("restore"));
    }

    @Test
    void aBrokenSegmentInASessionIsSkipped() {
        SegmentRow first = segment(1, steve, "dev", SegmentStatus.AVAILABLE, 0);
        SegmentRow second = segment(2, steve, "dev", SegmentStatus.AVAILABLE, 100);
        session(steve, "59dc83e5", first, second);
        engine.playResults.put(first.replayName(), PlaybackStart.failed("playback.not-found", "fehlt"));

        service.playSession(steve.player(), steve.uuid, "59dc83e5");
        tasks.runDelayed();

        assertEquals(List.of("br-test-1", "br-test-2"), engine.played);
        assertEquals(SegmentStatus.MISSING, store.statusUpdates.get(0).status());
    }

    @Test
    void withoutAutoAdvanceTheViewerIsToldHowToContinue() {
        service = create(TestSupport.config("playback.auto-advance", false));
        session(steve, "59dc83e5", segment(1, steve, "dev", SegmentStatus.AVAILABLE, 0),
                segment(2, steve, "dev", SegmentStatus.AVAILABLE, 100));

        service.playSession(steve.player(), steve.uuid, "59dc83e5");
        engine.finish(steve.player());
        tasks.runDelayed(3);

        assertEquals(List.of("br-test-1"), engine.played);
        assertTrue(steve.received("Nächster Abschnitt: /buildreplay play 2"));
    }

    @Test
    void stoppingEndsThePlaybackAndTheSessionQueue() {
        session(steve, "59dc83e5", segment(1, steve, "dev", SegmentStatus.AVAILABLE, 0),
                segment(2, steve, "dev", SegmentStatus.AVAILABLE, 100));
        service.playSession(steve.player(), steve.uuid, "59dc83e5");

        service.stop(steve.player());
        tasks.runDelayed(3);

        assertEquals(List.of(steve.uuid), engine.stopWatchingCalls);
        assertEquals(List.of("br-test-1"), engine.played);
        assertTrue(steve.received("Wiedergabe beendet"));
        assertFalse(service.isWatching(steve.uuid));

        service.stop(steve.player());
        assertTrue(steve.received("Du siehst gerade kein Replay"));
    }

    @Test
    void anUnknownOrAmbiguousSessionReferenceIsReported() {
        service.playSession(steve.player(), steve.uuid, "deadbeef");
        assertTrue(steve.received("Sitzung deadbeef wurde nicht gefunden"));

        SessionRow one = new SessionRow(1, "59dc83e5-aaaa-2222-3333-444455556666", null, steve.uuid, "Steve",
                SessionSource.SIMPLECLOUD, SessionStatus.CLOSED, START, null, null, null, START, START);
        SessionRow two = new SessionRow(2, "59dc83e5-bbbb-2222-3333-444455556666", null, steve.uuid, "Steve",
                SessionSource.SIMPLECLOUD, SessionStatus.CLOSED, START, null, null, null, START, START);
        store.sessionsByRef.put(FakeStore.refKey(steve.uuid, "59dc"), List.of(one, two));

        service.playSession(steve.player(), steve.uuid, "59DC");

        assertTrue(steve.received("passt auf mehrere Sitzungen: 59dc83e5aaaa, 59dc83e5bbbb"),
                steve.plainMessages().toString());
    }

    @Test
    void aSessionWithSegmentsOnlyOnAnotherServerIsHandedOffAsAWholeSession() {
        session(steve, "59dc83e5", segment(4, steve, "build", SegmentStatus.AVAILABLE, 0),
                segment(5, steve, "build", SegmentStatus.AVAILABLE, 100));

        service.playSession(steve.player(), steve.uuid, "59dc83e5");

        HandoffRequest request = shared.handoffs.get(steve.uuid);
        assertEquals(4, request.segmentId());
        assertEquals(steve.uuid, request.sessionOwner());
        assertEquals("59dc83e5", request.sessionRef());
        assertEquals(List.of(steve.uuid + "->build"), platform.connects);
    }

    @Test
    void aSessionHandoffContinuesFromTheRequestedSegmentOnTheTargetServer() {
        session(steve, "59dc83e5", segment(1, steve, "dev", SegmentStatus.AVAILABLE, 0),
                segment(2, steve, "dev", SegmentStatus.AVAILABLE, 100),
                segment(3, steve, "dev", SegmentStatus.AVAILABLE, 200));
        shared.handoffs.put(steve.uuid, new HandoffRequest(2, "br-test-2", "dev", "build", steve.uuid, "59dc83e5",
                START));

        service.onJoin(steve.player());
        tasks.runDelayed();

        assertEquals(List.of("br-test-2"), engine.played);
        assertTrue(steve.received("Spiele 2 Abschnitt(e) der Sitzung 59dc83e5 ab"));
    }

    // ------------------------------------------------------------------ Inventarschutz

    @Test
    void quittingWhileWatchingRestoresTheInventoryImmediatelyAndOnlyOnce() {
        segment(7, steve, "dev", SegmentStatus.AVAILABLE, 0);
        service.playSegment(steve.player(), 7);

        service.onQuitEarly(steve.player());
        steve.online = false;
        // AdvancedReplay setzt danach den (schon gegangenen) Spieler zurück und meldet das Ende
        engine.finish(steve.player());
        tasks.runDelayed(5);

        assertEquals(List.of("backup", "restore"), inventory.calls);
        assertFalse(service.isWatching(steve.uuid));
    }

    @Test
    void aPlaybackThatVanishesWithoutAFinishEventIsRecoveredFromTheBackup() {
        segment(7, steve, "dev", SegmentStatus.AVAILABLE, 0);
        service.playSegment(steve.player(), 7);
        tasks.runDelayed();
        assertEquals(List.of("backup"), inventory.calls, "Läuft noch - nichts tun");

        engine.vanish(steve.player());
        tasks.runDelayed(PlaybackService.WATCHDOG_GRACE_CHECKS + 1);

        assertEquals(List.of("backup", "restore"), inventory.calls);
        assertTrue(steve.received("unerwartet beendet - dein Inventar wurde wiederhergestellt"));
        assertFalse(service.isWatching(steve.uuid));
    }

    @Test
    void aFailingStopRestoresTheInventoryFromTheBackup() {
        segment(7, steve, "dev", SegmentStatus.AVAILABLE, 0);
        service.playSegment(steve.player(), 7);
        engine.stopWatchingFailure = new NullPointerException("this.run is null");

        service.stop(steve.player());

        assertEquals(List.of("backup", "restore"), inventory.calls);
        assertTrue(steve.received("Wiedergabe beendet"));
    }

    @Test
    void anOrphanedBackupIsRestoredWhenThePlayerJoins() {
        inventory.orphans.add(steve.uuid);

        service.onJoin(steve.player());

        assertEquals(List.of("restoreOrphan"), inventory.calls);
        assertTrue(steve.received("Dein Inventar aus einer unterbrochenen Wiedergabe wurde wiederhergestellt"));
    }

    @Test
    void shutdownEndsRunningPlaybacksAndRestoresTheViewers() {
        engine.finishOnStopWatching = false;
        segment(7, steve, "dev", SegmentStatus.AVAILABLE, 0);
        service.playSegment(steve.player(), 7);

        service.shutdown();

        assertEquals(List.of(steve.uuid), engine.stopWatchingCalls);
        assertEquals(List.of("backup", "restore", "flush"), inventory.calls);
        assertFalse(service.isWatching(steve.uuid));
    }

    @Test
    void stoppingWhileThePlaybackIsStillLoadingCancelsIt() {
        engine.deferPlay = true;
        segment(7, steve, "dev", SegmentStatus.AVAILABLE, 0);
        service.playSegment(steve.player(), 7);
        assertTrue(service.isWatching(steve.uuid), "Start wird vorbereitet");

        service.stop(steve.player());
        engine.completeDeferred(PlaybackStart.ok(400));

        assertEquals(List.of(steve.uuid), engine.stopWatchingCalls, "Gestartete Wiedergabe sofort beendet");
        assertEquals(List.of("backup", "discard"), inventory.calls);
        assertFalse(service.isWatching(steve.uuid));
    }

    @Test
    void theFinishOfAPlaybackStartedElsewhereOnlyDropsABackup() {
        engine.finish(alex.player());

        assertTrue(inventory.calls.isEmpty());
        assertTrue(alex.messages.isEmpty());
    }

    @Test
    void durationsAreFormattedAsMinutesAndSeconds() {
        assertEquals("0:00", PlaybackService.formatTicks(0));
        assertEquals("0:20", PlaybackService.formatTicks(400));
        assertEquals("2:05", PlaybackService.formatTicks(2_500));
        assertEquals("1:00:00", PlaybackService.formatTicks(72_000));
    }

    private static ClickEvent findClick(Component component) {
        if (component.clickEvent() != null) {
            return component.clickEvent();
        }
        for (Component child : component.children()) {
            ClickEvent click = findClick(child);
            if (click != null) {
                return click;
            }
        }
        return null;
    }
}
