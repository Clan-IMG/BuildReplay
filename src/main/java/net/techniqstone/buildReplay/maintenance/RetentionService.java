package net.techniqstone.buildReplay.maintenance;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.techniqstone.buildReplay.config.PluginConfig;
import net.techniqstone.buildReplay.core.TaskRunner;
import net.techniqstone.buildReplay.recording.EngineStatus;
import net.techniqstone.buildReplay.recording.ReplayEngine;
import net.techniqstone.buildReplay.recording.ReplayNames;
import net.techniqstone.buildReplay.server.ServerIdentity;
import net.techniqstone.buildReplay.state.LockToken;
import net.techniqstone.buildReplay.state.SharedEvent;
import net.techniqstone.buildReplay.state.SharedState;
import net.techniqstone.buildReplay.storage.ReplayStore;
import net.techniqstone.buildReplay.storage.StorageUnavailableException;
import net.techniqstone.buildReplay.storage.model.RecordingRow;
import net.techniqstone.buildReplay.storage.model.RecordingStatus;
import net.techniqstone.buildReplay.storage.model.SegmentRow;
import net.techniqstone.buildReplay.storage.model.SegmentStatus;
import org.jspecify.annotations.Nullable;

/**
 * Aufräumen auf <em>diesem Server</em> (nur hier liegen die AdvancedReplay-Dateien seiner Abschnitte), alle
 * {@code storage.cleanup-interval-minutes} und auf Befehl ({@link #runNow}):
 *
 * <ol>
 *   <li><b>Aufbewahrungsfrist</b> ({@code storage.retention-days}, 0 = nie wegen des Alters löschen): Abschnitte,
 *       die vor der Frist endeten → {@code engine.delete}, Status DELETED, Event {@code segment-deleted}.</li>
 *   <li><b>Pro-Spieler-Limit</b> ({@code storage.max-segments-per-player}, 0 = unbegrenzt): für die gerade
 *       anwesenden Spieler werden die ältesten Abschnitte <em>dieses</em> Servers gelöscht, bis die netzwerkweite
 *       Anzahl das Limit nicht mehr überschreitet (pro Spieler netzwerkweit gesperrt, danach wird der Store
 *       geleert, damit andere Server die neue Anzahl sehen).</li>
 *   <li><b>Verwaiste Rohaufnahmen</b> ({@value ReplayNames#RAW_PREFIX}…): nur beim Datei-Speicher (FILE), weil
 *       ein geteilter MySQL-/S3-Speicher auch Rohaufnahmen anderer Server enthält. Gelöscht wird nur, was laut
 *       MariaDB nicht mehr RECORDING/FINALIZING/RAW_PENDING ist, nicht gerade aufgenommen wird und laut
 *       Zeitstempel im Namen älter als einen Tag ist. Ist MariaDB nicht erreichbar, wird nichts gelöscht.</li>
 * </ol>
 *
 * {@code dryRun} zählt nur. Ein Lauf gleichzeitig (lokal und per Redis-Sperre pro Servername).
 */
public final class RetentionService implements AutoCloseable {

    static final Duration ORPHAN_MIN_AGE = Duration.ofDays(1);
    static final int SEGMENT_SCAN_LIMIT = 2_000;
    static final int PLAYER_SCAN_LIMIT = 500;
    static final int RECORDING_SCAN_LIMIT = 10_000;
    static final Duration RUN_LEASE = Duration.ofMinutes(15);
    static final Duration PLAYER_LEASE = Duration.ofMinutes(2);
    static final Duration INITIAL_DELAY = Duration.ofMinutes(2);
    static final Duration FLUSH_WAIT = Duration.ofSeconds(10);
    static final String FILE_BACKEND = "FILE";
    /** Zeitstempel in Rohnamen vor diesem Datum gelten als unplausibel (kein BuildReplay-Name). */
    private static final Instant PLAUSIBLE_SINCE = Instant.parse("2024-01-01T00:00:00Z");
    private static final Duration MAIN_THREAD_TIMEOUT = Duration.ofSeconds(60);

    private final ReplayStore store;
    private final SharedState shared;
    private final ReplayEngine engine;
    private final ServerIdentity identity;
    private final TaskRunner tasks;
    private final Clock clock;
    private final Logger logger;
    private final Supplier<? extends Collection<UUID>> localPlayers;
    private final AtomicBoolean running = new AtomicBoolean();

    private volatile PluginConfig config;
    private @Nullable ScheduledFuture<?> schedule;
    private @Nullable Duration scheduledInterval;
    private volatile boolean closed;

    /** Vom Hauptthread gelesener Zustand. */
    private record MainSnapshot(boolean engineAvailable, String storageBackend, List<UUID> players,
                                Set<String> activeRaw) {
    }

    /** Zähler eines Laufs. */
    private static final class Counter {
        private int examined;
        private int deleted;
        private int failed;

        RetentionResult result(boolean dryRun) {
            return new RetentionResult(examined, deleted, failed, dryRun);
        }
    }

    /**
     * @param localPlayers auf dem Hauptthread aufgerufen: UUIDs der Spieler auf diesem Server (für das
     *                     Pro-Spieler-Limit), z. B. {@code () -> Bukkit.getOnlinePlayers().stream().map(Player::getUniqueId).toList()}
     */
    public RetentionService(PluginConfig config, ReplayStore store, SharedState shared, ReplayEngine engine,
                            ServerIdentity identity, TaskRunner tasks, Clock clock, Logger logger,
                            Supplier<? extends Collection<UUID>> localPlayers) {
        this.config = Objects.requireNonNull(config, "config");
        this.store = Objects.requireNonNull(store, "store");
        this.shared = Objects.requireNonNull(shared, "shared");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.localPlayers = Objects.requireNonNull(localPlayers, "localPlayers");
    }

    /** Plant den periodischen Lauf (erster nach {@link #INITIAL_DELAY}, dann alle {@code cleanup-interval}). */
    public synchronized void start() {
        if (!closed) {
            reschedule(INITIAL_DELAY);
        }
    }

    public synchronized void updateConfig(PluginConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        Duration interval = config.storage().cleanupInterval();
        if (schedule != null && !interval.equals(scheduledInterval)) {
            reschedule(interval);
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

    /**
     * Sofortiger Lauf (für {@code /buildreplay admin cleanup}). Schlägt fehl mit {@link MaintenanceException}, wenn
     * bereits ein Lauf aktiv ist oder AdvancedReplay nicht verfügbar ist, mit {@link StorageUnavailableException},
     * wenn MariaDB deaktiviert bzw. nicht erreichbar ist. Ergebnis auf einem I/O-Thread.
     */
    public CompletableFuture<RetentionResult> runNow(boolean dryRun) {
        return run(dryRun, true);
    }

    CompletableFuture<RetentionResult> run(boolean dryRun, boolean manual) {
        if (closed) {
            return CompletableFuture.failedFuture(new MaintenanceException("BuildReplay wird gerade beendet."));
        }
        if (!running.compareAndSet(false, true)) {
            return manual
                    ? CompletableFuture.failedFuture(new MaintenanceException("Aufräumen läuft bereits."))
                    : CompletableFuture.completedFuture(new RetentionResult(0, 0, 0, dryRun));
        }
        CompletableFuture<RetentionResult> result;
        try {
            result = onMain(this::snapshot).thenApplyAsync(snapshot -> execute(snapshot, dryRun, manual), tasks.io());
        } catch (RuntimeException e) {
            result = CompletableFuture.failedFuture(e);
        }
        return result.whenComplete((ignored, error) -> running.set(false));
    }

    private void scheduledRun() {
        run(false, false).whenComplete((result, error) -> {
            if (error != null) {
                Throwable cause = unwrap(error);
                if (cause instanceof StorageUnavailableException) {
                    logger.warning("Aufräumen übersprungen: MariaDB nicht erreichbar (" + cause.getMessage() + ")");
                } else {
                    logger.log(Level.WARNING, "Aufräumen fehlgeschlagen", cause);
                }
                return;
            }
            if (result.deleted() > 0 || result.failed() > 0) {
                logger.info("Aufräumen: " + result.deleted() + " Replay(s) gelöscht, " + result.failed()
                        + " fehlgeschlagen (" + result.examined() + " geprüft)");
            }
        });
    }

    private void reschedule(Duration initialDelay) {
        if (schedule != null) {
            schedule.cancel(false);
        }
        Duration interval = config.storage().cleanupInterval();
        try {
            schedule = tasks.scheduler().scheduleWithFixedDelay(this::scheduledRun,
                    Math.max(1_000L, initialDelay.toMillis()), Math.max(60_000L, interval.toMillis()),
                    TimeUnit.MILLISECONDS);
            scheduledInterval = interval;
        } catch (RejectedExecutionException e) {
            logger.log(Level.WARNING, "Aufräumen konnte nicht geplant werden", e);
            schedule = null;
        }
    }

    // ------------------------------------------------------------------ Lauf

    /** Hauptthread. */
    private MainSnapshot snapshot() {
        boolean available;
        String backend;
        Set<String> activeRaw;
        try {
            EngineStatus status = engine.status();
            available = status.available();
            backend = String.valueOf(engine.storageBackend());
            activeRaw = Set.copyOf(engine.activeRawRecordings());
        } catch (RuntimeException e) {
            logger.log(Level.FINE, "AdvancedReplay-Zustand nicht abrufbar", e);
            available = false;
            backend = "?";
            activeRaw = Set.of();
        }
        List<UUID> players;
        try {
            Collection<UUID> online = localPlayers.get();
            players = online == null ? List.of() : List.copyOf(new LinkedHashSet<>(online));
        } catch (RuntimeException e) {
            logger.log(Level.FINE, "Lokale Spieler nicht abrufbar", e);
            players = List.of();
        }
        return new MainSnapshot(available, backend, players, activeRaw);
    }

    /** I/O-Thread. */
    private RetentionResult execute(MainSnapshot snapshot, boolean dryRun, boolean manual) {
        String server = identity.serverName();
        if (!store.isEnabled()) {
            if (manual) {
                throw new StorageUnavailableException("MariaDB ist deaktiviert");
            }
            return new RetentionResult(0, 0, 0, dryRun);
        }
        if (!snapshot.engineAvailable()) {
            if (manual) {
                throw new MaintenanceException("AdvancedReplay ist nicht verfügbar - Aufräumen nicht möglich.");
            }
            logger.fine("Aufräumen übersprungen: AdvancedReplay nicht verfügbar");
            return new RetentionResult(0, 0, 0, dryRun);
        }
        Optional<LockToken> lock = shared.tryLock("retention:" + server, RUN_LEASE);
        if (lock.isEmpty()) {
            if (manual) {
                throw new MaintenanceException("Aufräumen läuft bereits auf einer anderen Instanz von " + server + ".");
            }
            return new RetentionResult(0, 0, 0, dryRun);
        }
        try {
            PluginConfig current = config;
            Instant now = clock.instant();
            Counter counter = new Counter();
            Map<String, UUID> handled = new HashMap<>();
            if (current.storage().retentionDays() > 0) {
                expireOld(server, now, current.storage().retentionDays(), dryRun, counter, handled);
            }
            int limit = current.storage().maxSegmentsPerPlayer();
            if (limit > 0 && !snapshot.players().isEmpty()) {
                // Gelöschte Abschnitte müssen in der Zählung fehlen: nach echtem Löschen den Store leeren; im
                // Probelauf (oder wenn das nicht klappt) die schon erfassten Abschnitte selbst abziehen.
                boolean counted = !dryRun && (counter.deleted == 0 || awaitFlush());
                for (UUID player : snapshot.players()) {
                    enforcePlayerLimit(player, server, limit, dryRun, !counted, counter, handled);
                }
            }
            cleanOrphanedRaw(snapshot, server, now, dryRun, counter);
            return counter.result(dryRun);
        } finally {
            try {
                shared.unlock(lock.get());
            } catch (RuntimeException e) {
                logger.log(Level.FINE, "Aufräum-Sperre konnte nicht freigegeben werden", e);
            }
        }
    }

    private void expireOld(String server, Instant now, int retentionDays, boolean dryRun, Counter counter,
                           Map<String, UUID> handled) {
        Instant cutoff = now.minus(Duration.ofDays(retentionDays));
        String note = "Aufbewahrungsfrist (" + retentionDays + " Tage) abgelaufen";
        for (SegmentRow segment : store.segmentsForRetention(server, cutoff, SEGMENT_SCAN_LIMIT)) {
            if (segment.status() == SegmentStatus.DELETED || handled.containsKey(segment.replayName())) {
                continue;
            }
            handled.put(segment.replayName(), segment.playerUuid());
            deleteSegment(segment, note, dryRun, counter);
        }
    }

    private void enforcePlayerLimit(UUID player, String server, int limit, boolean dryRun,
                                    boolean subtractHandled, Counter counter, Map<String, UUID> handled) {
        long alreadyHandled = subtractHandled ? handled.values().stream().filter(player::equals).count() : 0;
        if (store.countSegmentsOfPlayer(player) - alreadyHandled <= limit) {
            return;
        }
        Optional<LockToken> lock = shared.tryLock("retention:player:" + player, PLAYER_LEASE);
        if (lock.isEmpty()) {
            return;
        }
        try {
            // unter der Sperre neu zählen: ein anderer Server hat womöglich gerade gelöscht
            long excess = store.countSegmentsOfPlayer(player) - alreadyHandled - limit;
            if (excess <= 0) {
                return;
            }
            long handledOfPlayer = handled.values().stream().filter(player::equals).count();
            int fetch = (int) Math.min(PLAYER_SCAN_LIMIT, excess + handledOfPlayer);
            String note = "Limit von " + limit + " Abschnitten pro Spieler überschritten";
            int removed = 0;
            for (SegmentRow segment : store.oldestSegmentsOfPlayer(player, server, fetch)) {
                if (removed >= excess) {
                    break;
                }
                if (segment.status() == SegmentStatus.DELETED || handled.containsKey(segment.replayName())) {
                    continue;
                }
                handled.put(segment.replayName(), player);
                if (deleteSegment(segment, note, dryRun, counter)) {
                    removed++;
                }
            }
            if (!dryRun && removed > 0) {
                awaitFlush();
            }
        } finally {
            try {
                shared.unlock(lock.get());
            } catch (RuntimeException e) {
                logger.log(Level.FINE, "Spieler-Sperre konnte nicht freigegeben werden", e);
            }
        }
    }

    /** @return ob gelöscht (bzw. im Probelauf: gelöscht würde) */
    private boolean deleteSegment(SegmentRow segment, String note, boolean dryRun, Counter counter) {
        counter.examined++;
        if (dryRun) {
            counter.deleted++;
            return true;
        }
        try {
            engine.delete(segment.replayName());
        } catch (RuntimeException e) {
            counter.failed++;
            logger.log(Level.WARNING, "Replay " + segment.replayName() + " konnte nicht gelöscht werden: "
                    + e.getMessage());
            return false;
        }
        store.updateSegmentStatus(segment.replayName(), SegmentStatus.DELETED, note);
        try {
            shared.publish(new SharedEvent(SharedEvent.SEGMENT_DELETED, identity.instanceId(), Map.of(
                    "segmentId", String.valueOf(segment.id()),
                    "replayName", segment.replayName(),
                    "sessionId", String.valueOf(segment.sessionId()),
                    "player", segment.playerUuid().toString(),
                    "server", segment.serverName(),
                    "reason", note)));
        } catch (RuntimeException e) {
            logger.log(Level.FINE, "segment-deleted konnte nicht veröffentlicht werden", e);
        }
        counter.deleted++;
        return true;
    }

    private void cleanOrphanedRaw(MainSnapshot snapshot, String server, Instant now, boolean dryRun,
                                  Counter counter) {
        if (!FILE_BACKEND.equalsIgnoreCase(snapshot.storageBackend())) {
            logger.fine("Verwaiste Rohaufnahmen werden nur beim Datei-Speicher aufgeräumt (aktiv: "
                    + snapshot.storageBackend() + ")");
            return;
        }
        List<String> raw = engine.storedReplayNames().stream().filter(ReplayNames::isRaw).toList();
        if (raw.isEmpty()) {
            return;
        }
        List<RecordingRow> open = store.recordingsByStatus(server, EnumSet.of(RecordingStatus.RECORDING,
                RecordingStatus.FINALIZING, RecordingStatus.RAW_PENDING), RECORDING_SCAN_LIMIT);
        if (open.size() >= RECORDING_SCAN_LIMIT) {
            logger.warning("Mehr als " + RECORDING_SCAN_LIMIT + " offene Aufnahmen - verwaiste Rohaufnahmen werden "
                    + "sicherheitshalber nicht gelöscht");
            return;
        }
        Set<String> keep = new HashSet<>(snapshot.activeRaw());
        open.forEach(row -> keep.add(row.rawName()));
        Instant cutoff = now.minus(ORPHAN_MIN_AGE);
        for (String name : raw) {
            if (keep.contains(name)) {
                continue;
            }
            Optional<Instant> created = rawNameTimestamp(name);
            if (created.isEmpty() || !created.get().isBefore(cutoff)) {
                continue;
            }
            counter.examined++;
            if (dryRun) {
                counter.deleted++;
                continue;
            }
            try {
                engine.delete(name);
                counter.deleted++;
            } catch (RuntimeException e) {
                counter.failed++;
                logger.log(Level.WARNING, "Verwaiste Rohaufnahme " + name + " konnte nicht gelöscht werden: "
                        + e.getMessage());
            }
        }
    }

    /** Wartet, bis eingereihte Schreibvorgänge ausgeführt sind; {@code false} bei Zeitüberschreitung/Fehler. */
    private boolean awaitFlush() {
        try {
            store.flush().get(FLUSH_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            logger.log(Level.FINE, "Store-Flush nach dem Löschen nicht abgeschlossen", e);
            return false;
        }
    }

    /**
     * Erstellungszeit aus einem Rohnamen ({@code brraw-<millis base36>-<zufall>}, siehe {@link ReplayNames}); leer,
     * wenn der Name nicht diesem Schema folgt oder der Zeitpunkt unplausibel ist.
     */
    static Optional<Instant> rawNameTimestamp(String rawName) {
        if (!ReplayNames.isRaw(rawName)) {
            return Optional.empty();
        }
        String rest = rawName.substring(ReplayNames.RAW_PREFIX.length());
        int dash = rest.indexOf('-');
        String stamp = dash < 0 ? rest : rest.substring(0, dash);
        if (stamp.isEmpty() || stamp.length() > 12) {
            return Optional.empty();
        }
        try {
            Instant created = Instant.ofEpochMilli(Long.parseLong(stamp, 36));
            return created.isBefore(PLAUSIBLE_SINCE) ? Optional.empty() : Optional.of(created);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private <T> CompletableFuture<T> onMain(Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        tasks.runOnMain(() -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future.orTimeout(MAIN_THREAD_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
