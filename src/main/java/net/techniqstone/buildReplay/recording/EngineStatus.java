package net.techniqstone.buildReplay.recording;

import org.jspecify.annotations.Nullable;

/** Zustand der AdvancedReplay-Anbindung für Status und Wiedergabe-Vorabprüfung. */
public record EngineStatus(
        boolean available,
        boolean installed,
        boolean playbackCompatible,
        @Nullable String incompatibilityReason,
        boolean clientSideBlockChanges,
        int maxLengthSeconds,
        String storageBackend,
        String advancedReplayVersion,
        int activeRecordings) {
}
