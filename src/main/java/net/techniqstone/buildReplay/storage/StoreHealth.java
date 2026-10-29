package net.techniqstone.buildReplay.storage;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** Zustand der MariaDB-Anbindung für {@code /buildreplay status}. */
public record StoreHealth(
        boolean enabled,
        boolean connected,
        int queuedWrites,
        int persistedOutboxEntries,
        long droppedWrites,
        @Nullable Instant lastSuccess,
        @Nullable String lastError,
        int schemaVersion) {
}
