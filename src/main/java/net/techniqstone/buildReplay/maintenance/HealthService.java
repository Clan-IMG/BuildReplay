package net.techniqstone.buildReplay.maintenance;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.techniqstone.buildReplay.config.PluginConfig;
import net.techniqstone.buildReplay.core.TaskRunner;
import net.techniqstone.buildReplay.recording.EngineStatus;
import net.techniqstone.buildReplay.recording.RecordingStats;
import net.techniqstone.buildReplay.server.ServerIdentity;
import net.techniqstone.buildReplay.session.SessionServiceStats;
import net.techniqstone.buildReplay.simplecloud.ClientHealth;
import net.techniqstone.buildReplay.state.SharedStateHealth;
import net.techniqstone.buildReplay.storage.StoreHealth;
import org.jspecify.annotations.Nullable;

/**
 * Baut den {@link HealthReport} für {@code /buildreplay status}.
 *
 * <p>Zustände, deren Abfrage blockieren darf (MariaDB, Redis, SimpleCloud), werden alle
 * {@code resilience.health-check-seconds} auf dem I/O-Pool abgefragt und zwischengespeichert;
 * {@link #report()} liest sie nur und ergänzt die Hauptthread-Zustände (AdvancedReplay, Aufnahmen, Sitzungen)
 * live. Dadurch ist {@code report()} auf dem Hauptthread schnell und blockiert nie.
 *
 * <p>Lebenszyklus: {@link #start()} nach dem Aufbau der Komponenten, {@link #updateConfig} nach einem Reload,
 * {@link #close()} beim Deaktivieren.
 */
public final class HealthService implements AutoCloseable {

    /** Ab so vielen Prüfintervallen ohne neue Daten gilt der zwischengespeicherte Stand als veraltet. */
    static final int STALE_INTERVALS = 3;

    private final ServerIdentity identity;
    private final String pluginVersion;
    private final HealthSources sources;
    private final TaskRunner tasks;
    private final Clock clock;
    private final Logger logger;
    private final AtomicBoolean refreshing = new AtomicBoolean();

    private volatile PluginConfig config;
    private volatile @Nullable IoSnapshot snapshot;
    private @Nullable ScheduledFuture<?> schedule;
    private @Nullable Duration scheduledInterval;
    private boolean closed;

    /** Zwischengespeicherte I/O-seitige Zustände. */
    record IoSnapshot(ClientHealth simpleCloud, boolean javaApiAvailable, StoreHealth database,
                      SharedStateHealth redis, Instant at) {
    }

    public HealthService(PluginConfig config, ServerIdentity identity, String pluginVersion, HealthSources sources,
                         TaskRunner tasks, Clock clock, Logger logger) {
        this.config = Objects.requireNonNull(config, "config");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.pluginVersion = Objects.requireNonNull(pluginVersion, "pluginVersion");
        this.sources = Objects.requireNonNull(sources, "sources");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Startet die periodische Abfrage (erste sofort). */
    public synchronized void start() {
        if (closed) {
            return;
        }
        reschedule();
        refreshAsync();
    }

    public synchronized void updateConfig(PluginConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        if (schedule != null && !config.resilience().healthCheckInterval().equals(scheduledInterval)) {
            reschedule();
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (schedule != null) {
            schedule.cancel(false);
            schedule = null;
        }
    }

    /** Hauptthread: aktueller Bericht (I/O-Zustände aus dem Zwischenspeicher). */
    public HealthReport report() {
        PluginConfig current = config;
        IoSnapshot cached = snapshot;
        boolean measured = cached != null;
        IoSnapshot io = measured ? cached : placeholder(current, null);
        EngineStatus engine = read(sources.engine(), HealthService::engineUnavailable, "AdvancedReplay");
        RecordingStats recordings = read(sources.recordings(), error -> new RecordingStats(0, 0, 0, 0, 0, 0),
                "Aufnahmen");
        SessionServiceStats sessions = read(sources.sessions(),
                error -> new SessionServiceStats(0, 0, 0, 0, 0, false), "Sitzungen");

        List<String> warnings = new ArrayList<>(HealthWarnings.evaluate(current, io.simpleCloud(),
                io.javaApiAvailable(), io.database(), io.redis(), engine, recordings, sessions, measured));
        Instant now = clock.instant();
        if (measured) {
            Duration age = Duration.between(io.at(), now);
            Duration limit = current.resilience().healthCheckInterval().multipliedBy(STALE_INTERVALS);
            if (age.compareTo(limit) > 0) {
                warnings.add("Zustand von MariaDB/Redis/SimpleCloud ist " + age.toSeconds() + " s alt - "
                        + "I/O-Pool ausgelastet?");
            }
        }
        return new HealthReport(now, identity.serverName(), identity.instanceId(), pluginVersion, io.simpleCloud(),
                io.javaApiAvailable(), io.database(), io.redis(), engine, recordings, sessions, warnings);
    }

    /** Fragt die I/O-seitigen Zustände auf dem I/O-Pool neu ab. */
    public CompletableFuture<Void> refreshAsync() {
        try {
            return tasks.runIo(this::refreshNow);
        } catch (RejectedExecutionException e) {
            logger.log(Level.FINE, "Zustandsabfrage übersprungen: I/O-Pool ausgelastet");
            return CompletableFuture.failedFuture(e);
        }
    }

    /** I/O-Thread: Zustände abfragen und zwischenspeichern. */
    void refreshNow() {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        try {
            PluginConfig current = config;
            IoSnapshot previous = snapshot;
            IoSnapshot fallback = previous != null ? previous : placeholder(current, null);
            ClientHealth cloud = readIo(sources.simpleCloud(), fallback.simpleCloud(), "SimpleCloud");
            boolean javaApi;
            try {
                javaApi = sources.javaApiAvailable().getAsBoolean();
            } catch (RuntimeException e) {
                logger.log(Level.FINE, "SimpleCloud-Java-API-Zustand nicht abrufbar", e);
                javaApi = fallback.javaApiAvailable();
            }
            StoreHealth database = readIo(sources.database(), fallback.database(), "MariaDB");
            SharedStateHealth redis = readIo(sources.redis(), fallback.redis(), "Redis");
            snapshot = new IoSnapshot(cloud, javaApi, database, redis, clock.instant());
        } finally {
            refreshing.set(false);
        }
    }

    private void reschedule() {
        if (schedule != null) {
            schedule.cancel(false);
        }
        Duration interval = config.resilience().healthCheckInterval();
        long millis = Math.max(1_000L, interval.toMillis());
        try {
            schedule = tasks.scheduler().scheduleWithFixedDelay(this::refreshAsync, millis, millis,
                    TimeUnit.MILLISECONDS);
            scheduledInterval = interval;
        } catch (RejectedExecutionException e) {
            logger.log(Level.WARNING, "Zustandsprüfung konnte nicht geplant werden", e);
            schedule = null;
        }
    }

    private <T> T readIo(Supplier<T> supplier, T fallback, String component) {
        try {
            T value = supplier.get();
            return value == null ? fallback : value;
        } catch (RuntimeException e) {
            logger.log(Level.FINE, "Zustand von " + component + " nicht abrufbar", e);
            return fallback;
        }
    }

    private <T> T read(Supplier<T> supplier, Function<RuntimeException, T> fallback,
                       String component) {
        try {
            T value = supplier.get();
            return value == null ? fallback.apply(null) : value;
        } catch (RuntimeException e) {
            logger.log(Level.FINE, "Zustand von " + component + " nicht abrufbar", e);
            return fallback.apply(e);
        }
    }

    private static EngineStatus engineUnavailable(@Nullable RuntimeException error) {
        String reason = "Status nicht abrufbar" + (error == null ? "" : ": " + error.getMessage());
        return new EngineStatus(false, true, false, reason, true, 0, "?", "?", 0);
    }

    /** Platzhalter, solange noch keine Abfrage stattgefunden hat. */
    static IoSnapshot placeholder(PluginConfig config, @Nullable String error) {
        return new IoSnapshot(
                new ClientHealth(config.simpleCloud().configured(), false, null, null, error, 0, 0),
                false,
                new StoreHealth(config.database().enabled(), false, 0, 0, 0, null, error, 0),
                new SharedStateHealth(config.redis().enabled(), false, true, null, error),
                Instant.EPOCH);
    }
}
