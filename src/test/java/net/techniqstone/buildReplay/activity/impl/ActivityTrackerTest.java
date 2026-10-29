package net.techniqstone.buildReplay.activity.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import net.techniqstone.buildReplay.activity.ActivityObserver;
import net.techniqstone.buildReplay.activity.ActivitySnapshot;
import net.techniqstone.buildReplay.activity.ActivityType;
import net.techniqstone.buildReplay.activity.impl.TestSupport.CapturingLogger;
import net.techniqstone.buildReplay.activity.impl.TestSupport.MutableClock;
import net.techniqstone.buildReplay.activity.impl.TestSupport.RecordingObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ActivityTrackerTest {

    private static final Instant T0 = Instant.parse("2026-10-29T14:00:00Z");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private MutableClock clock;
    private ActivityTracker tracker;
    private RecordingObserver observer;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        tracker = new ActivityTracker(TestSupport.activity(), clock);
        observer = new RecordingObserver(T0);
        tracker.addObserver(observer);
    }

    private static Instant at(double seconds) {
        return T0.plusMillis(Math.round(seconds * 1000));
    }

    @Test
    @DisplayName("a player stays idle for more than 60 seconds")
    void aPlayerWithoutInputForTheAfkTimeoutBecomesAfkExactlyOnce() {
        tracker.start(PLAYER, at(0));
        tracker.record(PLAYER, ActivityType.BLOCK_PLACE, at(5));

        tracker.tick(at(64.999));
        assertEquals(List.of("relevant BLOCK_PLACE @5000ms"), observer.calls, "vor Ablauf der 60 s nicht AFK");
        assertFalse(tracker.isAfk(PLAYER));

        tracker.tick(at(65));
        tracker.tick(at(66));
        tracker.tick(at(500));

        assertEquals(List.of("relevant BLOCK_PLACE @5000ms", "afk since @5000ms"), observer.calls);
        assertTrue(tracker.isAfk(PLAYER));
        assertTrue(tracker.snapshot(PLAYER).orElseThrow().afk());
    }

    @Test
    @DisplayName("a player resumes building after an AFK phase")
    void inputAfterAnAfkPhaseFiresOnActiveBeforeTheEventItself() {
        tracker.start(PLAYER, at(0));
        tracker.tick(at(60));
        tracker.record(PLAYER, ActivityType.BLOCK_BREAK, at(90));

        assertEquals(List.of("afk since @0ms", "active @90000ms", "relevant BLOCK_BREAK @90000ms"), observer.calls);
        assertFalse(tracker.isAfk(PLAYER));

        // neue AFK-Phase wird wieder genau einmal gemeldet
        tracker.tick(at(150));
        tracker.tick(at(151));
        assertEquals("afk since @90000ms", observer.calls.get(observer.calls.size() - 1));
        assertEquals(4, observer.calls.size());
    }

    @Test
    void anIndicatorAlsoEndsTheAfkPhaseAndIsNotSwallowedByTheThrottle() {
        tracker.start(PLAYER, at(0));
        tracker.record(PLAYER, ActivityType.MOVE, at(1));
        tracker.tick(at(61));
        tracker.record(PLAYER, ActivityType.MOVE, at(61.2));

        assertEquals(List.of("indicator MOVE @1000ms", "afk since @1000ms", "active @61200ms",
                "indicator MOVE @61200ms"), observer.calls);
    }

    @Test
    void indicatorsAreThrottledPerPlayerAndTypeToOneNotificationPerSecond() {
        tracker.start(PLAYER, at(0));
        tracker.record(PLAYER, ActivityType.MOVE, at(1.0));
        tracker.record(PLAYER, ActivityType.MOVE, at(1.5));
        tracker.record(PLAYER, ActivityType.LOOK, at(1.5));
        tracker.record(PLAYER, ActivityType.MOVE, at(1.999));
        tracker.record(OTHER, ActivityType.MOVE, at(1.999));
        tracker.record(PLAYER, ActivityType.MOVE, at(2.0));
        tracker.record(PLAYER, ActivityType.MOVE, at(2.7));

        assertEquals(List.of("indicator MOVE @1000ms", "indicator LOOK @1500ms", "indicator MOVE @1999ms",
                "indicator MOVE @2000ms"), observer.calls);
        ActivitySnapshot snapshot = tracker.snapshot(PLAYER).orElseThrow();
        assertEquals(at(2.7), snapshot.lastInput(), "auch gedrosselte Indikatoren zählen als Eingabe");
        assertNull(snapshot.lastRelevant());
        assertEquals(Map.of(ActivityType.MOVE, 5L, ActivityType.LOOK, 1L), snapshot.counts());
    }

    @Test
    void throttledIndicatorsStillPreventTheAfkTransition() {
        tracker.start(PLAYER, at(0));
        for (int i = 0; i < 100; i++) {
            tracker.record(PLAYER, ActivityType.MOVE, at(i * 0.9));
        }
        tracker.tick(at(89.1 + 59));

        assertFalse(tracker.isAfk(PLAYER));
        assertTrue(observer.calls.stream().noneMatch(call -> call.startsWith("afk")));
    }

    @Test
    void relevantActionsAreNeverThrottled() {
        tracker.start(PLAYER, at(0));
        tracker.record(PLAYER, ActivityType.BLOCK_PLACE, at(1));
        tracker.record(PLAYER, ActivityType.BLOCK_PLACE, at(1));
        tracker.record(PLAYER, ActivityType.BLOCK_PLACE, at(1.05));

        assertEquals(3, observer.calls.size());
        ActivitySnapshot snapshot = tracker.snapshot(PLAYER).orElseThrow();
        assertEquals(3, snapshot.relevantActions());
        assertEquals(at(1.05), snapshot.lastRelevant());
        assertEquals(at(1.05), snapshot.lastInput());
    }

    @Test
    void typesThatAreNeitherRelevantNorIndicatorsAreIgnoredCompletely() {
        ActivityTracker narrow = new ActivityTracker(
                TestSupport.activityWithTypes(Set.of(ActivityType.BLOCK_PLACE), Set.of(ActivityType.MOVE)), clock);
        RecordingObserver narrowObserver = new RecordingObserver(T0);
        narrow.addObserver(narrowObserver);

        narrow.record(PLAYER, ActivityType.CHAT, at(1));
        narrow.record(PLAYER, ActivityType.BLOCK_BREAK, at(2));

        assertTrue(narrow.snapshot(PLAYER).isEmpty(), "kein Zustand für ignorierte Arten");
        assertEquals(List.of(), narrowObserver.calls);

        narrow.start(PLAYER, at(0));
        narrow.record(PLAYER, ActivityType.CHAT, at(30));
        narrow.tick(at(60));
        assertEquals(List.of("afk since @0ms"), narrowObserver.calls, "ignorierte Arten zählen nicht als Eingabe");
    }

    @Test
    void recordingWithoutStartBeginsTrackingImplicitly() {
        tracker.record(PLAYER, ActivityType.SIGN_EDIT, at(3));

        ActivitySnapshot snapshot = tracker.snapshot(PLAYER).orElseThrow();
        assertEquals(at(3), snapshot.lastInput());
        assertEquals(1, snapshot.relevantActions());
        assertEquals(Map.of(ActivityType.SIGN_EDIT, 1L), snapshot.counts());
        assertEquals(List.of("relevant SIGN_EDIT @3000ms"), observer.calls);
    }

    @Test
    void eventsArrivingOutOfOrderNeverMoveTheLastInputBackwards() {
        tracker.start(PLAYER, at(10));
        tracker.record(PLAYER, ActivityType.CHAT, at(5));
        tracker.record(PLAYER, ActivityType.BLOCK_PLACE, at(20));
        tracker.record(PLAYER, ActivityType.BLOCK_PLACE, at(15));

        ActivitySnapshot snapshot = tracker.snapshot(PLAYER).orElseThrow();
        assertEquals(at(20), snapshot.lastInput());
        assertEquals(at(20), snapshot.lastRelevant());
    }

    @Test
    void startOnAnAfkPlayerCountsAsInputAndFiresOnActive() {
        tracker.start(PLAYER, at(0));
        tracker.tick(at(60));
        tracker.start(PLAYER, at(70));

        assertEquals(List.of("afk since @0ms", "active @70000ms"), observer.calls);
        assertFalse(tracker.isAfk(PLAYER));
    }

    @Test
    void forgetDiscardsTheStateSoNoLaterAfkIsReported() {
        tracker.start(PLAYER, at(0));
        tracker.forget(PLAYER);
        tracker.tick(at(120));

        assertTrue(tracker.snapshot(PLAYER).isEmpty());
        assertEquals(List.of(), observer.calls);
        assertEquals(Set.of(), tracker.trackedPlayers());
    }

    @Test
    void observersMayForgetPlayersWhileBeingNotifiedAboutAfk() {
        tracker.addObserver(new NoOpObserver() {
            @Override
            public void onAfk(UUID player, Instant lastInputAt) {
                tracker.forget(player);
            }
        });
        tracker.start(PLAYER, at(0));
        tracker.start(OTHER, at(0));

        tracker.tick(at(60));

        assertEquals(2, observer.calls.size());
        assertEquals(Set.of(), tracker.trackedPlayers());
    }

    @Test
    void aFailingObserverIsLoggedAndDoesNotStopOtherObservers() {
        CapturingLogger log = new CapturingLogger("tracker");
        ActivityTracker guarded = new ActivityTracker(TestSupport.activity(), clock, log.logger);
        guarded.addObserver(new NoOpObserver() {
            @Override
            public void onRelevant(UUID player, ActivityType type, Instant at) {
                throw new IllegalStateException("kaputt");
            }

            @Override
            public void onAfk(UUID player, Instant lastInputAt) {
                throw new IllegalStateException("auch kaputt");
            }
        });
        RecordingObserver second = new RecordingObserver(T0);
        guarded.addObserver(second);

        guarded.record(PLAYER, ActivityType.BLOCK_PLACE, at(1));
        guarded.tick(at(61));
        guarded.record(PLAYER, ActivityType.BLOCK_PLACE, at(62));

        assertEquals(List.of("relevant BLOCK_PLACE @1000ms", "afk since @1000ms", "active @62000ms",
                "relevant BLOCK_PLACE @62000ms"), second.calls);
        assertEquals(3, log.count(Level.WARNING));
        assertEquals(2, guarded.snapshot(PLAYER).orElseThrow().relevantActions());
    }

    @Test
    void theSameObserverIsOnlyRegisteredOnceAndCanBeRemoved() {
        tracker.addObserver(observer);
        tracker.record(PLAYER, ActivityType.BLOCK_PLACE, at(1));
        assertEquals(1, observer.calls.size());

        tracker.removeObserver(observer);
        tracker.record(PLAYER, ActivityType.BLOCK_PLACE, at(2));
        assertEquals(1, observer.calls.size());
    }

    @Test
    void tickWithoutArgumentUsesTheInjectedClock() {
        tracker.start(PLAYER, T0);
        clock.advance(Duration.ofSeconds(59));
        tracker.tick();
        assertFalse(tracker.isAfk(PLAYER));

        clock.advance(Duration.ofSeconds(1));
        tracker.tick();
        assertTrue(tracker.isAfk(PLAYER));
    }

    @Test
    void aCustomAfkTimeoutIsRespected() {
        ActivityTracker quick = new ActivityTracker(
                TestSupport.activity(Duration.ofSeconds(5), Duration.ofSeconds(60), Duration.ofSeconds(240)), clock);
        quick.start(PLAYER, at(0));
        quick.tick(at(4.9));
        assertFalse(quick.isAfk(PLAYER));
        quick.tick(at(5));
        assertTrue(quick.isAfk(PLAYER));
    }

    /** Beobachter ohne Wirkung als Basis für anonyme Klassen. */
    private static class NoOpObserver implements ActivityObserver {
        @Override
        public void onRelevant(UUID player, ActivityType type, Instant at) {
        }

        @Override
        public void onIndicator(UUID player, ActivityType type, Instant at) {
        }

        @Override
        public void onAfk(UUID player, Instant lastInputAt) {
        }

        @Override
        public void onActive(UUID player, Instant at) {
        }
    }
}
