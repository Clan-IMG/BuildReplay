package net.techniqstone.buildReplay.storage.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Idempotentes Anlegen/Aktualisieren eines Abschnitts über {@code replayName}. Existiert die Sitzung
 * {@code sessionKey} noch nicht, legt der Store eine vorläufige Sitzung mit diesem Schlüssel an.
 */
public record SegmentUpsert(
        String replayName,
        String sessionKey,
        @Nullable String rawName,
        UUID playerUuid,
        String playerName,
        String serverName,
        @Nullable String worldName,
        String storageBackend,
        @Nullable String storageRef,
        Instant startedAt,
        Instant endedAt,
        Instant firstActionAt,
        Instant lastActionAt,
        int durationTicks,
        int preRollMillis,
        int postRollMillis,
        int relevantActions,
        Map<String, Integer> actionSummary,
        SegmentStatus status,
        @Nullable Long sizeBytes) {

    public SegmentUpsert {
        actionSummary = Map.copyOf(actionSummary);
    }
}
