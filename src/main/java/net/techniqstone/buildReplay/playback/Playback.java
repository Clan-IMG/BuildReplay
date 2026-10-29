package net.techniqstone.buildReplay.playback;

import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * Wiedergabe über AdvancedReplay. Prüft Berechtigung (eigene Abschnitte, {@code buildreplay.others} für fremde),
 * Verfügbarkeit und Kompatibilität; Abschnitte anderer Server werden per Übergabe dort gestartet. Alle Methoden
 * auf dem Hauptthread; Rückmeldungen gehen als Nachricht an den Zuschauer.
 */
public interface Playback {

    void playSegment(Player viewer, long segmentId);

    /** Alle verfügbaren Abschnitte einer Sitzung nacheinander (mit {@code playback.auto-advance}). */
    void playSession(Player viewer, UUID owner, String sessionRef);

    void stop(Player viewer);

    boolean isWatching(UUID viewer);
}
