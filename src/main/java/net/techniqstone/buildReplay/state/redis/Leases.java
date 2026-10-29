package net.techniqstone.buildReplay.state.redis;

import java.time.Duration;
import org.jspecify.annotations.Nullable;

/** Gemeinsame Begrenzung von Lebensdauern (TTL/Lease) für Redis und den lokalen Zustand. */
final class Leases {

    /** Obergrenze, damit {@code Instant.plus} und {@code PX} nie überlaufen. */
    static final Duration MAX = Duration.ofDays(3_650);

    private Leases() {
    }

    /**
     * Lebensdauer in Millisekunden, mindestens 1 ms (Redis lehnt {@code PX 0} ab; nicht positive Werte laufen damit
     * praktisch sofort ab) und höchstens {@link #MAX}.
     */
    static long millis(@Nullable Duration ttl) {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            return 1L;
        }
        if (ttl.compareTo(MAX) > 0) {
            return MAX.toMillis();
        }
        return Math.max(1L, ttl.toMillis());
    }
}
