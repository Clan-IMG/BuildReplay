package net.techniqstone.buildReplay.session.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import net.techniqstone.buildReplay.config.PluginConfig;
import net.techniqstone.buildReplay.server.ServerIdentity;
import net.techniqstone.buildReplay.session.NetworkSession;
import net.techniqstone.buildReplay.session.SessionServiceStats;
import net.techniqstone.buildReplay.simplecloud.CloudSession;
import net.techniqstone.buildReplay.state.CachedSession;
import net.techniqstone.buildReplay.state.PresenceInfo;
import net.techniqstone.buildReplay.state.SharedEvent;
import net.techniqstone.buildReplay.state.SharedState;
import net.techniqstone.buildReplay.storage.model.SessionRow;
import net.techniqstone.buildReplay.storage.model.SessionSource;
import net.techniqstone.buildReplay.storage.model.SessionStatus;
import net.techniqstone.buildReplay.storage.model.VisitEndReason;
import net.techniqstone.buildReplay.storage.model.VisitRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Sitzungsauflösung, Serverwechsel, Sitzungsende und Abgleich. Zwei {@link SessionService}-Instanzen mit
 * gemeinsamem Store und gemeinsamem Zustand spielen zwei Server desselben Netzwerks. I/O läuft synchron im
 * Testthread, Verzögerungen über virtuelle Zeit.
 */
class SessionServiceTest {

    private static final Instant START = Instant.parse("2026-10-29T12:00:00Z");

    private final MutableClock clock = new MutableClock(START);
    private final ManualScheduler scheduler = new ManualScheduler(clock);
    private final TestTasks tasks = TestTasks.deterministic(scheduler);
    private final FakeSimpleCloudClient cloud = new FakeSimpleCloudClient();
    private final FakeReplayStore store = new FakeReplayStore(clock);
    private final FakeSharedState shared = new FakeSharedState(clock, true);
    private final UUID player = UUID.randomUUID();
    private final List<SessionService> services = new java.util.ArrayList<>();

    @AfterEach
    void closeServices() {
        services.forEach(SessionService::close);
    }

    private SessionService server(String name) {
        return server(name, TestConfigs.defaults(), shared);
    }

    private SessionService server(String name, PluginConfig config, SharedState sharedState) {
        ServerIdentity identity = TestConfigs.server(name);
        SessionService service = new SessionService(config, identity, tasks, clock, cloud, store, sharedState,
                TestConfigs.quietLogger(name));
        services.add(service);
        return service;
    }

    private static NetworkSession resolved(SessionService service, UUID player) {
        CompletableFuture<NetworkSession> future = service.resolve(player);
        assertTrue(future.isDone(), "Sitzung sollte bereits aufgelöst sein");
        return future.join();
    }

    private List<VisitRow> visitsOf(String sessionKey) {
        long id = store.session(sessionKey).orElseThrow().id();
        return store.allVisits().stream().filter(visit -> visit.sessionId() == id).toList();
    }

    // ------------------------------------------------------------------ (1) erster Netzwerk-Login

    @Test
    void firstNetworkJoinCreatesPlayerSessionAndVisit() {
        CloudSession cloudSession = cloud.login(player, "Builder", START.minusSeconds(3));
        SessionService dev = server("dev");

        dev.handleJoin(player, "Builder", START);

        NetworkSession session = resolved(dev, player);
        assertEquals(cloudSession.id(), session.sessionKey());
        assertEquals(cloudSession.id(), session.cloudSessionId());
        assertEquals(SessionSource.SIMPLECLOUD, session.source());
        assertEquals(cloudSession.loginTime(), session.loginTime());
        assertEquals(Optional.of(session), dev.current(player));

        assertEquals(Optional.of("Builder"), store.playerName(player));
        SessionRow row = store.session(cloudSession.id()).orElseThrow();
        assertEquals(SessionStatus.ACTIVE, row.status());
        assertEquals(SessionSource.SIMPLECLOUD, row.source());
        assertEquals(cloudSession.loginTime(), row.loginTime());

        List<VisitRow> visits = store.allVisits();
        assertEquals(1, visits.size());
        VisitRow visit = visits.getFirst();
        assertEquals(row.id(), visit.sessionId());
        assertEquals("dev", visit.serverName());
        assertEquals(START, visit.joinedAt());
        assertNull(visit.leftAt());
        assertTrue(visit.visitKey().endsWith(":" + player + ":" + START.toEpochMilli()));

        assertEquals(cloudSession.id(), shared.cachedSession(player).orElseThrow().sessionKey());
        PresenceInfo presence = shared.presence(player).orElseThrow();
        assertTrue(presence.online());
        assertEquals("dev", presence.serverName());

        SessionServiceStats stats = dev.stats();
        assertEquals(1, stats.localPlayers());
        assertEquals(1, stats.resolvedSessions());
        assertEquals(0, stats.provisionalSessions());
        assertEquals(1, stats.cloudLookups());
    }

    @Test
    void firstJoinRetriesTheCurrentSessionLookupUntilSimpleCloudHasCreatedIt() {
        CloudSession cloudSession = cloud.login(player, "Builder", START);
        cloud.answerCurrentEmpty(3); // Proxy legt die Sitzung erst nach einigen Sekunden an
        SessionService dev = server("dev");

        dev.handleJoin(player, "Builder", START);

        CompletableFuture<NetworkSession> pending = dev.resolve(player);
        assertFalse(pending.isDone());
        assertTrue(dev.current(player).isEmpty());
        assertEquals(1, cloud.currentCalls());

        scheduler.advance(Duration.ofSeconds(1));
        assertEquals(2, cloud.currentCalls(), "erste Wiederholung nach 1 s");
        assertFalse(pending.isDone());

        scheduler.advance(Duration.ofSeconds(10));
        assertTrue(pending.isDone());
        assertEquals(cloudSession.id(), pending.join().sessionKey());
        assertEquals(4, cloud.currentCalls());
        assertTrue(store.allSessions().stream().noneMatch(row -> row.source() == SessionSource.PROVISIONAL));
    }

    @Test
    void firstJoinFallsBackToAProvisionalSessionWhenTheRetryWindowIsExhausted() {
        PluginConfig config = TestConfigs.config(Map.of("simplecloud.current-session-retry-seconds", 5));
        SessionService dev = server("dev", config, shared);

        dev.handleJoin(player, "Builder", START);
        CompletableFuture<NetworkSession> pending = dev.resolve(player);
        scheduler.advance(Duration.ofSeconds(4));
        assertFalse(pending.isDone(), "innerhalb des Fensters wird weiter gefragt");

        scheduler.advance(Duration.ofSeconds(10));
        NetworkSession session = pending.join();
        assertTrue(session.provisional());
        assertTrue(session.sessionKey().startsWith(NetworkSession.PROVISIONAL_PREFIX));
        assertEquals(START, session.loginTime());
        assertEquals(4, cloud.currentCalls(), "Versuche bei 0 s, 1 s, 2,5 s und 4,5 s");
        assertEquals(SessionSource.PROVISIONAL, store.session(session.sessionKey()).orElseThrow().source());
        assertEquals(1, visitsOf(session.sessionKey()).size());
    }

    // ------------------------------------------------------------------ (2) neue Sitzung bei späterem Login

    @Test
    void aNewSimpleCloudSessionOnALaterLoginCreatesANewSessionRow() {
        CloudSession first = cloud.login(player, "Builder", START);
        SessionService dev = server("dev");
        dev.handleJoin(player, "Builder", START.plusSeconds(1));
        assertEquals(first.id(), resolved(dev, player).sessionKey());

        clock.advance(Duration.ofMinutes(10));
        dev.handleQuit(player, "Builder", clock.instant(), VisitEndReason.QUIT);
        cloud.logout(player, clock.instant());

        clock.advance(Duration.ofHours(2));
        CloudSession second = cloud.login(player, "Builder", clock.instant());
        dev.handleJoin(player, "Builder", clock.instant().plusSeconds(1));

        assertEquals(second.id(), resolved(dev, player).sessionKey());
        assertEquals(2, store.allSessions().size());
        assertNotNull(visitsOf(first.id()).getFirst().leftAt());
        assertNull(visitsOf(second.id()).getFirst().leftAt());
        assertEquals(second.id(), shared.cachedSession(player).orElseThrow().sessionKey());
    }

    // ------------------------------------------------------------------ (3) Serverwechsel

    @Test
    void serverSwitchKeepsTheSessionKeyAndRecordsTwoVisits() {
        CloudSession cloudSession = cloud.login(player, "Builder", START);
        SessionService dev = server("dev");
        SessionService build = server("build");

        dev.handleJoin(player, "Builder", START.plusSeconds(1));
        clock.advance(Duration.ofMinutes(5));
        dev.handleQuit(player, "Builder", clock.instant(), VisitEndReason.QUIT);
        build.handleJoin(player, "Builder", clock.instant());

        assertEquals(cloudSession.id(), resolved(dev, player).sessionKey(), "nach dem Verlassen weiter abrufbar");
        assertEquals(cloudSession.id(), resolved(build, player).sessionKey());
        assertEquals(1, store.allSessions().size());
        List<VisitRow> visits = visitsOf(cloudSession.id());
        assertEquals(2, visits.size());
        assertEquals(Set.of("dev", "build"), visits.stream().map(VisitRow::serverName).collect(Collectors.toSet()));
        VisitRow devVisit = visits.stream().filter(visit -> visit.serverName().equals("dev")).findFirst().orElseThrow();
        assertEquals(clock.instant(), devVisit.leftAt());
        assertEquals(VisitEndReason.QUIT, devVisit.endReason());
        assertEquals(0, dev.stats().localPlayers());
        assertEquals(1, build.stats().localPlayers());
    }

    @Test
    void serverSwitchWithoutSimpleCloudReusesTheSharedProvisionalSession() {
        cloud.setFailing(true);
        SessionService dev = server("dev");
        SessionService build = server("build");

        dev.handleJoin(player, "Builder", START);
        NetworkSession provisional = resolved(dev, player);
        assertTrue(provisional.provisional());

        clock.advance(Duration.ofMinutes(5));
        dev.handleQuit(player, "Builder", clock.instant(), VisitEndReason.QUIT);
        clock.advance(Duration.ofSeconds(2));
        build.handleJoin(player, "Builder", clock.instant());

        assertEquals(provisional.sessionKey(), resolved(build, player).sessionKey());
        assertEquals(1, store.allSessions().size());
        assertEquals(2, visitsOf(provisional.sessionKey()).size());
        assertEquals(1, build.stats().provisionalSessions());
    }

    @Test
    void reconnectAfterTheSwitchGraceCreatesANewProvisionalSession() {
        cloud.setFailing(true);
        SessionService dev = server("dev");
        SessionService build = server("build");

        dev.handleJoin(player, "Builder", START);
        NetworkSession first = resolved(dev, player);
        clock.advance(Duration.ofMinutes(5));
        dev.handleQuit(player, "Builder", clock.instant(), VisitEndReason.QUIT);

        clock.advance(Duration.ofSeconds(45)); // länger als resilience.switch-grace-seconds (30)
        build.handleJoin(player, "Builder", clock.instant());

        NetworkSession second = resolved(build, player);
        assertTrue(second.provisional());
        assertNotEquals(first.sessionKey(), second.sessionKey());
        assertEquals(2, store.allSessions().size());
    }

    // ------------------------------------------------------------------ (7) Netzwerk-Logout

    @Test
    void networkDisconnectEventClosesTheSessionOnce() {
        CloudSession cloudSession = cloud.login(player, "Builder", START);
        SessionService dev = server("dev");
        dev.handleJoin(player, "Builder", START.plusSeconds(1));
        clock.advance(Duration.ofMinutes(10));
        dev.handleQuit(player, "Builder", clock.instant(), VisitEndReason.QUIT);
        CloudSession closed = cloud.logout(player, clock.instant());

        dev.onNetworkDisconnect(player, clock.instant(), closed.durationSeconds());
        dev.onNetworkDisconnect(player, clock.instant(), closed.durationSeconds()); // doppelt zugestellt

        SessionRow row = store.session(cloudSession.id()).orElseThrow();
        assertEquals(SessionStatus.CLOSED, row.status());
        assertEquals(closed.logoutTime(), row.logoutTime());
        assertEquals(closed.durationSeconds(), row.durationSeconds());
        List<SharedEvent> events = shared.published(SharedEvent.SESSION_CLOSED);
        assertEquals(1, events.size());
        assertEquals(cloudSession.id(), events.getFirst().data().get("sessionKey"));
        assertEquals(player.toString(), events.getFirst().data().get("player"));
        assertTrue(shared.cachedSession(player).isEmpty());
        assertEquals(cloudSession.id(), dev.current(player).orElseThrow().sessionKey(),
                "nach dem Logout weiter abrufbar (Aufnahmen werden danach finalisiert)");
    }

    @Test
    void networkDisconnectEventWithoutSimpleCloudClosesTheCachedSessionWithTheEventTime() {
        cloud.login(player, "Builder", START);
        SessionService dev = server("dev");
        dev.handleJoin(player, "Builder", START.plusSeconds(1));
        String key = resolved(dev, player).sessionKey();
        clock.advance(Duration.ofMinutes(10));
        dev.handleQuit(player, "Builder", clock.instant(), VisitEndReason.QUIT);
        cloud.setFailing(true);

        dev.onNetworkDisconnect(player, clock.instant(), 600);

        SessionRow row = store.session(key).orElseThrow();
        assertEquals(SessionStatus.CLOSED, row.status());
        assertEquals(clock.instant(), row.logoutTime());
        assertEquals(600L, row.durationSeconds());
        assertTrue(shared.cachedSession(player).isEmpty());
    }

    @Test
    void disconnectEventIsCheckedAgainWhileSimpleCloudStillShowsTheSessionAsActive() {
        CloudSession cloudSession = cloud.login(player, "Builder", START);
        SessionService dev = server("dev");
        dev.handleJoin(player, "Builder", START.plusSeconds(1));
        clock.advance(Duration.ofMinutes(10));
        dev.handleQuit(player, "Builder", clock.instant(), VisitEndReason.QUIT);
        Instant logout = clock.instant();

        dev.onNetworkDisconnect(player, logout, 600); // REST kennt den Logout noch nicht
        assertEquals(SessionStatus.ACTIVE, store.session(cloudSession.id()).orElseThrow().status());

        cloud.logout(player, logout);
        scheduler.advance(SessionService.DISCONNECT_RECHECK);

        SessionRow row = store.session(cloudSession.id()).orElseThrow();
        assertEquals(SessionStatus.CLOSED, row.status());
        assertEquals(logout, row.logoutTime());
    }

    @Test
    void leaderIgnoresDisconnectEventsOfPlayersNeverResolvedOnABuildReplayServer() {
        SessionService dev = server("dev");
        dev.reconcileNow().join();
        assertTrue(dev.stats().reconcilerLeader());
        int callsBefore = cloud.totalCalls();

        dev.onNetworkDisconnect(UUID.randomUUID(), START, 120);

        assertEquals(callsBefore, cloud.totalCalls());
        assertTrue(shared.published(SharedEvent.SESSION_CLOSED).isEmpty());
    }

    @Test
    void joinWithoutPreviousQuitEndsTheOpenVisit() {
        CloudSession cloudSession = cloud.login(player, "Builder", START);
        SessionService dev = server("dev");
        dev.handleJoin(player, "Builder", START);
        Instant rejoin = START.plusSeconds(90);

        dev.handleJoin(player, "Builder", rejoin); // Quit-Event ging verloren

        List<VisitRow> visits = visitsOf(cloudSession.id());
        assertEquals(2, visits.size());
        VisitRow first = visits.stream().filter(visit -> visit.joinedAt().equals(START)).findFirst().orElseThrow();
        assertEquals(rejoin, first.leftAt());
        assertEquals(1, dev.stats().localPlayers());
    }

    @Test
    void reconcilerClosesTheSessionOfAPlayerWhoLeftTheNetwork() {
        CloudSession cloudSession = cloud.login(player, "Builder", START);
        SessionService dev = server("dev");
        dev.handleJoin(player, "Builder", START.plusSeconds(1));
        dev.reconcileNow().join(); // Leader merkt sich die Online-Liste
        assertTrue(dev.stats().reconcilerLeader());
        assertEquals(SessionStatus.ACTIVE, store.session(cloudSession.id()).orElseThrow().status());

        clock.advance(Duration.ofMinutes(30));
        dev.handleQuit(player, "Builder", clock.instant(), VisitEndReason.QUIT);
        CloudSession closed = cloud.logout(player, clock.instant());
        int listCallsBefore = cloud.listCalls();
        dev.reconcileNow().join();

        SessionRow row = store.session(cloudSession.id()).orElseThrow();
        assertEquals(SessionStatus.CLOSED, row.status());
        assertEquals(closed.logoutTime(), row.logoutTime());
        assertEquals(closed.durationSeconds(), row.durationSeconds());
        assertEquals(1, cloud.listCalls() - listCallsBefore, "ein listSessions(uuid, 1, 0) für den Diff");
        assertEquals(1, shared.published(SharedEvent.SESSION_CLOSED).size());
        assertTrue(shared.cachedSession(player).isEmpty());
    }

    @Test
    void newLeaderClosesActiveDatabaseSessionsOfOfflinePlayers() {
        CloudSession cloudSession = cloud.login(player, "Builder", START);
        SessionService dev = server("dev");
        dev.handleJoin(player, "Builder", START.plusSeconds(1));
        clock.advance(Duration.ofMinutes(30));
        dev.handleQuit(player, "Builder", clock.instant(), VisitEndReason.QUIT);
        CloudSession closed = cloud.logout(player, clock.instant());

        SessionService build = server("build"); // ohne vorherigen Online-Stand
        build.reconcileNow().join();

        SessionRow row = store.session(cloudSession.id()).orElseThrow();
        assertEquals(SessionStatus.CLOSED, row.status());
        assertEquals(closed.logoutTime(), row.logoutTime());
        assertTrue(build.stats().reconcilerLeader());
    }

    @Test
    void reconcilerKeepsSessionsOfPlayersThatAreStillOnline() {
        CloudSession cloudSession = cloud.login(player, "Builder", START);
        SessionService dev = server("dev");
        dev.handleJoin(player, "Builder", START.plusSeconds(1));
        dev.reconcileNow().join();
        clock.advance(Duration.ofMinutes(5));
        dev.reconcileNow().join();

        assertEquals(SessionStatus.ACTIVE, store.session(cloudSession.id()).orElseThrow().status());
        assertTrue(shared.published(SharedEvent.SESSION_CLOSED).isEmpty());
        assertEquals(cloudSession.id(), shared.cachedSession(player).orElseThrow().sessionKey());
    }

    @Test
    void reconcilerClosesAnOldSessionWhenThePlayerIsOnlineWithANewOne() {
        CloudSession old = cloud.login(player, "Builder", START);
        SessionService dev = server("dev");
        dev.handleJoin(player, "Builder", START.plusSeconds(1));
        clock.advance(Duration.ofMinutes(20));
        dev.handleQuit(player, "Builder", clock.instant(), VisitEndReason.QUIT);
        CloudSession closedOld = cloud.logout(player, clock.instant());
        clock.advance(Duration.ofMinutes(1));
        cloud.login(player, "Builder", clock.instant()); // neue Verbindung, Disconnect-Event ging verloren

        dev.reconcileNow().join();

        SessionRow row = store.session(old.id()).orElseThrow();
        assertEquals(SessionStatus.CLOSED, row.status());
        assertEquals(closedOld.logoutTime(), row.logoutTime());
    }

    @Test
    void reconcilerEvictsCachedSessionsWhoseIdDiffersFromTheOnlineList() {
        cloud.login(player, "Builder", START);
        shared.cacheSession(player, new CachedSession("veraltet", "veraltet", SessionSource.SIMPLECLOUD,
                START.minus(Duration.ofDays(1)), "andere-instanz", START.minus(Duration.ofDays(1))), Duration.ofHours(6));
        SessionService dev = server("dev");

        dev.reconcileNow().join();

        assertTrue(shared.cachedSession(player).isEmpty());
    }

    @Test
    void reconcilerUsesAtMostTwentySimpleCloudCallsPerRun() {
        for (int i = 0; i < 30; i++) {
            UUID offline = UUID.randomUUID();
            Instant login = START.minus(Duration.ofHours(i + 1));
            CloudSession closed = cloud.addClosed(offline, login, login.plus(Duration.ofMinutes(20)));
            store.putSession(closed.id(), closed.id(), offline, SessionSource.SIMPLECLOUD, SessionStatus.ACTIVE,
                    login, null);
        }
        SessionService dev = server("dev");

        dev.reconcileNow().join();
        long stillActive = store.allSessions().stream().filter(row -> row.status() == SessionStatus.ACTIVE).count();
        assertEquals(SessionReconciler.MAX_CALLS_PER_RUN, cloud.totalCalls());
        assertEquals(30 - (SessionReconciler.MAX_CALLS_PER_RUN - 1), stillActive, "1 Aufruf für die Online-Liste");

        dev.reconcileNow().join();
        assertEquals(0, store.allSessions().stream().filter(row -> row.status() == SessionStatus.ACTIVE).count());
    }

    @Test
    void reconcilerSkipsQuietlyWhileTheCircuitIsOpen() {
        cloud.setCircuitOpen(true);
        store.putSession("s-1", "s-1", player, SessionSource.SIMPLECLOUD, SessionStatus.ACTIVE, START, null);
        SessionService dev = server("dev");

        dev.reconcileNow().join();

        assertEquals(0, cloud.totalCalls());
        assertEquals(SessionStatus.ACTIVE, store.session("s-1").orElseThrow().status());
    }

    // ------------------------------------------------------------------ (9) gemeinsamer Zustand gestört

    @Test
    void degradedSharedStateStillResolvesViaSimpleCloud() {
        CloudSession cloudSession = cloud.login(player, "Builder", START);
        // Redis weg: jede Instanz hat nur ihren lokalen Rückfallzustand
        SessionService dev = server("dev", TestConfigs.defaults(), new FakeSharedState(clock, false));
        SessionService build = server("build", TestConfigs.defaults(), new FakeSharedState(clock, false));

        dev.handleJoin(player, "Builder", START.plusSeconds(1));
        clock.advance(Duration.ofMinutes(3));
        dev.handleQuit(player, "Builder", clock.instant(), VisitEndReason.QUIT);
        build.handleJoin(player, "Builder", clock.instant());

        assertEquals(cloudSession.id(), resolved(dev, player).sessionKey());
        assertEquals(cloudSession.id(), resolved(build, player).sessionKey());
        assertEquals(1, store.allSessions().size());
        assertEquals(2, visitsOf(cloudSession.id()).size());
    }

    @Test
    void brokenSharedStateStillResolvesViaSimpleCloudAndFallsBackToProvisional() {
        CloudSession cloudSession = cloud.login(player, "Builder", START);
        shared.setBroken(true);
        SessionService dev = server("dev");

        dev.handleJoin(player, "Builder", START.plusSeconds(1));
        assertEquals(cloudSession.id(), resolved(dev, player).sessionKey());

        UUID other = UUID.randomUUID();
        cloud.setFailing(true);
        dev.handleJoin(other, "Other", START.plusSeconds(2));
        assertTrue(resolved(dev, other).provisional(), "ohne Sperre trotzdem vorläufig aufgelöst");
    }

    // ------------------------------------------------------------------ (11) vorläufig + späterer Abgleich

    @Test
    void simpleCloudOutageCreatesAProvisionalSessionThatTheReconcilerMergesLater() {
        CloudSession cloudSession = cloud.login(player, "Builder", START.minusSeconds(2));
        cloud.setFailing(true);
        SessionService dev = server("dev");

        dev.handleJoin(player, "Builder", START);
        NetworkSession provisional = resolved(dev, player);
        assertTrue(provisional.provisional());
        assertEquals(SessionSource.PROVISIONAL, store.session(provisional.sessionKey()).orElseThrow().source());
        assertEquals(1, dev.stats().provisionalSessions());
        assertTrue(dev.stats().cloudLookupFailures() > 0);

        clock.advance(Duration.ofMinutes(20));
        dev.handleQuit(player, "Builder", clock.instant(), VisitEndReason.QUIT);
        CloudSession closed = cloud.logout(player, clock.instant());
        cloud.setFailing(false);

        dev.reconcileNow().join();

        assertEquals(1, store.merges.get());
        assertEquals(SessionStatus.MERGED, store.session(provisional.sessionKey()).orElseThrow().status());
        SessionRow merged = store.session(cloudSession.id()).orElseThrow();
        assertEquals(SessionSource.SIMPLECLOUD, merged.source());
        assertEquals(SessionStatus.CLOSED, merged.status());
        assertEquals(closed.logoutTime(), merged.logoutTime());
        assertEquals(cloudSession.loginTime(), merged.loginTime());
        assertEquals(merged.id(), store.allVisits().getFirst().sessionId(), "Aufenthalt umgehängt");
        assertEquals(cloudSession.id(), dev.current(player).orElseThrow().sessionKey());

        dev.reconcileNow().join();
        assertEquals(1, store.merges.get(), "zusammengeführte Sitzungen werden nicht erneut bearbeitet");
    }

    @Test
    void provisionalSessionOfAPresentPlayerIsUpgradedWhenSimpleCloudRecovers() {
        CloudSession cloudSession = cloud.login(player, "Builder", START.minusSeconds(2));
        cloud.setFailing(true);
        shared.forceLeader(SessionService.RECONCILER_ROLE, "andere-instanz", Duration.ofDays(1));
        SessionService dev = server("dev");
        dev.start();

        dev.handleJoin(player, "Builder", START);
        NetworkSession provisional = resolved(dev, player);
        assertTrue(provisional.provisional());

        cloud.setFailing(false);
        scheduler.advance(Duration.ofSeconds(16)); // Präsenz-Auffrischung (15 s) stuft hoch

        NetworkSession upgraded = dev.current(player).orElseThrow();
        assertEquals(cloudSession.id(), upgraded.sessionKey());
        assertEquals(cloudSession.id(), dev.resolve(player).join().sessionKey());
        assertEquals(SessionStatus.MERGED, store.session(provisional.sessionKey()).orElseThrow().status());
        assertEquals(cloudSession.id(), shared.cachedSession(player).orElseThrow().sessionKey());
        assertFalse(dev.stats().reconcilerLeader());
        assertTrue(shared.presence(player).orElseThrow().online(), "Präsenz wird aufgefrischt");
    }

    @Test
    void withoutProvisionalSessionsResolutionFailsUntilSimpleCloudIsBack() {
        PluginConfig config = TestConfigs.config(Map.of("resilience.provisional-sessions", false));
        CloudSession cloudSession = cloud.login(player, "Builder", START.minusSeconds(2));
        cloud.setFailing(true);
        shared.forceLeader(SessionService.RECONCILER_ROLE, "andere-instanz", Duration.ofDays(1));
        SessionService dev = server("dev", config, shared);
        dev.start();

        dev.handleJoin(player, "Builder", START);

        assertTrue(dev.resolve(player).isCompletedExceptionally());
        assertTrue(dev.current(player).isEmpty());
        assertTrue(store.allSessions().isEmpty());
        assertTrue(store.allVisits().isEmpty());

        cloud.setFailing(false);
        scheduler.advance(Duration.ofSeconds(16));

        assertEquals(cloudSession.id(), dev.resolve(player).join().sessionKey());
        assertEquals(1, visitsOf(cloudSession.id()).size());
    }

    // ------------------------------------------------------------------ (12) gleichzeitige Auflösung

    @Test
    void twoServersResolvingConcurrentlyAgreeOnOneSessionKey() throws Exception {
        TestTasks threaded = TestTasks.threaded();
        java.time.Clock real = java.time.Clock.systemUTC();
        FakeSharedState realShared = new FakeSharedState(real, true);
        FakeReplayStore realStore = new FakeReplayStore(real);
        FakeSimpleCloudClient down = new FakeSimpleCloudClient();
        down.setFailing(true);
        SessionService dev = new SessionService(TestConfigs.defaults(), TestConfigs.server("dev"), threaded, real,
                down, realStore, realShared, TestConfigs.quietLogger("dev"));
        SessionService build = new SessionService(TestConfigs.defaults(), TestConfigs.server("build"), threaded,
                real, down, realStore, realShared, TestConfigs.quietLogger("build"));
        try {
            int players = 15;
            for (int i = 0; i < players; i++) {
                UUID uuid = UUID.randomUUID();
                java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
                Instant joinedAt = real.instant();
                Thread first = Thread.ofPlatform().start(() -> {
                    await(barrier);
                    dev.handleJoin(uuid, "P", joinedAt);
                });
                Thread second = Thread.ofPlatform().start(() -> {
                    await(barrier);
                    build.handleJoin(uuid, "P", joinedAt);
                });
                first.join();
                second.join();
                NetworkSession a = dev.resolve(uuid).get(20, java.util.concurrent.TimeUnit.SECONDS);
                NetworkSession b = build.resolve(uuid).get(20, java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(a.provisional());
                assertEquals(a.sessionKey(), b.sessionKey(), "Durchlauf " + i);
            }
            realStore.flush().join();
            assertEquals(players, realStore.allSessions().size());
            assertEquals(players * 2, realStore.allVisits().size());
        } finally {
            dev.close();
            build.close();
            threaded.shutdown();
        }
    }

    private static void await(java.util.concurrent.CyclicBarrier barrier) {
        try {
            barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ übrige Fälle

    @Test
    void currentAndResolveAreEmptyForPlayersNeverSeenHere() {
        SessionService dev = server("dev");

        assertTrue(dev.current(player).isEmpty());
        CompletableFuture<NetworkSession> future = dev.resolve(player);
        assertTrue(future.isCompletedExceptionally());
        assertEquals(0, cloud.totalCalls());
    }

    @Test
    void sessionStaysResolvableForTenMinutesAfterQuit() {
        CloudSession cloudSession = cloud.login(player, "Builder", START);
        SessionService dev = server("dev");
        dev.handleJoin(player, "Builder", START);
        dev.handleQuit(player, "Builder", START.plusSeconds(60), VisitEndReason.QUIT);

        clock.advance(Duration.ofMinutes(9));
        assertEquals(cloudSession.id(), dev.current(player).orElseThrow().sessionKey());
        assertEquals(cloudSession.id(), dev.resolve(player).join().sessionKey());

        clock.advance(Duration.ofMinutes(3));
        assertTrue(dev.current(player).isEmpty());
        assertTrue(dev.resolve(player).isCompletedExceptionally());
    }

    @Test
    void quitBeforeTheSessionIsResolvedStillEndsTheVisitAfterItStarts() {
        CloudSession cloudSession = cloud.login(player, "Builder", START);
        cloud.answerCurrentEmpty(2);
        SessionService dev = server("dev");

        dev.handleJoin(player, "Builder", START);
        dev.handleQuit(player, "Builder", START.plusMillis(500), VisitEndReason.QUIT);
        assertTrue(store.allVisits().isEmpty(), "Aufenthalt erst nach der Auflösung");

        scheduler.advance(Duration.ofSeconds(5));

        VisitRow visit = visitsOf(cloudSession.id()).getFirst();
        assertEquals(START.plusMillis(500), visit.leftAt());
        assertFalse(shared.presence(player).orElseThrow().online());
    }

    @Test
    void networkLoginEventEvictsACachedSessionWithADifferentId() {
        SessionService dev = server("dev");
        CachedSession old = new CachedSession("alt", "alt", SessionSource.SIMPLECLOUD, START.minusSeconds(3600),
                "instanz", START.minusSeconds(3600));

        shared.cacheSession(player, old, Duration.ofHours(6));
        dev.onNetworkLogin(player, "alt", START);
        assertEquals(Optional.of(old), shared.cachedSession(player), "gleiche ID bleibt");

        dev.onNetworkLogin(player, "neu", START);
        assertTrue(shared.cachedSession(player).isEmpty());
    }

    @Test
    void closeFailsPendingResolutionsAndReleasesLeadership() {
        cloud.login(player, "Builder", START);
        cloud.answerCurrentEmpty(100);
        SessionService dev = server("dev");
        dev.reconcileNow().join();
        assertTrue(dev.stats().reconcilerLeader());
        dev.handleJoin(player, "Builder", START);
        CompletableFuture<NetworkSession> pending = dev.resolve(player);

        dev.close();

        assertTrue(pending.isCompletedExceptionally());
        assertFalse(dev.stats().reconcilerLeader());
        assertTrue(shared.tryAcquireOrRenewLeadership(SessionService.RECONCILER_ROLE, "andere", Duration.ofSeconds(30)));
    }
}
