package net.techniqstone.buildReplay.maintenance;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.techniqstone.buildReplay.config.PluginConfig;
import net.techniqstone.buildReplay.core.TaskRunner;
import net.techniqstone.buildReplay.server.ServerIdentity;
import net.techniqstone.buildReplay.state.InstanceInfo;
import net.techniqstone.buildReplay.state.SharedState;
import org.jspecify.annotations.Nullable;

/**
 * Herzschlag dieser Instanz (alle {@code resilience.heartbeat-seconds}): {@link SharedState#heartbeat} mit
 * {@link InstanceInfo} (TTL = 3 Intervalle), sichtbar über {@link SharedState#liveInstances()}.
 *
 * <p>Die Präsenz der Spieler frischt bewusst nur der Sitzungsdienst auf: er reiht {@code markPresent} und
 * {@code markLeft} je Spieler in fester Reihenfolge ein. Ein zweiter, unabhängiger Schreiber könnte einen gerade
 * gegangenen Spieler wieder als anwesend melden.
 *
 * <p>Lebenszyklus: {@link #start()}, {@link #updateConfig}, {@link #close()}. Redis wird nur auf dem I/O-Pool
 * angesprochen.
 */
public final class InstanceHeartbeat implements AutoCloseable {

    static final int TTL_INTERVALS = 3;

    private final SharedState shared;
    private final ServerIdentity identity;
    private final String pluginVersion;
    private final TaskRunner tasks;
    private final Clock clock;
    private final Logger logger;
    private final Instant startedAt;
    private final AtomicBoolean beating = new AtomicBoolean();

    private volatile PluginConfig config;
    private @Nullable ScheduledFuture<?> schedule;
    private @Nullable Duration scheduledInterval;
    private volatile boolean closed;

    public InstanceHeartbeat(PluginConfig config, SharedState shared, ServerIdentity identity, String pluginVersion,
                             TaskRunner tasks, Clock clock, Logger logger) {
        this.config = Objects.requireNonNull(config, "config");
        this.shared = Objects.requireNonNull(shared, "shared");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.pluginVersion = Objects.requireNonNull(pluginVersion, "pluginVersion");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.startedAt = clock.instant();
    }

    public Instant startedAt() {
        return startedAt;
    }

    /** Erster Herzschlag sofort, danach periodisch. */
    public synchronized void start() {
        if (closed) {
            return;
        }
        reschedule();
        beat();
    }

    public synchronized void updateConfig(PluginConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        if (schedule != null && !config.resilience().heartbeatInterval().equals(scheduledInterval)) {
            reschedule();
        }
    }

    /** Beendet den Herzschlag (die Instanz verschwindet nach Ablauf der TTL aus {@code liveInstances}). */
    @Override
    public synchronized void close() {
        closed = true;
        if (schedule != null) {
            schedule.cancel(false);
            schedule = null;
        }
    }

    /** Ein Herzschlag auf dem I/O-Pool. */
    public CompletableFuture<Void> beat() {
        if (closed || !beating.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> result;
        try {
            result = tasks.runIo(this::write);
        } catch (RejectedExecutionException e) {
            result = CompletableFuture.failedFuture(e);
        }
        return result.whenComplete((ignored, error) -> {
            beating.set(false);
            if (error != null) {
                logger.log(Level.FINE, "Herzschlag fehlgeschlagen", error);
            }
        });
    }

    /** I/O-Thread. */
    private void write() {
        Duration interval = config.resilience().heartbeatInterval();
        shared.heartbeat(new InstanceInfo(identity.instanceId(), identity.serverName(), pluginVersion, startedAt,
                clock.instant()), interval.multipliedBy(TTL_INTERVALS));
    }

    private void reschedule() {
        if (schedule != null) {
            schedule.cancel(false);
        }
        Duration interval = config.resilience().heartbeatInterval();
        long millis = Math.max(1_000L, interval.toMillis());
        try {
            schedule = tasks.scheduler().scheduleWithFixedDelay(this::beat, millis, millis, TimeUnit.MILLISECONDS);
            scheduledInterval = interval;
        } catch (RejectedExecutionException e) {
            logger.log(Level.WARNING, "Herzschlag konnte nicht geplant werden", e);
            schedule = null;
        }
    }
}
