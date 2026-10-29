package net.techniqstone.buildReplay.recording;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Laufende Pufferaufnahme eines Spielers auf diesem Server. */
public record ActiveRecordingInfo(
        UUID player,
        String playerName,
        String rawName,
        @Nullable String worldName,
        Instant startedAt,
        int currentTick,
        int relevantActions,
        boolean afk,
        @Nullable String sessionRef) {
}
