package net.techniqstone.buildReplay.state.redis;

import static net.techniqstone.buildReplay.state.redis.TestSupport.T0;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.concurrent.atomic.AtomicInteger;
import net.techniqstone.buildReplay.state.HandoffRequest;
import net.techniqstone.buildReplay.state.InstanceInfo;
import net.techniqstone.buildReplay.state.LockToken;
import net.techniqstone.buildReplay.state.PresenceInfo;
import net.techniqstone.buildReplay.state.SharedEvent;
import net.techniqstone.buildReplay.state.SharedStateHealth;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LocalSharedStateTest {

    private MutableClock clock;
    private TestSupport.CapturingLogger log;
    private LocalSharedState state;
    private final UUID player = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        log = new TestSupport.CapturingLogger("local");
        state = new LocalSharedState(clock, log.logger());
    }

    @AfterEach
    void tearDown() {
        state.close();
    }

    @Test
    void healthReportsALocalOnlyStateWithoutRedis() {
        assertFalse(state.isDistributed());
        assertEquals(new SharedStateHealth(false, false, true, null, null), state.health());
    }

    @Test
    void cachedSessionIsReturnedUntilItsTtlExpires() {
        state.cacheSession(player, TestSupport.session("s1"), Duration.ofSeconds(10));
        assertEquals(Optional.of(TestSupport.session("s1")), state.cachedSession(player));

        clock.advance(Duration.ofMillis(9_999));
        assertTrue(state.cachedSession(player).isPresent());
        clock.advance(Duration.ofMillis(1));
        assertEquals(Optional.empty(), state.cachedSession(player));
    }

    @Test
    void cachingAgainReplacesTheSessionAndRestartsTheTtl() {
        state.cacheSession(player, TestSupport.session("s1"), Duration.ofSeconds(10));
        clock.advance(Duration.ofSeconds(8));
        state.cacheSession(player, TestSupport.session("s2"), Duration.ofSeconds(10));
        clock.advance(Duration.ofSeconds(8));
        assertEquals("s2", state.cachedSession(player).orElseThrow().sessionKey());
    }

    @Test
    void evictSessionRemovesTheCachedSession() {
        state.cacheSession(player, TestSupport.session("s1"), Duration.ofMinutes(5));
        state.evictSession(player);
        assertEquals(Optional.empty(), state.cachedSession(player));
        state.evictSession(UUID.randomUUID());
    }

    @Test
    void markPresentIsVisibleForItsTtl() {
        PresenceInfo here = TestSupport.presence("build-1", "inst-a", true);
        state.markPresent(player, here, Duration.ofSeconds(45));
        assertEquals(Optional.of(here), state.presence(player));
        clock.advance(Duration.ofSeconds(45));
        assertEquals(Optional.empty(), state.presence(player));
    }

    @Test
    void markLeftKeepsAnOfflineEntryForTheGracePeriod() {
        state.markPresent(player, TestSupport.presence("build-1", "inst-a", true), Duration.ofSeconds(45));
        state.markLeft(player, TestSupport.presence("build-1", "inst-a", true), Duration.ofSeconds(30));

        PresenceInfo left = state.presence(player).orElseThrow();
        assertFalse(left.online(), "online wird beim Verlassen immer auf false gesetzt");
        assertEquals("build-1", left.serverName());
        clock.advance(Duration.ofSeconds(29));
        assertTrue(state.presence(player).isPresent());
        clock.advance(Duration.ofSeconds(1));
        assertEquals(Optional.empty(), state.presence(player));
    }

    @Test
    void markLeftWritesTheOfflineEntryWhenNoPresenceExists() {
        state.markLeft(player, TestSupport.presence("build-1", "inst-a", false), Duration.ofSeconds(30));
        assertEquals(Optional.of(TestSupport.presence("build-1", "inst-a", false)), state.presence(player));
    }

    @Test
    void markLeftDoesNotOverwriteANewerPresenceFromAnotherServer() {
        state.markPresent(player, TestSupport.presence("build-1", "inst-a", true), Duration.ofSeconds(45));
        // Serverwechsel: Zielserver meldet den Spieler an, bevor der alte Server das Verlassen meldet
        PresenceInfo target = TestSupport.presence("build-2", "inst-b", true);
        state.markPresent(player, target, Duration.ofSeconds(45));
        state.markLeft(player, TestSupport.presence("build-1", "inst-a", false), Duration.ofSeconds(30));

        assertEquals(Optional.of(target), state.presence(player));
    }

    @Test
    void recordingStateIsOnlyClearedForTheMatchingRawName() {
        state.putRecordingState(player, TestSupport.recording("brraw-1"), Duration.ofMinutes(30));
        state.clearRecordingState(player, "brraw-other");
        assertEquals(Optional.of(TestSupport.recording("brraw-1")), state.recordingState(player));

        state.clearRecordingState(player, "brraw-1");
        assertEquals(Optional.empty(), state.recordingState(player));
    }

    @Test
    void recordingStateExpiresAfterItsTtl() {
        state.putRecordingState(player, TestSupport.recording("brraw-1"), Duration.ofMinutes(1));
        clock.advance(Duration.ofMinutes(1));
        assertEquals(Optional.empty(), state.recordingState(player));
        state.clearRecordingState(player, "brraw-1");
    }

    @Test
    void activityIsStoredWithNullableLastRelevantAndExpires() {
        state.putActivity(player, TestSupport.activity(false), Duration.ofSeconds(90));
        assertNull(state.activity(player).orElseThrow().lastRelevant());
        state.putActivity(player, TestSupport.activity(true), Duration.ofSeconds(90));
        assertEquals(Optional.of(TestSupport.activity(true)), state.activity(player));
        clock.advance(Duration.ofSeconds(90));
        assertEquals(Optional.empty(), state.activity(player));
    }

    @Test
    void lockIsExclusiveUntilUnlockedWithTheRightToken() {
        LockToken token = state.tryLock("reconcile", Duration.ofSeconds(30)).orElseThrow();
        assertEquals("reconcile", token.name());
        assertFalse(token.distributed());
        assertEquals(Optional.empty(), state.tryLock("reconcile", Duration.ofSeconds(30)));
        assertTrue(state.tryLock("other", Duration.ofSeconds(30)).isPresent(), "andere Namen sind unabhängig");

        assertFalse(state.unlock(new LockToken("reconcile", "falsches-token", false)));
        assertEquals(Optional.empty(), state.tryLock("reconcile", Duration.ofSeconds(30)));

        assertTrue(state.unlock(token));
        assertFalse(state.unlock(token), "zweite Freigabe hat nichts mehr freizugeben");
        LockToken next = state.tryLock("reconcile", Duration.ofSeconds(30)).orElseThrow();
        assertNotEquals(token.token(), next.token());
    }

    @Test
    void lockBecomesAvailableAgainAfterTheLeaseExpires() {
        LockToken stale = state.tryLock("cleanup", Duration.ofSeconds(10)).orElseThrow();
        clock.advance(Duration.ofSeconds(10));
        LockToken fresh = state.tryLock("cleanup", Duration.ofSeconds(10)).orElseThrow();
        assertFalse(state.unlock(stale), "abgelaufenes Token darf die neue Sperre nicht freigeben");
        assertEquals(Optional.empty(), state.tryLock("cleanup", Duration.ofSeconds(10)));
        assertTrue(state.unlock(fresh));
    }

    @Test
    void concurrentLockAttemptsHaveExactlyOneWinner() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Optional<LockToken>>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return state.tryLock("contended", Duration.ofSeconds(30));
                }));
            }
            start.countDown();
            int winners = 0;
            for (Future<Optional<LockToken>> result : results) {
                winners += result.get(10, TimeUnit.SECONDS).isPresent() ? 1 : 0;
            }
            assertEquals(1, winners);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void leadershipCanOnlyBeRenewedByTheLeaderAndIsTakenOverAfterExpiry() {
        Duration lease = Duration.ofSeconds(30);
        assertTrue(state.tryAcquireOrRenewLeadership("online-sync", "inst-a", lease));
        assertFalse(state.tryAcquireOrRenewLeadership("online-sync", "inst-b", lease));

        clock.advance(Duration.ofSeconds(20));
        assertTrue(state.tryAcquireOrRenewLeadership("online-sync", "inst-a", lease), "Verlängerung");
        clock.advance(Duration.ofSeconds(20));
        assertFalse(state.tryAcquireOrRenewLeadership("online-sync", "inst-b", lease),
                "Verlängerung hat die Lease neu gestartet");

        clock.advance(Duration.ofSeconds(10));
        assertTrue(state.tryAcquireOrRenewLeadership("online-sync", "inst-b", lease), "Übernahme nach Ablauf");
        assertFalse(state.tryAcquireOrRenewLeadership("online-sync", "inst-a", lease));
        assertTrue(state.tryAcquireOrRenewLeadership("session-sync", "inst-a", lease), "Rollen sind getrennt");
    }

    @Test
    void releaseLeadershipIgnoresOtherInstances() {
        Duration lease = Duration.ofSeconds(30);
        assertTrue(state.tryAcquireOrRenewLeadership("online-sync", "inst-a", lease));
        state.releaseLeadership("online-sync", "inst-b");
        assertFalse(state.tryAcquireOrRenewLeadership("online-sync", "inst-b", lease));

        state.releaseLeadership("online-sync", "inst-a");
        assertTrue(state.tryAcquireOrRenewLeadership("online-sync", "inst-b", lease));
    }

    @Test
    void liveInstancesListsOnlyUnexpiredHeartbeatsSortedById() {
        state.heartbeat(TestSupport.instance("c"), Duration.ofSeconds(45));
        state.heartbeat(TestSupport.instance("a"), Duration.ofSeconds(45));
        state.heartbeat(TestSupport.instance("b"), Duration.ofSeconds(10));
        assertEquals(List.of("a", "b", "c"), ids(state.liveInstances()));

        clock.advance(Duration.ofSeconds(10));
        assertEquals(List.of("a", "c"), ids(state.liveInstances()));
        state.heartbeat(TestSupport.instance("b"), Duration.ofSeconds(45));
        assertEquals(List.of("a", "b", "c"), ids(state.liveInstances()));
    }

    @Test
    void takeHandoffReturnsTheRequestExactlyOnce() {
        HandoffRequest request = TestSupport.handoff(42);
        state.putHandoff(player, request, Duration.ofSeconds(60));
        assertEquals(Optional.of(request), state.takeHandoff(player));
        assertEquals(Optional.empty(), state.takeHandoff(player));
    }

    @Test
    void expiredHandoffIsNotReturned() {
        state.putHandoff(player, TestSupport.handoff(1), Duration.ofSeconds(60));
        clock.advance(Duration.ofSeconds(60));
        assertEquals(Optional.empty(), state.takeHandoff(player));
    }

    @Test
    void concurrentTakeHandoffHasExactlyOneWinner() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int round = 0; round < 50; round++) {
                state.putHandoff(player, TestSupport.handoff(round), Duration.ofSeconds(60));
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Optional<HandoffRequest>>> results = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    results.add(pool.submit(() -> {
                        start.await();
                        return state.takeHandoff(player);
                    }));
                }
                start.countDown();
                int winners = 0;
                for (Future<Optional<HandoffRequest>> result : results) {
                    winners += result.get(10, TimeUnit.SECONDS).isPresent() ? 1 : 0;
                }
                assertEquals(1, winners, "Runde " + round);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void publishDeliversToLocalListenersOnABackgroundThread() throws Exception {
        List<SharedEvent> received = new CopyOnWriteArrayList<>();
        List<Thread> threads = new CopyOnWriteArrayList<>();
        CountDownLatch delivered = new CountDownLatch(2);
        state.subscribe(event -> {
            received.add(event);
            threads.add(Thread.currentThread());
            delivered.countDown();
        });

        state.publish(TestSupport.event(SharedEvent.SEGMENT_SAVED, "inst-a", "1"));
        state.publish(TestSupport.event(SharedEvent.SESSION_CLOSED, "inst-a", "2"));

        assertTrue(delivered.await(5, TimeUnit.SECONDS));
        assertEquals(List.of(SharedEvent.SEGMENT_SAVED, SharedEvent.SESSION_CLOSED),
                received.stream().map(SharedEvent::type).toList(), "Reihenfolge bleibt erhalten");
        assertTrue(threads.stream().noneMatch(t -> t == Thread.currentThread()));
    }

    @Test
    void aFailingListenerDoesNotStopDeliveryToOthers() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch delivered = new CountDownLatch(1);
        state.subscribe(event -> {
            throw new IllegalStateException("kaputt");
        });
        state.subscribe(event -> {
            calls.incrementAndGet();
            delivered.countDown();
        });
        state.publish(TestSupport.event(SharedEvent.SEGMENT_DELETED, "inst-a", "x"));
        assertTrue(delivered.await(5, TimeUnit.SECONDS));
        assertEquals(1, calls.get());
        TestSupport.waitUntil("Fehler protokolliert", Duration.ofSeconds(5),
                () -> log.all().contains("Empfänger"));
    }

    @Test
    void publishWithoutListenersIsANoOp() {
        state.publish(TestSupport.event(SharedEvent.SEGMENT_SAVED, "inst-a", "1"));
    }

    @Test
    void nonPositiveTtlsExpireImmediatelyInsteadOfFailing() {
        state.cacheSession(player, TestSupport.session("s"), Duration.ZERO);
        state.putActivity(player, TestSupport.activity(true), Duration.ofSeconds(-5));
        clock.advance(Duration.ofMillis(1));
        assertEquals(Optional.empty(), state.cachedSession(player));
        assertEquals(Optional.empty(), state.activity(player));
    }

    @Test
    void hugeTtlsAreClampedInsteadOfOverflowing() {
        state.cacheSession(player, TestSupport.session("s"), Duration.ofSeconds(Long.MAX_VALUE));
        clock.advance(Duration.ofDays(3_000));
        assertTrue(state.cachedSession(player).isPresent());
    }

    @Test
    void expiredEntriesArePurgedByLaterWritesEvenIfNeverReadAgain() {
        for (int i = 0; i < 100; i++) {
            state.putActivity(UUID.randomUUID(), TestSupport.activity(true), Duration.ofSeconds(5));
        }
        assertEquals(100, state.storedEntries());
        clock.advance(Duration.ofSeconds(31));
        state.putActivity(player, TestSupport.activity(true), Duration.ofSeconds(5));
        assertEquals(1, state.storedEntries());
    }

    @Test
    void resetAfterOutageDropsVolatileStateButKeepsLocksAndHeartbeats() {
        state.cacheSession(player, TestSupport.session("s"), Duration.ofMinutes(5));
        state.markPresent(player, TestSupport.presence("build-1", "inst-a", true), Duration.ofMinutes(5));
        state.putRecordingState(player, TestSupport.recording("brraw-1"), Duration.ofMinutes(5));
        state.putActivity(player, TestSupport.activity(true), Duration.ofMinutes(5));
        state.putHandoff(player, TestSupport.handoff(1), Duration.ofMinutes(5));
        assertTrue(state.tryAcquireOrRenewLeadership("online-sync", "inst-a", Duration.ofMinutes(5)));
        LockToken lock = state.tryLock("held", Duration.ofMinutes(5)).orElseThrow();
        state.heartbeat(TestSupport.instance("a"), Duration.ofMinutes(5));

        state.resetAfterOutage();

        assertEquals(Optional.empty(), state.cachedSession(player));
        assertEquals(Optional.empty(), state.presence(player));
        assertEquals(Optional.empty(), state.recordingState(player));
        assertEquals(Optional.empty(), state.activity(player));
        assertEquals(Optional.empty(), state.takeHandoff(player));
        assertTrue(state.tryAcquireOrRenewLeadership("online-sync", "inst-b", Duration.ofMinutes(5)));
        assertEquals(Optional.empty(), state.tryLock("held", Duration.ofMinutes(5)), "Sperre bleibt gehalten");
        assertTrue(state.unlock(lock));
        assertEquals(List.of("a"), ids(state.liveInstances()));
    }

    private static List<String> ids(List<InstanceInfo> instances) {
        return instances.stream().map(InstanceInfo::instanceId).toList();
    }
}
