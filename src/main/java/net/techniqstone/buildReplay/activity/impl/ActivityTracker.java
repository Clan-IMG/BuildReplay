package net.techniqstone.buildReplay.activity.impl;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.techniqstone.buildReplay.activity.ActivityObserver;
import net.techniqstone.buildReplay.activity.ActivitySnapshot;
import net.techniqstone.buildReplay.activity.ActivityTracking;
import net.techniqstone.buildReplay.activity.ActivityType;
import net.techniqstone.buildReplay.config.PluginConfig;
import org.jspecify.annotations.Nullable;

/**
 * Aktivitäts- und AFK-Erkennung pro Spieler.
 *
 * <ul>
 *   <li>{@link #record}: relevante Aktionen setzen „letzte relevante Aktion“ und „letzte Eingabe“ und werden immer
 *       gemeldet; Indikatoren setzen nur „letzte Eingabe“ und werden pro Spieler und Art höchstens einmal je
 *       {@link #INDICATOR_THROTTLE} gemeldet (Bewegung/Blick feuern ständig). Nicht konfigurierte Arten werden
 *       ignoriert. War der Spieler AFK, geht {@link ActivityObserver#onActive} der eigentlichen Meldung voraus.</li>
 *   <li>{@link #tick(Instant)} (das Plugin ruft es sekündlich auf): Spieler ohne Eingabe seit
 *       {@code activity.afk-timeout-seconds} werden AFK - {@link ActivityObserver#onAfk} genau einmal pro
 *       AFK-Phase.</li>
 *   <li>{@link #start}: erste Eingabe auf diesem Server; {@link #forget}: Zustand verwerfen (Verlassen).</li>
 * </ul>
 *
 * <p>Hauptthread-gebunden und nicht threadsicher; nur die Beobachterliste verträgt Änderungen während einer
 * Benachrichtigung. Fehler in Beobachtern werden protokolliert (falls ein Logger übergeben wurde) und unterbrechen
 * weder den Tracker noch andere Beobachter.
 */
public final class ActivityTracker implements ActivityTracking {

    /** Mindestabstand zwischen zwei Indikator-Meldungen pro Spieler und Art. */
    public static final Duration INDICATOR_THROTTLE = Duration.ofSeconds(1);

    private final PluginConfig.ActivitySettings settings;
    private final Clock clock;
    private final @Nullable Logger logger;
    private final List<ActivityObserver> observers = new CopyOnWriteArrayList<>();
    private final Map<UUID, PlayerActivity> players = new HashMap<>();

    public ActivityTracker(PluginConfig.ActivitySettings settings, Clock clock) {
        this(settings, clock, null);
    }

    public ActivityTracker(PluginConfig.ActivitySettings settings, Clock clock, @Nullable Logger logger) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = logger;
    }

    @Override
    public void addObserver(ActivityObserver observer) {
        Objects.requireNonNull(observer, "observer");
        if (!observers.contains(observer)) {
            observers.add(observer);
        }
    }

    public void removeObserver(ActivityObserver observer) {
        observers.remove(observer);
    }

    @Override
    public void record(UUID player, ActivityType type, Instant at) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(at, "at");
        boolean relevant = settings.isRelevant(type);
        if (!relevant && !settings.isIndicator(type)) {
            return;
        }
        PlayerActivity state = players.get(player);
        if (state == null) {
            // erste Eingabe ohne vorheriges start(): wie start() behandeln
            state = new PlayerActivity(at);
            players.put(player, state);
        }
        boolean wasAfk = state.afk;
        state.input(at);
        state.count(type);
        if (wasAfk) {
            state.afk = false;
            state.lastIndicatorNotice.clear();
            notifyObservers("onActive", observer -> observer.onActive(player, at));
        }
        if (relevant) {
            state.relevantAction(at);
            notifyObservers("onRelevant", observer -> observer.onRelevant(player, type, at));
        } else if (state.indicatorNoticeDue(type, at)) {
            notifyObservers("onIndicator", observer -> observer.onIndicator(player, type, at));
        }
    }

    @Override
    public void start(UUID player, Instant at) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(at, "at");
        PlayerActivity state = players.get(player);
        if (state == null) {
            players.put(player, new PlayerActivity(at));
            return;
        }
        boolean wasAfk = state.afk;
        state.input(at);
        if (wasAfk) {
            state.afk = false;
            state.lastIndicatorNotice.clear();
            notifyObservers("onActive", observer -> observer.onActive(player, at));
        }
    }

    @Override
    public void forget(UUID player) {
        players.remove(player);
    }

    @Override
    public Optional<ActivitySnapshot> snapshot(UUID player) {
        PlayerActivity state = players.get(player);
        return state == null ? Optional.empty() : Optional.of(state.snapshot(player));
    }

    /** Wie {@link #tick(Instant)} mit der aktuellen Uhrzeit. */
    public void tick() {
        tick(clock.instant());
    }

    /**
     * AFK-Prüfung (sekündlich, Hauptthread): Spieler mit {@code now - letzteEingabe >= afkTimeout} werden AFK.
     * Beobachter werden erst nach dem Durchlauf benachrichtigt, damit sie {@link #forget} aufrufen dürfen.
     */
    public void tick(Instant now) {
        Objects.requireNonNull(now, "now");
        Duration afkTimeout = settings.afkTimeout();
        List<PlayerActivityTransition> becameAfk = new ArrayList<>();
        for (Map.Entry<UUID, PlayerActivity> entry : players.entrySet()) {
            PlayerActivity state = entry.getValue();
            if (!state.afk && !now.isBefore(state.lastInput.plus(afkTimeout))) {
                state.afk = true;
                becameAfk.add(new PlayerActivityTransition(entry.getKey(), state.lastInput));
            }
        }
        for (PlayerActivityTransition transition : becameAfk) {
            notifyObservers("onAfk", observer -> observer.onAfk(transition.player(), transition.lastInputAt()));
        }
    }

    public boolean isAfk(UUID player) {
        PlayerActivity state = players.get(player);
        return state != null && state.afk;
    }

    public Set<UUID> trackedPlayers() {
        return Set.copyOf(players.keySet());
    }

    private void notifyObservers(String callback, Consumer<ActivityObserver> call) {
        for (ActivityObserver observer : observers) {
            try {
                call.accept(observer);
            } catch (RuntimeException | LinkageError e) {
                if (logger != null) {
                    logger.log(Level.WARNING, "Aktivitätsbeobachter " + observer.getClass().getName()
                            + " ist in " + callback + " fehlgeschlagen", e);
                }
            }
        }
    }

    private record PlayerActivityTransition(UUID player, Instant lastInputAt) {
    }

    /** Veränderlicher Zustand eines Spielers. */
    private static final class PlayerActivity {
        Instant lastInput;
        @Nullable Instant lastRelevant;
        boolean afk;
        long relevantActions;
        final EnumMap<ActivityType, Long> counts = new EnumMap<>(ActivityType.class);
        final EnumMap<ActivityType, Instant> lastIndicatorNotice = new EnumMap<>(ActivityType.class);

        PlayerActivity(Instant startedAt) {
            this.lastInput = startedAt;
        }

        void input(Instant at) {
            if (at.isAfter(lastInput)) {
                lastInput = at;
            }
        }

        void relevantAction(Instant at) {
            relevantActions++;
            if (lastRelevant == null || at.isAfter(lastRelevant)) {
                lastRelevant = at;
            }
        }

        void count(ActivityType type) {
            counts.merge(type, 1L, Long::sum);
        }

        /** Drossel: höchstens eine Meldung je Art und {@link #INDICATOR_THROTTLE}; merkt sich die Meldung. */
        boolean indicatorNoticeDue(ActivityType type, Instant at) {
            Instant last = lastIndicatorNotice.get(type);
            if (last != null && Duration.between(last, at).compareTo(INDICATOR_THROTTLE) < 0) {
                return false;
            }
            lastIndicatorNotice.put(type, at);
            return true;
        }

        ActivitySnapshot snapshot(UUID player) {
            return new ActivitySnapshot(player, lastRelevant, lastInput, afk, relevantActions, counts);
        }
    }
}
