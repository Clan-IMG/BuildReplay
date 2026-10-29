package net.techniqstone.buildReplay.state.redis;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import net.techniqstone.buildReplay.state.InstanceInfo;
import net.techniqstone.buildReplay.state.LockToken;
import net.techniqstone.buildReplay.state.SharedEvent;
import net.techniqstone.buildReplay.state.SharedState;
import net.techniqstone.buildReplay.state.SharedStateHealth;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Ausweichen auf den lokalen Zustand bei Redis-Ausfällen und Rückkehr in den Redis-Modus. */
class FailoverSharedStateTest {

    @Test
    @DisplayName("Redis temporarily unavailable")
    void redisTemporarilyUnavailableFallsBackToLocalStateAndReturnsToRedisAfterwards() {
        TestSupport.CapturingLogger logA = new TestSupport.CapturingLogger("failover-a");
        TestSupport.CapturingLogger logB = new TestSupport.CapturingLogger("failover-b");
        try (EmbeddedRedis redis = EmbeddedRedis.start();
             SharedState a = SharedStates.create(TestSupport.settings(redis.port()), "inst-a", Clock.systemUTC(),
                     logA.logger());
             SharedState b = SharedStates.create(TestSupport.settings(redis.port()), "inst-b", Clock.systemUTC(),
                     logB.logger())) {
            List<SharedEvent> onA = new CopyOnWriteArrayList<>();
            List<SharedEvent> onB = new CopyOnWriteArrayList<>();
            a.subscribe(onA::add);
            b.subscribe(onB::add);
            TestSupport.waitUntil("beide abonniert", Duration.ofSeconds(5), () -> subscribed(a) && subscribed(b));
            a.publish(TestSupport.event(SharedEvent.SEGMENT_SAVED, "inst-a", "vorher"));
            TestSupport.waitUntil("B empfängt vor dem Ausfall", Duration.ofSeconds(5), () -> onB.size() == 1);
            LockToken heldBefore = a.tryLock("vor-dem-ausfall", Duration.ofSeconds(60)).orElseThrow();
            assertTrue(heldBefore.distributed());
            assertTrue(a.isDistributed());

            int subscriptionsA = ((FailoverSharedState) a).subscriptionCount();
            int subscriptionsB = ((FailoverSharedState) b).subscriptionCount();

            // ---- Ausfall
            redis.stopServer();
            UUID player = UUID.randomUUID();
            assertDoesNotThrow(() -> exerciseEveryOperation(a, player));
            assertFalse(a.isDistributed());
            SharedStateHealth health = a.health();
            assertTrue(health.enabled());
            assertFalse(health.connected());
            assertTrue(health.usingLocalFallback());
            assertNotNull(health.lastError());
            assertNotNull(health.lastSuccess(), "letzter Erfolg vor dem Ausfall bleibt sichtbar");

            long start = System.nanoTime();
            for (int i = 0; i < 200; i++) {
                a.cachedSession(player);
                a.presence(player);
            }
            assertTrue(System.nanoTime() - start < Duration.ofSeconds(1).toNanos(),
                    "im Ausweichmodus wird nicht auf Redis gewartet");

            a.cacheSession(player, TestSupport.session("im-ausfall"), Duration.ofMinutes(5));
            assertEquals("im-ausfall", a.cachedSession(player).orElseThrow().sessionKey());
            a.putRecordingState(player, TestSupport.recording("brraw-x"), Duration.ofMinutes(5));
            assertEquals(Optional.of(TestSupport.recording("brraw-x")), a.recordingState(player));

            LockToken local = a.tryLock("im-ausfall", Duration.ofSeconds(30)).orElseThrow();
            assertFalse(local.distributed());
            assertEquals(Optional.empty(), a.tryLock("im-ausfall", Duration.ofSeconds(30)));
            assertEquals(Optional.empty(), a.tryLock("vor-dem-ausfall", Duration.ofSeconds(30)),
                    "eine vor dem Ausfall gehaltene Redis-Sperre wird nicht lokal erneut vergeben");
            assertTrue(a.unlock(local));
            LockToken spanning = a.tryLock("ueber-den-ausfall", Duration.ofSeconds(60)).orElseThrow();

            assertTrue(a.tryAcquireOrRenewLeadership("online-sync", "inst-a", Duration.ofSeconds(30)));
            assertFalse(a.tryAcquireOrRenewLeadership("online-sync", "inst-x", Duration.ofSeconds(30)));
            a.putHandoff(player, TestSupport.handoff(5), Duration.ofSeconds(30));
            assertEquals(Optional.of(TestSupport.handoff(5)).map(h -> h.segmentId()),
                    a.takeHandoff(player).map(h -> h.segmentId()));
            a.heartbeat(TestSupport.instance("inst-a"), Duration.ofSeconds(45));
            assertEquals(List.of("inst-a"), a.liveInstances().stream().map(InstanceInfo::instanceId).toList());

            int beforeLocal = onA.size();
            a.publish(TestSupport.event(SharedEvent.SESSION_CLOSED, "inst-a", "lokal"));
            TestSupport.waitUntil("lokale Zustellung", Duration.ofSeconds(5),
                    () -> onA.stream().anyMatch(e -> e.data().get("value").equals("lokal")));
            assertTrue(onA.size() > beforeLocal);
            assertDoesNotThrow(() -> b.cachedSession(player));
            TestSupport.waitUntil("B bemerkt den Ausfall", Duration.ofSeconds(10), () -> !b.isDistributed());

            // ---- Redis kommt zurück (gleicher Port, ohne Daten)
            redis.startServer();
            TestSupport.waitUntil("A wieder verteilt", Duration.ofSeconds(15), a::isDistributed);
            TestSupport.waitUntil("B wieder verteilt", Duration.ofSeconds(15), b::isDistributed);
            SharedStateHealth recovered = a.health();
            assertTrue(recovered.connected());
            assertFalse(recovered.usingLocalFallback());

            assertEquals(Optional.empty(), a.cachedSession(player), "lokaler Ausfallzustand wurde verworfen");
            a.cacheSession(player, TestSupport.session("danach"), Duration.ofMinutes(5));
            assertEquals("danach", b.cachedSession(player).orElseThrow().sessionKey());
            assertTrue(redis.exists("buildreplay:session:" + player));
            LockToken distributedAgain = a.tryLock("im-ausfall", Duration.ofSeconds(30)).orElseThrow();
            assertTrue(distributedAgain.distributed());
            assertEquals(Optional.empty(), b.tryLock("im-ausfall", Duration.ofSeconds(30)));
            assertEquals(Optional.empty(), a.tryLock("ueber-den-ausfall", Duration.ofSeconds(30)),
                    "die im Ausfall lokal vergebene Sperre schließt Threads dieser Instanz weiter aus");
            LockToken lockOnB = b.tryLock("ueber-den-ausfall", Duration.ofSeconds(30)).orElseThrow();
            assertTrue(a.unlock(spanning));
            assertEquals(Optional.empty(), a.tryLock("ueber-den-ausfall", Duration.ofSeconds(30)),
                    "nach lokaler Freigabe entscheidet wieder Redis");
            assertTrue(b.unlock(lockOnB));

            TestSupport.waitUntil("beide neu abonniert", Duration.ofSeconds(15),
                    () -> ((FailoverSharedState) a).subscriptionCount() > subscriptionsA && subscribed(a)
                            && ((FailoverSharedState) b).subscriptionCount() > subscriptionsB && subscribed(b));
            a.publish(TestSupport.event(SharedEvent.SEGMENT_DELETED, "inst-a", "nachher"));
            TestSupport.waitUntil("B empfängt nach dem Ausfall", Duration.ofSeconds(5),
                    () -> onB.stream().anyMatch(e -> e.data().get("value").equals("nachher")));
            assertTrue(onB.stream().noneMatch(e -> e.data().get("value").equals("lokal")),
                    "lokale Ausfall-Events erreichen andere Instanzen nicht");

            assertEquals(1, logA.count(Level.WARNING, "Redis nicht erreichbar"), logA.all());
            assertEquals(1, logA.count(Level.INFO, "Redis wieder erreichbar"), logA.all());
            assertEquals(0, logA.count(Level.SEVERE, ""), logA.all());
        }
    }

    @Test
    void startsWithoutBlockingWhenRedisIsUnreachableAndSwitchesToRedisOnceItIsUp() {
        TestSupport.CapturingLogger log = new TestSupport.CapturingLogger("unreachable");
        int port = TestSupport.freePort();
        long start = System.nanoTime();
        try (SharedState state = SharedStates.create(TestSupport.settings(port), "inst-a", Clock.systemUTC(),
                log.logger())) {
            assertTrue(System.nanoTime() - start < Duration.ofMillis(500).toNanos(), "create blockiert nicht");
            state.subscribe(event -> { });
            TestSupport.waitUntil("Ausfall erkannt", Duration.ofSeconds(5), () -> !state.isDistributed());
            UUID player = UUID.randomUUID();
            assertDoesNotThrow(() -> exerciseEveryOperation(state, player));
            SharedStateHealth health = state.health();
            assertNull(health.lastSuccess());
            assertNotNull(health.lastError());

            try (EmbeddedRedis redis = EmbeddedRedis.start(port)) {
                TestSupport.waitUntil("verbunden", Duration.ofSeconds(15), state::isDistributed);
                state.cacheSession(player, TestSupport.session("s"), Duration.ofMinutes(1));
                assertTrue(redis.exists("buildreplay:session:" + player));
                TestSupport.waitUntil("abonniert", Duration.ofSeconds(15), () -> subscribed(state));
                assertNotNull(state.health().lastSuccess());
            }
            assertEquals(1, log.count(Level.WARNING, "Redis nicht erreichbar"), log.all());
            assertEquals(1, log.count(Level.WARNING, "Pub/Sub nicht verbunden"), "Abo-Fehler einmal statt je Versuch");
        }
    }

    @Test
    void disabledRedisYieldsAPurelyLocalState() {
        try (SharedState state = SharedStates.create(TestSupport.disabled(), "inst-a", Clock.systemUTC(),
                new TestSupport.CapturingLogger("disabled").logger())) {
            assertInstanceOf(LocalSharedState.class, state);
            assertFalse(state.isDistributed());
            assertEquals(new SharedStateHealth(false, false, true, null, null), state.health());
            UUID player = UUID.randomUUID();
            state.cacheSession(player, TestSupport.session("s"), Duration.ofMinutes(1));
            assertEquals(Optional.of(TestSupport.session("s")), state.cachedSession(player));
        }
    }

    @Test
    void passwordProtectedRedisIsUsedWithTheConfiguredPasswordWhichIsNeverLogged() {
        String password = "Pw" + UUID.randomUUID().toString().replace("-", "");
        String wrongPassword = "Xx" + UUID.randomUUID().toString().replace("-", "");
        TestSupport.CapturingLogger goodLog = new TestSupport.CapturingLogger("auth-good");
        TestSupport.CapturingLogger badLog = new TestSupport.CapturingLogger("auth-bad");
        try (EmbeddedRedis redis = EmbeddedRedis.startWithPassword(password);
             SharedState good = SharedStates.create(TestSupport.settings(redis.port(), password, "buildreplay:"),
                     "inst-a", Clock.systemUTC(), goodLog.logger());
             SharedState bad = SharedStates.create(TestSupport.settings(redis.port(), wrongPassword, "buildreplay:"),
                     "inst-b", Clock.systemUTC(), badLog.logger())) {
            UUID player = UUID.randomUUID();
            good.cacheSession(player, TestSupport.session("s"), Duration.ofMinutes(1));
            assertTrue(good.isDistributed());
            assertTrue(redis.exists("buildreplay:session:" + player));

            bad.subscribe(event -> { });
            assertDoesNotThrow(() -> bad.cacheSession(player, TestSupport.session("lokal"), Duration.ofMinutes(1)));
            TestSupport.waitUntil("falsches Passwort führt zum Ausweichen", Duration.ofSeconds(5),
                    () -> !bad.isDistributed());
            assertEquals("lokal", bad.cachedSession(player).orElseThrow().sessionKey());
            assertEquals(1, badLog.count(Level.WARNING, "Redis-Anmeldung abgelehnt"), badLog.all());
            TestSupport.waitUntil("Abo-Fehler protokolliert", Duration.ofSeconds(5),
                    () -> badLog.all().contains("Pub/Sub"));

            String everything = goodLog.all() + badLog.all() + good.health() + bad.health();
            assertFalse(everything.contains(password), everything);
            assertFalse(everything.contains(wrongPassword), everything);
            assertNotNull(bad.health().lastError());
        }
    }

    @Test
    void aRedisErrorOnASingleCallFallsBackOnlyUntilTheNextHealthCheck() {
        TestSupport.CapturingLogger log = new TestSupport.CapturingLogger("wrongtype");
        try (EmbeddedRedis redis = EmbeddedRedis.start()) {
            RedisSharedState remote = new RedisSharedState(TestSupport.settings(redis.port()), "inst-a",
                    Clock.systemUTC(), log.logger());
            try (FailoverSharedState state = new FailoverSharedState(remote,
                    new LocalSharedState(Clock.systemUTC(), log.logger()), Clock.systemUTC(), log.logger(),
                    Duration.ofMillis(200))) {
                state.start();
                UUID player = UUID.randomUUID();
                redis.withJedis(j -> j.hset("buildreplay:handoff:" + player, "falscher", "typ"));

                assertEquals(Optional.empty(), assertDoesNotThrow(() -> state.takeHandoff(player)));
                assertTrue(state.health().lastError().contains("WRONGTYPE"), state.health().lastError());
                assertEquals(1, log.count(Level.WARNING, "Redis-Befehl fehlgeschlagen"), log.all());
                TestSupport.waitUntil("nächster PING stellt Redis wieder her", Duration.ofSeconds(5),
                        () -> log.count(Level.INFO, "Redis wieder erreichbar") == 1);
                assertTrue(state.isDistributed());
            }
        }
    }

    @Test
    void operationsAfterCloseDoNotThrow() {
        try (EmbeddedRedis redis = EmbeddedRedis.start()) {
            SharedState state = SharedStates.create(TestSupport.settings(redis.port()), "inst-a", Clock.systemUTC(),
                    new TestSupport.CapturingLogger("closed").logger());
            state.subscribe(event -> { });
            state.close();
            state.close();
            assertFalse(state.isDistributed());
            assertDoesNotThrow(() -> exerciseEveryOperation(state, UUID.randomUUID()));
        }
    }

    /** Ruft jede Methode von {@link SharedState} einmal auf. */
    private static void exerciseEveryOperation(SharedState state, UUID player) {
        state.isDistributed();
        state.health();
        state.cacheSession(player, TestSupport.session("x"), Duration.ofSeconds(30));
        state.cachedSession(player);
        state.evictSession(player);
        state.markPresent(player, TestSupport.presence("build-1", "inst-a", true), Duration.ofSeconds(30));
        state.markLeft(player, TestSupport.presence("build-1", "inst-a", true), Duration.ofSeconds(30));
        state.presence(player);
        state.putRecordingState(player, TestSupport.recording("brraw-1"), Duration.ofSeconds(30));
        state.recordingState(player);
        state.clearRecordingState(player, "brraw-1");
        state.putActivity(player, TestSupport.activity(true), Duration.ofSeconds(30));
        state.activity(player);
        state.tryLock("probe", Duration.ofSeconds(5)).ifPresent(state::unlock);
        state.tryAcquireOrRenewLeadership("probe", "inst-a", Duration.ofSeconds(5));
        state.releaseLeadership("probe", "inst-a");
        state.heartbeat(TestSupport.instance("inst-a"), Duration.ofSeconds(30));
        state.liveInstances();
        state.putHandoff(player, TestSupport.handoff(1), Duration.ofSeconds(30));
        state.takeHandoff(player);
        state.publish(TestSupport.event(SharedEvent.SEGMENT_SAVED, "inst-a", "probe"));
    }

    private static boolean subscribed(SharedState state) {
        return ((FailoverSharedState) state).subscriptionActive();
    }
}
