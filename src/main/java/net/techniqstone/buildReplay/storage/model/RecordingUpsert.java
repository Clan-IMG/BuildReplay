package net.techniqstone.buildReplay.storage.model;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Idempotentes Anlegen einer Pufferaufnahme über {@code rawName}. */
public record RecordingUpsert(
        String rawName,
        @Nullable String sessionKey,
        UUID playerUuid,
        String playerName,
        String serverName,
        String instanceId,
        @Nullable String worldName,
        Instant startedAt,
        RecordingStatus status) {
}
