package net.techniqstone.buildReplay.recording;

import org.jspecify.annotations.Nullable;

/** Ergebnis von {@link ReplayEngine#saveSegments}. */
public record SavedSegment(
        String replayName,
        int durationTicks,
        @Nullable String worldName,
        @Nullable Long sizeBytes,
        boolean ok,
        @Nullable String error) {
}
