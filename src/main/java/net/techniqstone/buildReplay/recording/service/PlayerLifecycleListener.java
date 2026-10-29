package net.techniqstone.buildReplay.recording.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import net.techniqstone.buildReplay.activity.ActivityTracking;
import net.techniqstone.buildReplay.playback.service.PlaybackService;
import net.techniqstone.buildReplay.session.Sessions;
import net.techniqstone.buildReplay.storage.model.VisitEndReason;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Legt die Reihenfolge beim Betreten und Verlassen fest:
 * <ul>
 *   <li>Join (MONITOR): Sitzung/Aufenthalt → Aktivität → Aufnahme → Wiedergabe (verwaistes Inventar, Übergabe).</li>
 *   <li>Quit (LOWEST, vor AdvancedReplays eigenem Quit-Handler): Inventar eines Zuschauers sofort
 *       wiederherstellen.</li>
 *   <li>Quit (MONITOR): Aufnahme abschließen → Aktivität vergessen → Aufenthalt beenden.</li>
 *   <li>Welt-/Spielmoduswechsel: im nächsten Tick neu bewerten.</li>
 * </ul>
 */
public final class PlayerLifecycleListener implements Listener {

    private final Sessions sessions;
    private final ActivityTracking activity;
    private final RecordingService recordings;
    private final PlaybackService playback;
    private final Clock clock;

    public PlayerLifecycleListener(Sessions sessions, ActivityTracking activity, RecordingService recordings,
                                   PlaybackService playback, Clock clock) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.activity = Objects.requireNonNull(activity, "activity");
        this.recordings = Objects.requireNonNull(recordings, "recordings");
        this.playback = Objects.requireNonNull(playback, "playback");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        join(event.getPlayer());
    }

    /**
     * Hauptthread: Spieler übernehmen - beim Join und beim Aktivieren des Plugins für bereits anwesende Spieler
     * (Plugin-Reload).
     */
    public void join(Player player) {
        UUID id = player.getUniqueId();
        Instant now = clock.instant();
        sessions.handleJoin(id, player.getName(), now);
        activity.start(id, now);
        recordings.onJoin(player);
        playback.onJoin(player);
    }

    /** Vor AdvancedReplay: dessen Rücksetzung läuft erst, wenn der Spieler schon weg ist - Items gingen verloren. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuitEarly(PlayerQuitEvent event) {
        playback.onQuitEarly(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        recordings.onQuit(player);
        activity.forget(id);
        sessions.handleQuit(id, player.getName(), clock.instant(), VisitEndReason.QUIT);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        recordings.reevaluateSoon(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        recordings.reevaluateSoon(event.getPlayer());
    }
}
