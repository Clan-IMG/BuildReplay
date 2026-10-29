package net.techniqstone.buildReplay.activity;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Momentaufnahme der Aktivität eines Spielers (Diagnose, Status). */
public record ActivitySnapshot(
        UUID player,
        @Nullable Instant lastRelevant,
        Instant lastInput,
        boolean afk,
        long relevantActions,
        Map<ActivityType, Long> counts) {

    public ActivitySnapshot {
        counts = Map.copyOf(counts);
    }
}
