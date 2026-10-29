package net.techniqstone.buildReplay.storage.model;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Fortlaufende Pufferaufnahme ({@code <prefix>recordings}). */
public record RecordingRow(
        long id,
        String rawName,
        @Nullable Long sessionId,
        UUID playerUuid,
        String playerName,
        String serverName,
        String instanceId,
        @Nullable String worldName,
        Instant startedAt,
        @Nullable Instant endedAt,
        RecordingStatus status,
        int relevantActions,
        @Nullable String error) {
}
