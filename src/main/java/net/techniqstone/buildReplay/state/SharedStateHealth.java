package net.techniqstone.buildReplay.state;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** Zustand der Redis-Anbindung für {@code /buildreplay status}. */
public record SharedStateHealth(
        boolean enabled,
        boolean connected,
        boolean usingLocalFallback,
        @Nullable Instant lastSuccess,
        @Nullable String lastError) {
}
