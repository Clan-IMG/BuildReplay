package net.techniqstone.buildReplay.recording.service;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Unveränderliche Eckdaten einer Pufferaufnahme (für Store-Zeilen und die nachträgliche Zerlegung). */
record RecordingMeta(String rawName, UUID player, String playerName, @Nullable String worldName, Instant startedAt) {

    RecordingMeta {
        Objects.requireNonNull(rawName, "rawName");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(playerName, "playerName");
        Objects.requireNonNull(startedAt, "startedAt");
    }
}
