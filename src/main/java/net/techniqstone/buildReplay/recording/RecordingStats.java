package net.techniqstone.buildReplay.recording;

/** Kennzahlen der Aufnahme-Verwaltung für {@code /buildreplay status}. */
public record RecordingStats(
        int active,
        long rawRecordingsStarted,
        long segmentsSaved,
        long recordingsDiscarded,
        long failures,
        int pendingFinalizations) {
}
