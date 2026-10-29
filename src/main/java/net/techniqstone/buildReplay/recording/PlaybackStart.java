package net.techniqstone.buildReplay.recording;

import org.jspecify.annotations.Nullable;

/**
 * Ergebnis eines Wiedergabestarts. Bei {@code started=false} enthält {@code failureKey} den Nachrichtenschlüssel
 * (z. B. {@code playback.incompatible}, {@code playback.not-found}, {@code playback.world-missing},
 * {@code playback.corrupt}, {@code playback.start-failed}) und {@code detail} eine technische Beschreibung.
 *
 * <p>{@code playback.not-found}, {@code playback.corrupt}, {@code playback.world-missing},
 * {@code playback.incompatible} und {@code playback.already-watching} werden nur gemeldet, bevor AdvancedReplay den
 * Zuschauer angefasst hat (Inventar unverändert). Jeder spätere Fehler ist {@code playback.start-failed}; der
 * Zuschauer ist dann nicht mehr als Wiedergabe registriert, sein Inventar kann aber schon geleert sein.
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
