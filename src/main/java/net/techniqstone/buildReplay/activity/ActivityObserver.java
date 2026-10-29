package net.techniqstone.buildReplay.activity;

import java.time.Instant;
import java.util.UUID;

/** Empfängt Aktivitätsereignisse vom Tracker; Aufrufe immer auf dem Hauptthread. */
public interface ActivityObserver {

    /** Relevante Aktion (z. B. Block gesetzt) - startet bzw. verlängert einen Abschnitt. */
    void onRelevant(UUID player, ActivityType type, Instant at);

    /** Aktivitätsindikator (z. B. Bewegung, Chat) - Spieler ist nicht AFK, startet aber keinen Abschnitt. */
    void onIndicator(UUID player, ActivityType type, Instant at);

    /** Spieler ist seit {@code activity.afk-timeout-seconds} ohne jede Eingabe. */
    void onAfk(UUID player, Instant lastInputAt);

    /** Spieler war AFK und hat wieder eine Eingabe gemacht. */
    void onActive(UUID player, Instant at);
}
