package net.techniqstone.buildReplay.activity.impl;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import net.techniqstone.buildReplay.activity.ActivityTracking;
import net.techniqstone.buildReplay.activity.ActivityType;
import net.techniqstone.buildReplay.config.PluginConfig;
import net.techniqstone.buildReplay.core.TaskRunner;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Gemeinsamer Meldeweg von {@link ActivityListener} und {@link AxiomActivityBridge}: nimmt den Zeitpunkt sofort,
 * verwirft nicht konfigurierte Arten früh und ruft den Tracker immer auf dem Hauptthread auf (asynchrone Ereignisse
 * wie der Chat werden per {@link TaskRunner#runOnMain} umgeleitet).
 */
final class ActivityReporter {

    /** Metadaten-Schlüssel, mit dem Citizens/FancyNpcs ihre Spieler-NPCs markieren. */
    static final String NPC_METADATA = "NPC";

    private final ActivityTracking tracker;
    private final PluginConfig.ActivitySettings settings;
    private final TaskRunner tasks;
    private final Clock clock;

    ActivityReporter(ActivityTracking tracker, PluginConfig.ActivitySettings settings, TaskRunner tasks, Clock clock) {
        this.tracker = Objects.requireNonNull(tracker, "tracker");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Ob diese Art laut Konfiguration überhaupt ausgewertet wird (relevant oder Indikator). */
    boolean wants(ActivityType type) {
        return settings.isRelevant(type) || settings.isIndicator(type);
    }

    Clock clock() {
        return clock;
    }

    void report(@Nullable Player player, ActivityType type) {
        if (player == null || !wants(type)) {
            return;
        }
        UUID id = player.getUniqueId();
        Instant at = clock.instant();
        if (tasks.isMainThread()) {
            recordNow(player, id, type, at);
            return;
        }
        // asynchrones Ereignis (z. B. AsyncChatEvent): Zeitpunkt behalten, Tracker nur auf dem Hauptthread
        tasks.runOnMain(() -> {
            if (player.isOnline()) {
                recordNow(player, id, type, at);
            }
        });
    }

    private void recordNow(Player player, UUID id, ActivityType type, Instant at) {
        if (player.hasMetadata(NPC_METADATA)) {
            return;
        }
        tracker.record(id, type, at);
    }
}
