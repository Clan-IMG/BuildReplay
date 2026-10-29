package net.techniqstone.buildReplay.state.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.techniqstone.buildReplay.state.HandoffRequest;
import net.techniqstone.buildReplay.state.InstanceInfo;
import net.techniqstone.buildReplay.state.LockToken;
import net.techniqstone.buildReplay.state.PresenceInfo;
import net.techniqstone.buildReplay.state.SharedEvent;
import net.techniqstone.buildReplay.state.SharedState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Zwei BuildReplay-Instanzen ("zwei Server") über {@link SharedStates#create} am selben Redis. */
class TwoInstanceCoordinationTest {

    private static EmbeddedRedis redis;

    private SharedState serverA;
    private SharedState serverB;
    private ExecutorService pool;

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
        serverA = SharedStates.create(TestSupport.settings(redis.port()), "inst-a", Clock.systemUTC(),
                new TestSupport.CapturingLogger("a").logger());
        serverB = SharedStates.create(TestSupport.settings(redis.port()), "inst-b", Clock.systemUTC(),
                new TestSupport.CapturingLogger("b").logger());
        assertInstanceOf(FailoverSharedState.class, serverA);
        pool = Executors.newFixedThreadPool(16);
    }

    @AfterEach
    void tearDown() {
        try {
            assertTrue(serverA.isDistributed(), "A hat nie auf lokal ausweichen müssen");
            assertTrue(serverB.isDistributed(), "B hat nie auf lokal ausweichen müssen");
        } finally {
            pool.shutdownNow();
            serverA.close();
            serverB.close();
        }
    }

    @Test
    void onlyOneInstanceWinsAContendedLock() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Optional<LockToken>>> attempts = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            SharedState server = i % 2 == 0 ? serverA : serverB;
            attempts.add(pool.submit(() -> {
                start.await();
                return server.tryLock("reconcile", Duration.ofSeconds(30));
            }));
        }
        start.countDown();
        List<LockToken> winners = new ArrayList<>();
        for (Future<Optional<LockToken>> attempt : attempts) {
            attempt.get(10, TimeUnit.SECONDS).ifPresent(winners::add);
        }
        assertEquals(1, winners.size());
        LockToken winner = winners.getFirst();
        assertTrue(winner.distributed());

        assertEquals(Optional.empty(), serverA.tryLock("reconcile", Duration.ofSeconds(30)));
        assertEquals(Optional.empty(), serverB.tryLock("reconcile", Duration.ofSeconds(30)));
        assertTrue(serverA.unlock(winner) || serverB.unlock(winner));
        assertTrue(serverB.tryLock("reconcile", Duration.ofSeconds(30)).isPresent(), "nach Freigabe wieder frei");
    }

    @Test
    void unlockWithAWrongTokenFailsAndKeepsTheLock() {
        LockToken held = serverA.tryLock("cleanup", Duration.ofSeconds(30)).orElseThrow();
        LockToken forged = new LockToken("cleanup", UUID.randomUUID().toString(), true);

        assertFalse(serverB.unlock(forged));
        assertFalse(serverA.unlock(forged));
        assertEquals(Optional.empty(), serverB.tryLock("cleanup", Duration.ofSeconds(30)));

        assertTrue(serverB.unlock(held), "das richtige Token gibt auch von einer anderen Instanz aus frei");
        assertTrue(serverB.tryLock("cleanup", Duration.ofSeconds(30)).isPresent());
    }

    @Test
    void lockIsTakenOverAfterTheLeaseExpires() {
        LockToken stale = serverA.tryLock("cleanup", Duration.ofMillis(300)).orElseThrow();
        assertEquals(Optional.empty(), serverB.tryLock("cleanup", Duration.ofSeconds(30)));
        TestSupport.sleep(500);
        LockToken fresh = serverB.tryLock("cleanup", Duration.ofSeconds(30)).orElseThrow();
        assertFalse(serverA.unlock(stale), "altes Token gibt die neue Sperre nicht frei");
        assertEquals(Optional.empty(), serverA.tryLock("cleanup", Duration.ofSeconds(30)));
        assertTrue(serverB.unlock(fresh));
    }

    @Test
    void leadershipIsExclusiveRenewableAndReleasable() {
        Duration lease = Duration.ofSeconds(30);
        assertTrue(serverA.tryAcquireOrRenewLeadership("online-sync", "inst-a", lease));
        assertFalse(serverB.tryAcquireOrRenewLeadership("online-sync", "inst-b", lease));
        assertTrue(serverA.tryAcquireOrRenewLeadership("online-sync", "inst-a", lease), "Verlängerung");
        assertFalse(serverB.tryAcquireOrRenewLeadership("online-sync", "inst-b", lease));

        serverB.releaseLeadership("online-sync", "inst-b");
        assertFalse(serverB.tryAcquireOrRenewLeadership("online-sync", "inst-b", lease), "fremde Freigabe wirkt nicht");

        serverA.releaseLeadership("online-sync", "inst-a");
        assertTrue(serverB.tryAcquireOrRenewLeadership("online-sync", "inst-b", lease));
        assertFalse(serverA.tryAcquireOrRenewLeadership("online-sync", "inst-a", lease));
    }

    @Test
    void leadershipIsTakenOverAfterTheLeaseExpiresWithoutRenewal() {
        Duration shortLease = Duration.ofMillis(1_000);
        assertTrue(serverA.tryAcquireOrRenewLeadership("session-sync", "inst-a", shortLease));
        TestSupport.sleep(500);
        assertTrue(serverA.tryAcquireOrRenewLeadership("session-sync", "inst-a", shortLease),
                "rechtzeitig verlängert");
        TestSupport.sleep(600);
        assertFalse(serverB.tryAcquireOrRenewLeadership("session-sync", "inst-b", shortLease),
                "Verlängerung hat die Lease neu gestartet");

        TestSupport.sleep(1_000);
        assertTrue(serverB.tryAcquireOrRenewLeadership("session-sync", "inst-b", Duration.ofSeconds(30)),
                "Übernahme nach Ablauf");
        assertFalse(serverA.tryAcquireOrRenewLeadership("session-sync", "inst-a", shortLease),
                "der alte Leader bemerkt den Verlust");
    }

    @Test
    void concurrentTakeHandoffHasExactlyOneWinner() throws Exception {
        UUID player = UUID.randomUUID();
        for (int round = 0; round < 25; round++) {
            HandoffRequest request = TestSupport.handoff(round);
            serverA.putHandoff(player, request, Duration.ofSeconds(60));
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Optional<HandoffRequest>>> takers = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                SharedState server = i % 2 == 0 ? serverA : serverB;
                takers.add(pool.submit(() -> {
                    start.await();
                    return server.takeHandoff(player);
                }));
            }
            start.countDown();
            List<HandoffRequest> taken = new ArrayList<>();
            for (Future<Optional<HandoffRequest>> taker : takers) {
                taker.get(10, TimeUnit.SECONDS).ifPresent(taken::add);
            }
            assertEquals(List.of(request), taken, "Runde " + round);
        }
    }

    @Test
    void eventsPublishedOnOneInstanceReachTheOtherAndTheSender() {
        List<SharedEvent> onA = new CopyOnWriteArrayList<>();
        List<SharedEvent> onB = new CopyOnWriteArrayList<>();
        serverA.subscribe(onA::add);
        serverB.subscribe(onB::add);
        TestSupport.waitUntil("beide abonniert", Duration.ofSeconds(5),
                () -> subscribed(serverA) && subscribed(serverB));

        SharedEvent event = TestSupport.event(SharedEvent.SEGMENT_SAVED, "inst-a", "4711");
        serverA.publish(event);

        TestSupport.waitUntil("B empfängt", Duration.ofSeconds(5), () -> onB.size() == 1);
        TestSupport.waitUntil("A empfängt das eigene Event", Duration.ofSeconds(5), () -> onA.size() == 1);
        assertEquals(event, onB.getFirst());
        assertEquals("inst-a", onA.getFirst().originInstance());
    }

    @Test
    void bothInstancesSeeEachOthersHeartbeats() {
        serverA.heartbeat(TestSupport.instance("inst-a"), Duration.ofSeconds(45));
        serverB.heartbeat(TestSupport.instance("inst-b"), Duration.ofSeconds(45));
        assertEquals(List.of("inst-a", "inst-b"), ids(serverA.liveInstances()));
        assertEquals(List.of("inst-a", "inst-b"), ids(serverB.liveInstances()));
    }

    @Test
    void serverSwitchKeepsThePresenceOfTheTargetServer() {
        UUID player = UUID.randomUUID();
        serverA.markPresent(player, TestSupport.presence("build-1", "inst-a", true), Duration.ofSeconds(45));
        assertEquals("build-1", serverB.presence(player).orElseThrow().serverName(), "B sieht den Wechsel");

        PresenceInfo onB = TestSupport.presence("build-2", "inst-b", true);
        serverB.markPresent(player, onB, Duration.ofSeconds(45));
        serverA.markLeft(player, TestSupport.presence("build-1", "inst-a", true), Duration.ofSeconds(30));
        assertEquals(Optional.of(onB), serverA.presence(player));

        serverB.markLeft(player, onB, Duration.ofSeconds(30));
        PresenceInfo left = serverA.presence(player).orElseThrow();
        assertEquals("build-2", left.serverName());
        assertFalse(left.online());
    }

    @Test
    void stateWrittenOnOneServerIsVisibleOnTheOther() {
        UUID player = UUID.randomUUID();
        serverA.cacheSession(player, TestSupport.session("s1"), Duration.ofMinutes(5));
        serverA.putRecordingState(player, TestSupport.recording("brraw-1"), Duration.ofMinutes(5));
        serverA.putActivity(player, TestSupport.activity(true), Duration.ofMinutes(5));

        assertEquals(Optional.of(TestSupport.session("s1")), serverB.cachedSession(player));
        assertEquals(Optional.of(TestSupport.recording("brraw-1")), serverB.recordingState(player));
        assertEquals(Optional.of(TestSupport.activity(true)), serverB.activity(player));

        serverB.clearRecordingState(player, "brraw-1");
        serverB.evictSession(player);
        assertEquals(Optional.empty(), serverA.recordingState(player));
        assertEquals(Optional.empty(), serverA.cachedSession(player));
    }

    private static boolean subscribed(SharedState state) {
        return ((FailoverSharedState) state).subscriptionActive();
    }

    private static List<String> ids(List<InstanceInfo> instances) {
        return instances.stream().map(InstanceInfo::instanceId).toList();
    }
}
