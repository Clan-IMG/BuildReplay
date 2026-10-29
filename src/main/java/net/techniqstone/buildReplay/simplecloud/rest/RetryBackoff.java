package net.techniqstone.buildReplay.simplecloud.rest;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.function.DoubleSupplier;
import org.jspecify.annotations.Nullable;

/**
 * Wartezeiten zwischen Wiederholungen: exponentiell wachsend mit "Full Jitter" zwischen {@code base} und
 * {@code max}, damit viele Instanzen nach einem Ausfall nicht im Gleichschritt erneut anfragen. Ein
 * {@code Retry-After}-Hinweis des Servers wird bevorzugt, aber ebenfalls durch {@code max} begrenzt.
 */
final class RetryBackoff {

    private static final int MAX_DOUBLINGS = 30;

    private final long baseMillis;
    private final long maxMillis;
    private final DoubleSupplier random;

    /**
     * @param base   kleinste Wartezeit (negative Werte zählen als 0)
     * @param max    größte Wartezeit (mindestens {@code base})
     * @param random liefert Zufallswerte in {@code [0, 1)}
     */
    RetryBackoff(Duration base, Duration max, DoubleSupplier random) {
        this.baseMillis = Math.max(0, base.toMillis());
        this.maxMillis = Math.max(this.baseMillis, max.toMillis());
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * Wartezeit vor dem {@code retry}-ten erneuten Versuch ({@code retry >= 1}): gleichverteilt in
     * {@code [base, min(max, base * 2^retry)]}.
     */
    Duration delayFor(int retry) {
        int doublings = Math.min(MAX_DOUBLINGS, Math.max(1, retry));
        long ceiling = baseMillis;
        for (int i = 0; i < doublings && ceiling < maxMillis; i++) {
            ceiling = ceiling > Long.MAX_VALUE / 2 ? maxMillis : Math.min(maxMillis, ceiling * 2);
        }
        double r = random.getAsDouble();
        if (!(r >= 0.0)) {
            r = 0.0;
        } else if (r >= 1.0) {
            r = Math.nextDown(1.0);
        }
        long delay = baseMillis + (long) Math.floor(r * (ceiling - baseMillis + 1));
        return Duration.ofMillis(Math.min(delay, ceiling));
    }

    /** Server-Hinweis ({@code Retry-After}) auf {@code [0, max]} begrenzt. */
    Duration capped(Duration hint) {
        return Duration.ofMillis(Math.max(0, Math.min(hint.toMillis(), maxMillis)));
    }

    Duration max() {
        return Duration.ofMillis(maxMillis);
    }

    /**
     * Liest {@code Retry-After}: Sekunden ({@code "3"}) oder HTTP-Datum (RFC 1123). Unlesbare oder negative
     * Werte ergeben {@code null}, ein Datum in der Vergangenheit {@link Duration#ZERO}.
     */
    static @Nullable Duration parseRetryAfter(@Nullable String value, Instant now) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        if (text.chars().allMatch(Character::isDigit)) {
            try {
                return Duration.ofSeconds(Long.parseLong(text));
            } catch (NumberFormatException | ArithmeticException e) {
                return null;
            }
        }
        try {
            Instant at = ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration wait = Duration.between(now, at);
            return wait.isNegative() ? Duration.ZERO : wait;
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
