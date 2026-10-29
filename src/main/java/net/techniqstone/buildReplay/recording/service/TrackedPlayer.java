package net.techniqstone.buildReplay.recording.service;

import java.time.Instant;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/** Anwesender Spieler aus Sicht der Aufnahme-Verwaltung. Nur auf dem Hauptthread benutzen. */
final class TrackedPlayer {

    private final UUID uuid;
    private final String name;
    private final Player player;
    private boolean afk;
    private @Nullable ActiveRecording recording;
    private Instant retryAfter = Instant.MIN;

    TrackedPlayer(UUID uuid, String name, Player player) {
        this.uuid = uuid;
        this.name = name;
        this.player = player;
    }

    UUID uuid() {
        return uuid;
    }

    String name() {
        return name;
    }

    Player player() {
        return player;
    }

    boolean afk() {
        return afk;
    }

    void afk(boolean afk) {
        this.afk = afk;
    }

    @Nullable ActiveRecording recording() {
        return recording;
    }

    void recording(@Nullable ActiveRecording recording) {
        this.recording = recording;
    }

    /** Frühester Zeitpunkt für einen neuen Startversuch nach einem Fehlschlag. */
    Instant retryAfter() {
        return retryAfter;
    }

    void retryAfter(Instant retryAfter) {
        this.retryAfter = retryAfter;
    }
}
