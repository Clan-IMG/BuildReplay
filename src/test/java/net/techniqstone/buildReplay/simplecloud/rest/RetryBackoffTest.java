package net.techniqstone.buildReplay.simplecloud.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;

class RetryBackoffTest {

    private static final Duration BASE = Duration.ofMillis(500);
    private static final Duration MAX = Duration.ofMillis(8_000);

    @Test
    void theLowestRandomValueWaitsTheBaseDelay() {
        RetryBackoff backoff = new RetryBackoff(BASE, MAX, () -> 0.0);

        for (int retry = 1; retry <= 10; retry++) {
            assertEquals(BASE, backoff.delayFor(retry));
        }
    }

    @Test
    void theHighestRandomValueDoublesPerRetryUpToTheMaximum() {
        RetryBackoff backoff = new RetryBackoff(BASE, MAX, () -> Math.nextDown(1.0));

        assertEquals(Duration.ofMillis(1_000), backoff.delayFor(1));
        assertEquals(Duration.ofMillis(2_000), backoff.delayFor(2));
        assertEquals(Duration.ofMillis(4_000), backoff.delayFor(3));
        assertEquals(Duration.ofMillis(8_000), backoff.delayFor(4));
        assertEquals(Duration.ofMillis(8_000), backoff.delayFor(40));
    }

    @Test
    void randomDelaysStayBetweenBaseAndTheCurrentCeiling() {
        RetryBackoff backoff = new RetryBackoff(BASE, MAX, () -> ThreadLocalRandom.current().nextDouble());

        for (int i = 0; i < 1_000; i++) {
            long delay = backoff.delayFor(2).toMillis();
            assertTrue(delay >= 500 && delay <= 2_000, "Wartezeit " + delay);
        }
    }

    @Test
    void capsServerHintsAtTheMaximum() {
        RetryBackoff backoff = new RetryBackoff(BASE, MAX, () -> 0.0);

        assertEquals(MAX, backoff.capped(Duration.ofSeconds(120)));
        assertEquals(Duration.ofSeconds(2), backoff.capped(Duration.ofSeconds(2)));
        assertEquals(Duration.ZERO, backoff.capped(Duration.ofSeconds(-3)));
    }

    @Test
    void aMaximumBelowTheBaseIsRaisedToTheBase() {
        RetryBackoff backoff = new RetryBackoff(Duration.ofSeconds(2), Duration.ofSeconds(1), () -> 0.9);

        assertEquals(Duration.ofSeconds(2), backoff.delayFor(3));
        assertEquals(Duration.ofSeconds(2), backoff.max());
    }

    @Test
    void parsesRetryAfterAsSecondsOrHttpDate() {
        Instant now = Instant.parse("2026-10-29T12:00:00Z");

        assertEquals(Duration.ofSeconds(3), RetryBackoff.parseRetryAfter("3", now));
        assertEquals(Duration.ofSeconds(0), RetryBackoff.parseRetryAfter(" 0 ", now));
        assertEquals(Duration.ofSeconds(90),
                RetryBackoff.parseRetryAfter("Thu, 29 Oct 2026 12:01:30 GMT", now));
        assertEquals(Duration.ZERO, RetryBackoff.parseRetryAfter("Thu, 29 Oct 2026 11:00:00 GMT", now));
        assertNull(RetryBackoff.parseRetryAfter("-5", now));
        assertNull(RetryBackoff.parseRetryAfter("bald", now));
        assertNull(RetryBackoff.parseRetryAfter("", now));
        assertNull(RetryBackoff.parseRetryAfter(null, now));
        assertNull(RetryBackoff.parseRetryAfter("99999999999999999999999", now));
    }
}
