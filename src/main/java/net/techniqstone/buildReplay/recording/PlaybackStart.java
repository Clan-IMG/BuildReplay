package net.techniqstone.buildReplay.recording;

import org.jspecify.annotations.Nullable;

/**
 * Ergebnis eines Wiedergabestarts. Bei {@code started=false} enthält {@code failureKey} den Nachrichtenschlüssel
 * (z. B. {@code playback.incompatible}, {@code playback.not-found}, {@code playback.world-missing},
 * {@code playback.corrupt}, {@code playback.start-failed}) und {@code detail} eine technische Beschreibung.
 */
public record PlaybackStart(boolean started, @Nullable String failureKey, @Nullable String detail,
                            int durationTicks) {

    public static PlaybackStart ok(int durationTicks) {
        return new PlaybackStart(true, null, null, durationTicks);
    }

    public static PlaybackStart failed(String failureKey, String detail) {
        return new PlaybackStart(false, failureKey, detail, 0);
    }
}
