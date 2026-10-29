package net.techniqstone.buildReplay.simplecloud.rest;

import static net.techniqstone.buildReplay.simplecloud.rest.CircuitBreaker.Permit.NORMAL;
import static net.techniqstone.buildReplay.simplecloud.rest.CircuitBreaker.Permit.REJECTED;
import static net.techniqstone.buildReplay.simplecloud.rest.CircuitBreaker.Permit.TRIAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import net.techniqstone.buildReplay.simplecloud.rest.CircuitBreaker.Change;
import net.techniqstone.buildReplay.simplecloud.rest.CircuitBreaker.State;
import net.techniqstone.buildReplay.simplecloud.rest.TestSupport.MutableClock;
import org.junit.jupiter.api.Test;

class CircuitBreakerTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-29T12:00:00Z"));
    private final CircuitBreaker breaker = new CircuitBreaker(3, Duration.ofSeconds(30), clock);

    @Test
    void staysClosedBelowTheThreshold() {
        assertEquals(Change.NONE, breaker.onFailure(breaker.tryAcquire()));
        assertEquals(Change.NONE, breaker.onFailure(breaker.tryAcquire()));

        assertEquals(State.CLOSED, breaker.state());
        assertEquals(NORMAL, breaker.tryAcquire());
    }

    @Test
    void aSuccessResetsTheConsecutiveFailureCount() {
        breaker.onFailure(breaker.tryAcquire());
        breaker.onFailure(breaker.tryAcquire());
        breaker.onSuccess(breaker.tryAcquire());
        breaker.onFailure(breaker.tryAcquire());
        breaker.onFailure(breaker.tryAcquire());

        assertEquals(State.CLOSED, breaker.state());
    }

    @Test
    void opensAtTheThresholdAndRejectsUntilTheOpenDurationHasPassed() {
        openBreaker();

        assertTrue(breaker.isOpen());
        assertEquals(clock.instant().plusSeconds(30), breaker.openUntil());
        assertEquals(REJECTED, breaker.tryAcquire());
        clock.advance(Duration.ofSeconds(29));
        assertEquals(REJECTED, breaker.tryAcquire());
    }

    @Test
    void allowsExactlyOneTrialAfterTheOpenDuration() {
        openBreaker();
        clock.advance(Duration.ofSeconds(30));

        assertEquals(TRIAL, breaker.tryAcquire());
        assertEquals(State.HALF_OPEN, breaker.state());
        assertEquals(REJECTED, breaker.tryAcquire());
    }

    @Test
    void closesWhenTheTrialSucceeds() {
        openBreaker();
        clock.advance(Duration.ofSeconds(30));

        assertEquals(Change.CLOSED, breaker.onSuccess(breaker.tryAcquire()));
        assertFalse(breaker.isOpen());
        assertEquals(NORMAL, breaker.tryAcquire());
    }

    @Test
    void reopensWhenTheTrialFails() {
        openBreaker();
        clock.advance(Duration.ofSeconds(30));

        assertEquals(Change.REOPENED, breaker.onFailure(breaker.tryAcquire()));
        assertEquals(State.OPEN, breaker.state());
        assertEquals(REJECTED, breaker.tryAcquire());
    }

    @Test
    void permitsANewTrialWhenTheFirstTrialNeverReportsBack() {
        openBreaker();
        clock.advance(Duration.ofSeconds(30));
        assertEquals(TRIAL, breaker.tryAcquire());

        clock.advance(Duration.ofSeconds(30));

        assertEquals(TRIAL, breaker.tryAcquire());
    }

    @Test
    void ignoresLateFailuresOfCallsStartedBeforeOpening() {
        CircuitBreaker.Permit early = breaker.tryAcquire();
        openBreaker();
        clock.advance(Duration.ofSeconds(30));
        CircuitBreaker.Permit trial = breaker.tryAcquire();

        assertEquals(Change.NONE, breaker.onFailure(early));
        assertEquals(State.HALF_OPEN, breaker.state());
        assertEquals(Change.CLOSED, breaker.onSuccess(trial));
    }

    @Test
    void ignoresRejectedPermits() {
        openBreaker();

        assertEquals(Change.NONE, breaker.onSuccess(REJECTED));
        assertEquals(Change.NONE, breaker.onFailure(REJECTED));
        assertEquals(State.OPEN, breaker.state());
    }

    private void openBreaker() {
        breaker.onFailure(breaker.tryAcquire());
        breaker.onFailure(breaker.tryAcquire());
        assertEquals(Change.OPENED, breaker.onFailure(breaker.tryAcquire()));
    }
}
