package net.techniqstone.buildReplay.simplecloud;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** Zustand des SimpleCloud-REST-Clients für {@code /buildreplay status}. */
public record ClientHealth(
        boolean configured,
        boolean circuitOpen,
        @Nullable Instant lastSuccess,
        @Nullable Instant lastFailure,
        @Nullable String lastError,
        long requests,
        long failures) {
}
