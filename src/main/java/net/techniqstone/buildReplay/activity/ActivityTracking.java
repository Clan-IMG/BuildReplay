package net.techniqstone.buildReplay.activity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Aktivitäts- und AFK-Erkennung (Hauptthread). Listener melden Ereignisse, der Tracker klassifiziert sie anhand der
 * Konfiguration als relevant oder Indikator, drosselt hochfrequente Indikatoren (Bewegung) und meldet AFK-Übergänge
 * über einen sekündlichen Takt.
 */
public interface ActivityTracking {

    void addObserver(ActivityObserver observer);

    /** Ereignis melden (Hauptthread). Nicht konfigurierte Arten werden ignoriert. */
    void record(UUID player, ActivityType type, Instant at);

    /** Spieler beginnt auf diesem Server (zählt als Eingabe, nicht AFK). */
    void start(UUID player, Instant at);

    /** Spieler hat den Server verlassen - Zustand verwerfen. */
    void forget(UUID player);

    Optional<ActivitySnapshot> snapshot(UUID player);
}
