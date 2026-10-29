package net.techniqstone.buildReplay.storage.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Gespeicherter Replay-Abschnitt ({@code <prefix>segments}). {@code replayName} ist der netzwerkweit eindeutige
 * AdvancedReplay-Name; {@code serverName} der Server, auf dem er aufgenommen wurde und abgespielt werden kann
 * (Welt muss dort geladen sein). {@code storageRef} ist nur informativ (z. B. relativer Dateipfad) und
 * ausdrücklich keine netzwerkweit gültige Referenz.
 */
public record SegmentRow(
        long id,
        String replayName,
        long sessionId,
        @Nullable Long recordingId,
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
        @Nullable Long sizeBytes,
        @Nullable String note,
        Instant createdAt) {

    public SegmentRow {
        actionSummary = Map.copyOf(actionSummary);
    }

    public long durationSeconds() {
        return durationTicks / 20L;
    }
}
