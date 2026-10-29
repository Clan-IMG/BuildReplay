package net.techniqstone.buildReplay.maintenance;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.techniqstone.buildReplay.core.TaskRunner;
import net.techniqstone.buildReplay.recording.RecordingRecovery;
import net.techniqstone.buildReplay.recording.ReplayEngine;
import net.techniqstone.buildReplay.server.ServerIdentity;
import net.techniqstone.buildReplay.storage.ReplayStore;
import net.techniqstone.buildReplay.storage.StorageUnavailableException;
import net.techniqstone.buildReplay.storage.model.RecordingRow;
import net.techniqstone.buildReplay.storage.model.RecordingStatus;
import net.techniqstone.buildReplay.storage.model.SegmentRow;
import net.techniqstone.buildReplay.storage.model.SegmentStatus;
import net.techniqstone.buildReplay.storage.model.VisitEndReason;
import net.techniqstone.buildReplay.storage.model.VisitRow;
import org.jspecify.annotations.Nullable;

/**
 * Räumt beim Start auf, was eine vorherige Instanz <em>dieses Servers</em> (gleicher {@code serverName}, andere
 * {@code instanceId}) bei einem Absturz oder harten Stopp hinterlassen hat:
 *
 * <ol>
 *   <li>offene Aufenthalte → {@code endVisit(RECOVERED)} (Ende = Zeitpunkt der Wiederherstellung, der genaue
 *       Absturzzeitpunkt ist unbekannt),</li>
 *   <li>Aufnahmen in RECORDING/FINALIZING → INTERRUPTED ("Instanz während der Aufnahme beendet"),</li>
 *   <li>Rohaufnahmen in RAW_PENDING → {@link RecordingRecovery#recoverPending},</li>
 *   <li>Abschnitte, die seit über 10 Minuten in SAVING hängen → AVAILABLE, wenn das Replay im AdvancedReplay-Speicher
 *       existiert, sonst FAILED.</li>
 * </ol>
 *
 * Einträge der laufenden Instanz werden nie angefasst. Alle Schritte sind idempotent; ist MariaDB beim Start nicht
 * erreichbar, wird die Wiederherstellung jede Minute erneut versucht (höchstens {@value #MAX_ATTEMPTS}-mal).
 * {@link #start()} nach {@code ReplayEngine#install} aufrufen; {@link #close()} beim Deaktivieren.
 */
public final class RecoveryService implements AutoCloseable {

    static final Duration SAVING_STALE_AFTER = Duration.ofMinutes(10);
    static final String INTERRUPTED_ERROR = "Instanz während der Aufnahme beendet";
    static final String NOTE_AVAILABLE = "Nach Neustart geprüft: Replay vorhanden";
    static final String NOTE_FAILED = "Speichern nicht abgeschlossen (Instanz beendet)";
    static final int SCAN_LIMIT = 10_000;
    static final Duration RETRY_DELAY = Duration.ofMinutes(1);
    static final int MAX_ATTEMPTS = 30;
    private static final Duration MAIN_THREAD_TIMEOUT = Duration.ofSeconds(60);

    private final ReplayStore store;
    private final ReplayEngine engine;
    private final RecordingRecovery recovery;
    private final ServerIdentity identity;
    private final TaskRunner tasks;
    private final Clock clock;
    private final Logger logger;
    private final CompletableFuture<RecoveryResult> done = new CompletableFuture<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile boolean closed;
    private volatile @Nullable ScheduledFuture<?> retry;

    /** Was vom Hauptthread über AdvancedReplay bekannt sein muss. */
    private record EngineInfo(boolean available, String storageBackend) {
    }

    public RecoveryService(ReplayStore store, ReplayEngine engine, RecordingRecovery recovery,
                           ServerIdentity identity, TaskRunner tasks, Clock clock, Logger logger) {
        this.store = Objects.requireNonNull(store, "store");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.recovery = Objects.requireNonNull(recovery, "recovery");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Startet die Wiederherstellung asynchron (idempotent, beliebiger Thread). Das Future schließt auf einem
     * I/O-Thread ab, sobald ein Durchlauf erfolgreich war, bzw. scheitert, wenn alle Versuche fehlschlugen.
     */
    public CompletableFuture<RecoveryResult> start() {
        if (started.compareAndSet(false, true)) {
            attempt(1);
        }
        return done;
    }

    @Override
    public void close() {
        closed = true;
        ScheduledFuture<?> pending = retry;
        if (pending != null) {
            pending.cancel(false);
        }
        if (!done.isDone()) {
            done.completeExceptionally(new MaintenanceException("Wiederherstellung abgebrochen (Plugin wird beendet)."));
        }
    }

    private void attempt(int attempt) {
        if (closed) {
            return;
        }
        CompletableFuture<RecoveryResult> run;
        try {
            run = onMain(this::engineInfo).thenComposeAsync(this::runSteps, tasks.io());
        } catch (RejectedExecutionException e) {
            run = CompletableFuture.failedFuture(e);
        }
        run.whenComplete((result, error) -> {
            if (error == null) {
                log(result);
                done.complete(result);
                return;
            }
            Throwable cause = unwrap(error);
            boolean retryable = cause instanceof StorageUnavailableException || cause instanceof RejectedExecutionException;
            if (retryable && attempt < MAX_ATTEMPTS && !closed) {
                logger.log(attempt == 1 ? Level.WARNING : Level.FINE, "Wiederherstellung nach Neustart verschoben ("
                        + cause.getMessage() + ") - neuer Versuch in " + RETRY_DELAY.toSeconds() + " s");
                scheduleRetry(attempt + 1);
                return;
            }
            logger.log(Level.SEVERE, "Wiederherstellung nach Neustart fehlgeschlagen", cause);
            done.completeExceptionally(cause);
        });
    }

    private void scheduleRetry(int nextAttempt) {
        try {
            retry = tasks.scheduler().schedule(() -> attempt(nextAttempt), RETRY_DELAY.toMillis(),
                    TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            done.completeExceptionally(e);
        }
    }

    /** Hauptthread: AdvancedReplay-Zustand (Fehler = nicht verfügbar). */
    private EngineInfo engineInfo() {
        try {
            return new EngineInfo(engine.status().available(), String.valueOf(engine.storageBackend()));
        } catch (RuntimeException e) {
            logger.log(Level.FINE, "AdvancedReplay-Zustand nicht abrufbar", e);
            return new EngineInfo(false, "?");
        }
    }

    /** I/O-Thread. */
    private CompletableFuture<RecoveryResult> runSteps(EngineInfo engineInfo) {
        if (!store.isEnabled()) {
            logger.fine("Wiederherstellung übersprungen: MariaDB ist deaktiviert");
            return CompletableFuture.completedFuture(RecoveryResult.skippedResult());
        }
        Instant now = clock.instant();
        String server = identity.serverName();

        int visitsClosed = closeOpenVisits(server, now);
        int interrupted = interruptRecordings(server, now);

        List<RecordingRow> pending = store.recordingsByStatus(server, EnumSet.of(RecordingStatus.RAW_PENDING),
                SCAN_LIMIT);
        CompletableFuture<Integer> recovered;
        if (pending.isEmpty()) {
            recovered = CompletableFuture.completedFuture(0);
        } else if (!engineInfo.available()) {
            logger.warning(pending.size() + " Rohaufnahme(n) warten auf das Zerlegen, AdvancedReplay ist aber nicht "
                    + "verfügbar - nächster Versuch beim nächsten Start");
            recovered = CompletableFuture.completedFuture(0);
        } else {
            recovered = safeRecover(pending);
        }
        return recovered.thenApplyAsync(count -> {
            int[] saving = resolveStaleSaving(server, now, engineInfo);
            return new RecoveryResult(visitsClosed, interrupted, pending.size(), count, saving[0], saving[1], false);
        }, tasks.io());
    }

    private int closeOpenVisits(String server, Instant now) {
        int closedCount = 0;
        for (VisitRow visit : store.openVisits(server)) {
            if (identity.instanceId().equals(visit.instanceId())) {
                continue;
            }
            Instant leftAt = visit.joinedAt().isAfter(now) ? visit.joinedAt() : now;
            store.endVisit(visit.visitKey(), leftAt, VisitEndReason.RECOVERED);
            closedCount++;
        }
        return closedCount;
    }

    private int interruptRecordings(String server, Instant now) {
        int count = 0;
        for (RecordingRow row : store.recordingsByStatus(server,
                EnumSet.of(RecordingStatus.RECORDING, RecordingStatus.FINALIZING), SCAN_LIMIT)) {
            if (identity.instanceId().equals(row.instanceId())) {
                continue;
            }
            store.finishRecording(row.rawName(), RecordingStatus.INTERRUPTED, now, row.relevantActions(),
                    INTERRUPTED_ERROR);
            count++;
        }
        return count;
    }

    private CompletableFuture<Integer> safeRecover(List<RecordingRow> pending) {
        CompletableFuture<Integer> future;
        try {
            future = Objects.requireNonNull(recovery.recoverPending(pending), "recoverPending lieferte kein Future");
        } catch (RuntimeException e) {
            future = CompletableFuture.failedFuture(e);
        }
        return future.handle((count, error) -> {
            if (error != null) {
                logger.log(Level.WARNING, "Zerlegen ausstehender Rohaufnahmen fehlgeschlagen", unwrap(error));
                return 0;
            }
            return count == null ? 0 : count;
        });
    }

    /** I/O-Thread: {available, failed}. */
    private int[] resolveStaleSaving(String server, Instant now, EngineInfo engineInfo) {
        int[] counts = {0, 0};
        if (!engineInfo.available()) {
            return counts;
        }
        Instant staleBefore = now.minus(SAVING_STALE_AFTER);
        // Es gibt keine Abfrage nach Status: segmentsForRetention liefert alle nicht gelöschten Abschnitte dieses
        // Servers, die vor staleBefore endeten (endedAt <= createdAt, die Menge enthält also alle hängenden).
        for (SegmentRow segment : store.segmentsForRetention(server, staleBefore, SCAN_LIMIT)) {
            if (segment.status() != SegmentStatus.SAVING || !segment.createdAt().isBefore(staleBefore)) {
                continue;
            }
            if (!segment.storageBackend().equalsIgnoreCase(engineInfo.storageBackend())) {
                logger.fine("Abschnitt " + segment.replayName() + " liegt im Speicher " + segment.storageBackend()
                        + ", aktiv ist " + engineInfo.storageBackend() + " - Status bleibt SAVING");
                continue;
            }
            boolean exists;
            try {
                exists = engine.exists(segment.replayName());
            } catch (RuntimeException e) {
                logger.log(Level.FINE, "Existenzprüfung für " + segment.replayName() + " fehlgeschlagen", e);
                continue;
            }
            if (exists) {
                store.updateSegmentStatus(segment.replayName(), SegmentStatus.AVAILABLE, NOTE_AVAILABLE);
                counts[0]++;
            } else {
                store.updateSegmentStatus(segment.replayName(), SegmentStatus.FAILED, NOTE_FAILED);
                counts[1]++;
            }
        }
        return counts;
    }

    private void log(RecoveryResult result) {
        if (result.skipped()) {
            return;
        }
        String summary = "Wiederherstellung nach Neustart: " + result.visitsClosed() + " Aufenthalt(e) geschlossen, "
                + result.recordingsInterrupted() + " Aufnahme(n) als unterbrochen markiert, "
                + result.pendingRecovered() + "/" + result.pendingFound() + " ausstehende Rohaufnahme(n) zerlegt, "
                + result.savingAvailable() + " Abschnitt(e) verfügbar, " + result.savingFailed()
                + " Abschnitt(e) fehlgeschlagen";
        logger.log(result.changedAnything() || result.pendingFound() > 0 ? Level.INFO : Level.FINE, summary);
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
