package net.techniqstone.buildReplay.state.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import net.techniqstone.buildReplay.state.HandoffRequest;
import net.techniqstone.buildReplay.state.InstanceInfo;
import net.techniqstone.buildReplay.state.LockToken;
import net.techniqstone.buildReplay.state.PresenceInfo;
import net.techniqstone.buildReplay.state.SharedEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.exceptions.JedisException;
import redis.clients.jedis.params.ClientKillParams;
import redis.clients.jedis.args.ClientType;

/** Jede Operation von {@link RedisSharedState} gegen ein echtes (eingebettetes) Redis, inkl. Schlüssellayout. */
class RedisSharedStateTest {

    private static EmbeddedRedis redis;

    private TestSupport.CapturingLogger log;
    private RedisSharedState state;
    private final UUID player = UUID.randomUUID();

    @BeforeAll
    static void startRedis() {
        redis = EmbeddedRedis.start();
    }

    @AfterAll
    static void stopRedis() {
        if (redis != null) {
            redis.close();
        }
    }

    @BeforeEach
    void setUp() {
        redis.flushAll();
        log = new TestSupport.CapturingLogger("redis");
        state = new RedisSharedState(TestSupport.settings(redis.port()), "inst-a", Clock.systemUTC(), log.logger());
    }

    @AfterEach
    void tearDown() {
        state.close();
    }

    @Test
    void cachedSessionIsStoredAsJsonWithTtlAndExpires() {
        state.cacheSession(player, TestSupport.session("s1"), Duration.ofSeconds(30));
        assertEquals(Optional.of(TestSupport.session("s1")), state.cachedSession(player));

        String key = "buildreplay:session:" + player;
        String raw = redis.withJedis(j -> j.get(key));
        assertTrue(raw.contains("\"resolvedAt\":\"2026-10-29T18:00:00Z\""), raw);
        long ttl = redis.withJedis(j -> j.pttl(key));
        assertTrue(ttl > 25_000 && ttl <= 30_000, "PTTL " + ttl);

        state.cacheSession(player, TestSupport.session("s2"), Duration.ofMillis(200));
        TestSupport.waitUntil("Sitzung abgelaufen", Duration.ofSeconds(3), () -> state.cachedSession(player).isEmpty());
    }

    @Test
    void evictSessionDeletesTheKey() {
        state.cacheSession(player, TestSupport.session("s1"), Duration.ofMinutes(5));
        state.evictSession(player);
        assertEquals(Optional.empty(), state.cachedSession(player));
        assertFalse(redis.exists("buildreplay:session:" + player));
    }

    @Test
    void presenceFollowsTheCompareRulesOfMarkLeft() {
        PresenceInfo here = TestSupport.presence("build-1", "inst-a", true);
        state.markPresent(player, here, Duration.ofSeconds(45));
        assertEquals(Optional.of(here), state.presence(player));

        state.markLeft(player, here, Duration.ofSeconds(30));
        assertEquals(Optional.of(TestSupport.presence("build-1", "inst-a", false)), state.presence(player));
        long ttl = redis.withJedis(j -> j.pttl("buildreplay:presence:" + player));
        assertTrue(ttl > 25_000 && ttl <= 30_000, "Karenzzeit " + ttl);

        PresenceInfo target = TestSupport.presence("build-2", "inst-b", true);
        state.markPresent(player, target, Duration.ofSeconds(45));
        state.markLeft(player, here, Duration.ofSeconds(30));
        assertEquals(Optional.of(target), state.presence(player), "neuerer Eintrag eines anderen Servers bleibt");

        UUID fresh = UUID.randomUUID();
        state.markLeft(fresh, here, Duration.ofMillis(200));
        assertFalse(state.presence(fresh).orElseThrow().online());
        TestSupport.waitUntil("Karenz abgelaufen", Duration.ofSeconds(3), () -> state.presence(fresh).isEmpty());
    }

    @Test
    void recordingStateIsAHashWithTtlAndIsClearedOnlyForTheMatchingRawName() {
        state.putRecordingState(player, TestSupport.recording("brraw-1"), Duration.ofMinutes(30));
        assertEquals(Optional.of(TestSupport.recording("brraw-1")), state.recordingState(player));

        String key = "buildreplay:rec:" + player;
        assertEquals("hash", redis.withJedis(j -> j.type(key)));
        Map<String, String> hash = redis.withJedis(j -> j.hgetAll(key));
        assertEquals(Map.of("raw", "brraw-1", "server", "build-1", "instance", "inst-a",
                "since", "2026-10-29T18:00:00Z"), hash);
        assertTrue(redis.withJedis(j -> j.pttl(key)) > 0);

        state.clearRecordingState(player, "brraw-other");
        assertTrue(state.recordingState(player).isPresent());
        state.clearRecordingState(player, "brraw-1");
        assertEquals(Optional.empty(), state.recordingState(player));

        state.putRecordingState(player, TestSupport.recording("brraw-2"), Duration.ofMillis(200));
        TestSupport.waitUntil("Status abgelaufen", Duration.ofSeconds(3),
                () -> state.recordingState(player).isEmpty());
    }

    @Test
    void activityRoundTripsIncludingNullLastRelevantAndExpires() {
        state.putActivity(player, TestSupport.activity(false), Duration.ofSeconds(60));
        assertEquals(Optional.of(TestSupport.activity(false)), state.activity(player));
        state.putActivity(player, TestSupport.activity(true), Duration.ofMillis(200));
        assertEquals(Optional.of(TestSupport.activity(true)), state.activity(player));
        TestSupport.waitUntil("Aktivität abgelaufen", Duration.ofSeconds(3), () -> state.activity(player).isEmpty());
    }

    @Test
    void lockUsesSetNxWithLeaseAndCompareAndDeleteUnlock() {
        LockToken token = state.tryLock("reconcile", Duration.ofSeconds(20)).orElseThrow();
        assertTrue(token.distributed());
        assertEquals(token.token(), redis.withJedis(j -> j.get("buildreplay:lock:reconcile")));
        long ttl = redis.withJedis(j -> j.pttl("buildreplay:lock:reconcile"));
        assertTrue(ttl > 15_000 && ttl <= 20_000, "Lease " + ttl);

        assertEquals(Optional.empty(), state.tryLock("reconcile", Duration.ofSeconds(20)));
        assertFalse(state.unlock(new LockToken("reconcile", "fremd", true)));
        assertEquals(token.token(), redis.withJedis(j -> j.get("buildreplay:lock:reconcile")));

        assertTrue(state.unlock(token));
        assertFalse(state.unlock(token));

        LockToken shortLived = state.tryLock("reconcile", Duration.ofMillis(200)).orElseThrow();
        TestSupport.waitUntil("Lease abgelaufen", Duration.ofSeconds(3),
                () -> state.tryLock("reconcile", Duration.ofSeconds(5)).isPresent());
        assertFalse(state.unlock(shortLived), "abgelaufenes Token gibt die neue Sperre nicht frei");
    }

    @Test
    void leadershipIsAcquiredRenewedAndReleased() {
        String key = "buildreplay:leader:online-sync";
        assertTrue(state.tryAcquireOrRenewLeadership("online-sync", "inst-a", Duration.ofSeconds(5)));
        assertEquals("inst-a", redis.withJedis(j -> j.get(key)));
        assertFalse(state.tryAcquireOrRenewLeadership("online-sync", "inst-b", Duration.ofSeconds(5)));

        assertTrue(state.tryAcquireOrRenewLeadership("online-sync", "inst-a", Duration.ofSeconds(60)));
        long renewed = redis.withJedis(j -> j.pttl(key));
        assertTrue(renewed > 55_000, "Verlängerung setzt die Lease neu: " + renewed);

        state.releaseLeadership("online-sync", "inst-b");
        assertEquals("inst-a", redis.withJedis(j -> j.get(key)), "fremde Freigabe wirkt nicht");
        state.releaseLeadership("online-sync", "inst-a");
        assertFalse(redis.exists(key));
        assertTrue(state.tryAcquireOrRenewLeadership("online-sync", "inst-b", Duration.ofSeconds(5)));
    }

    @Test
    void liveInstancesScansAllPagesOfThisPrefixAndSkipsExpiredEntries() {
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 450; i++) {
            String id = String.format("inst-%03d", i);
            expected.add(id);
            state.heartbeat(TestSupport.instance(id), Duration.ofSeconds(60));
        }
        state.heartbeat(TestSupport.instance("zz-kurz"), Duration.ofMillis(150));
        redis.withJedis(j -> j.set("anderes-plugin:instance:x", "{}"));
        redis.withJedis(j -> j.set("buildreplay:instance:kaputt", "kein json"));

        TestSupport.waitUntil("kurzer Herzschlag abgelaufen", Duration.ofSeconds(3),
                () -> state.liveInstances().size() == 450);
        List<InstanceInfo> live = state.liveInstances();
        assertEquals(expected, live.stream().map(InstanceInfo::instanceId).toList());
        assertEquals(TestSupport.instance("inst-007"), live.get(7));
    }

    @Test
    void keyPrefixWithGlobCharactersIsMatchedLiterally() {
        RedisSharedState odd = new RedisSharedState(TestSupport.settings(redis.port(), "", "br[1]*:"), "inst-x",
                Clock.systemUTC(), log.logger());
        RedisSharedState similar = new RedisSharedState(TestSupport.settings(redis.port(), "", "br1-:"), "inst-y",
                Clock.systemUTC(), log.logger());
        try {
            odd.heartbeat(TestSupport.instance("x"), Duration.ofSeconds(60));
            similar.heartbeat(TestSupport.instance("y"), Duration.ofSeconds(60));
            assertTrue(redis.exists("br[1]*:instance:x"));
            assertEquals(List.of("x"), odd.liveInstances().stream().map(InstanceInfo::instanceId).toList());
            assertEquals(List.of("y"), similar.liveInstances().stream().map(InstanceInfo::instanceId).toList());
        } finally {
            odd.close();
            similar.close();
        }
    }

    @Test
    void takeHandoffGetsAndDeletesTheRequestAtomically() {
        HandoffRequest request = TestSupport.handoff(7);
        state.putHandoff(player, request, Duration.ofSeconds(60));
        assertTrue(redis.withJedis(j -> j.pttl("buildreplay:handoff:" + player)) > 0);
        assertEquals(Optional.of(request), state.takeHandoff(player));
        assertFalse(redis.exists("buildreplay:handoff:" + player));
        assertEquals(Optional.empty(), state.takeHandoff(player));

        state.putHandoff(player, request, Duration.ofMillis(150));
        TestSupport.sleep(400);
        assertEquals(Optional.empty(), state.takeHandoff(player));
    }

    @Test
    void publishedEventsReachOwnSubscribersAndInvalidPayloadsAreSkipped() {
        List<SharedEvent> received = new CopyOnWriteArrayList<>();
        state.subscribe(received::add);
        TestSupport.waitUntil("abonniert", Duration.ofSeconds(5), state::subscriptionActive);

        redis.withJedis(j -> j.publish("buildreplay:events", "kein json"));
        SharedEvent event = TestSupport.event(SharedEvent.SEGMENT_SAVED, "inst-a", "17");
        state.publish(event);

        TestSupport.waitUntil("Event zugestellt", Duration.ofSeconds(5), () -> !received.isEmpty());
        assertEquals(List.of(event), received);
    }

    @Test
    void invalidStoredEntriesAreTreatedAsAbsent() {
        redis.withJedis(j -> j.set("buildreplay:session:" + player, "{kaputt"));
        redis.withJedis(j -> j.set("buildreplay:presence:" + player, "{}"));
        redis.withJedis(j -> j.hset("buildreplay:rec:" + player, "raw", "nur-raw"));
        redis.withJedis(j -> j.set("buildreplay:activity:" + player, "42"));
        redis.withJedis(j -> j.set("buildreplay:handoff:" + player, "\"text\""));

        assertEquals(Optional.empty(), state.cachedSession(player));
        assertEquals(Optional.empty(), state.presence(player));
        assertEquals(Optional.empty(), state.recordingState(player));
        assertEquals(Optional.empty(), state.activity(player));
        assertEquals(Optional.empty(), state.takeHandoff(player));
    }

    @Test
    void constructingTheClientDoesNotConnectAndFailuresSurfaceAsJedisException() {
        long start = System.nanoTime();
        RedisSharedState unreachable = new RedisSharedState(TestSupport.settings(TestSupport.freePort()), "inst-z",
                Clock.systemUTC(), log.logger());
        try {
            assertTrue(System.nanoTime() - start < Duration.ofMillis(500).toNanos());
            assertThrows(JedisException.class, unreachable::ping);
            assertThrows(JedisException.class, () -> unreachable.cachedSession(player));
            assertNotNull(unreachable.health().lastError());
            assertNull(unreachable.health().lastSuccess());
        } finally {
            unreachable.close();
        }
    }

    @Test
    void subscriberResubscribesAutomaticallyAfterItsConnectionIsKilled() {
        List<SharedEvent> received = new CopyOnWriteArrayList<>();
        state.subscribe(received::add);
        TestSupport.waitUntil("abonniert", Duration.ofSeconds(5), state::subscriptionActive);

        int before = state.subscriptionCount();
        long killed = redis.withJedis(j -> j.clientKill(ClientKillParams.clientKillParams().type(ClientType.PUBSUB)));
        assertTrue(killed >= 1L, "getrennte Abo-Verbindungen: " + killed);
        TestSupport.waitUntil("neu abonniert", Duration.ofSeconds(10),
                () -> state.subscriptionCount() > before && state.subscriptionActive());

        state.publish(TestSupport.event(SharedEvent.SEGMENT_DELETED, "inst-a", "nach-neustart"));
        TestSupport.waitUntil("Event nach Neuabo", Duration.ofSeconds(5), () -> received.size() == 1);
        assertTrue(log.all().contains("Pub/Sub-Verbindung verloren"), log.all());
    }

    @Test
    void silentSubscriberConnectionIsRebuiltByTheWatchdog() {
        List<SharedEvent> received = new CopyOnWriteArrayList<>();
        state.subscribe(received::add);
        TestSupport.waitUntil("abonniert", Duration.ofSeconds(5), state::subscriptionActive);

        int before = state.subscriptionCount();
        state.maintainSubscriber(Duration.ofSeconds(30));
        TestSupport.sleep(200);
        assertTrue(state.subscriptionActive(), "PING hält ein gesundes Abo am Leben");
        assertEquals(before, state.subscriptionCount());

        state.maintainSubscriber(Duration.ZERO);
        assertFalse(state.subscriptionActive(), "stilles Abo gilt sofort als getrennt");
        TestSupport.waitUntil("Abo neu aufgebaut", Duration.ofSeconds(10),
                () -> state.subscriptionCount() > before && state.subscriptionActive());
        state.publish(TestSupport.event(SharedEvent.SEGMENT_SAVED, "inst-a", "1"));
        TestSupport.waitUntil("Event zugestellt", Duration.ofSeconds(5), () -> received.size() == 1);
        assertTrue(log.all().contains("antwortet seit"), log.all());
    }

    @Test
    void closeStopsTheSubscriberThread() {
        state.subscribe(event -> { });
        TestSupport.waitUntil("abonniert", Duration.ofSeconds(5), state::subscriptionActive);
        assertEquals(1L, subscribers());
        long start = System.nanoTime();
        state.close();
        assertTrue(System.nanoTime() - start < Duration.ofSeconds(3).toNanos(), "close blockiert nicht lange");
        assertFalse(state.subscriptionActive());
        TestSupport.waitUntil("Abo beendet", Duration.ofSeconds(5), () -> subscribers() == 0L);
    }

    private static long subscribers() {
        return redis.withJedis(j -> j.pubsubNumSub("buildreplay:events").getOrDefault("buildreplay:events", 0L));
    }
}
