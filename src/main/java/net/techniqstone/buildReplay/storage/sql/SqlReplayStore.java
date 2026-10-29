package net.techniqstone.buildReplay.storage.sql;

import com.google.gson.Gson;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.techniqstone.buildReplay.config.PluginConfig;
import net.techniqstone.buildReplay.storage.ReplayStore;
import net.techniqstone.buildReplay.storage.StorageUnavailableException;
import net.techniqstone.buildReplay.storage.StoreHealth;
import net.techniqstone.buildReplay.storage.model.RecordingRow;
import net.techniqstone.buildReplay.storage.model.RecordingStatus;
import net.techniqstone.buildReplay.storage.model.RecordingUpsert;
import net.techniqstone.buildReplay.storage.model.SegmentRow;
import net.techniqstone.buildReplay.storage.model.SegmentStatus;
import net.techniqstone.buildReplay.storage.model.SegmentUpsert;
import net.techniqstone.buildReplay.storage.model.SessionRow;
import net.techniqstone.buildReplay.storage.model.SessionStats;
import net.techniqstone.buildReplay.storage.model.SessionUpsert;
import net.techniqstone.buildReplay.storage.model.VisitEndReason;
import net.techniqstone.buildReplay.storage.model.VisitRow;
import net.techniqstone.buildReplay.storage.model.VisitStart;
import org.jspecify.annotations.Nullable;

/**
 * {@link ReplayStore} auf MariaDB (HikariCP).
 *
 * <p><b>Lebenszyklus</b>: der Konstruktor macht keine Ein-/Ausgabe. {@link #start()} lädt die Outbox-Datei, legt
 * den Pool an und unternimmt genau einen Verbindungs- und Migrationsversuch, auf den höchstens
 * {@code connection-timeout} (+2 s) gewartet wird. Scheitert er, wird nur gewarnt; der Schreib-Thread verbindet sich
 * im Hintergrund mit exponentiellem Backoff (bis 60 s) neu. {@link #close()} nimmt keine Schreibvorgänge mehr an,
 * arbeitet die Warteschlange höchstens ~5 s ab, sichert den Rest in die Outbox und schließt den Pool.
 *
 * <p><b>Schreiben</b>: alle Schreibmethoden reihen nur ein (sicher vom Hauptthread aus). Genau ein Thread
 * ("BuildReplay-DB-Writer") führt die Vorgänge in Reihenfolge aus, jeden in einer eigenen Transaktion (READ
 * COMMITTED); Deadlocks und Lock-Wait-Timeouts werden bis zu dreimal wiederholt. Ist MariaDB nicht erreichbar,
 * bleiben die Vorgänge in der Warteschlange (höchstens {@code resilience.outbox-max-entries}, darüber hinaus wird
 * verworfen und gezählt) und werden alle {@code resilience.outbox-flush-seconds} nach
 * {@code <dataFolder>/outbox/outbox.jsonl} gesichert. Beim nächsten Start werden gesicherte Vorgänge zuerst
 * ausgeführt; die Datei wird gelöscht, sobald die Warteschlange leer ist.
 *
 * <p><b>Lesen</b>: direkt auf dem aufrufenden Thread über den Pool (nie auf dem Hauptthread aufrufen). Ist MariaDB
 * deaktiviert, (noch) nicht verbunden oder schlägt die Abfrage fehl, wird {@link StorageUnavailableException}
 * geworfen; ein Lesefehler wegen fehlender Verbindung stößt einen sofortigen Wiederverbindungsversuch an.
 *
 * <p>Die übergebene {@link Clock} liefert nur die gespeicherten Zeitstempel ({@code created_at}/{@code updated_at});
 * Wartezeiten und Backoff laufen über {@link System#nanoTime()}.
 */
public final class SqlReplayStore implements ReplayStore {

    /** Zeitverhalten des Schreib-Threads; Tests verkürzen die Wartezeiten. */
    record Tuning(Duration initialBackoff, Duration maxBackoff, Duration closeDrain, int maxRetries,
                  Duration warnInterval) {
        static final Tuning DEFAULT = new Tuning(Duration.ofSeconds(1), Duration.ofSeconds(60), Duration.ofSeconds(5),
                3, Duration.ofMinutes(5));
    }

    private enum Outcome {
        /** Ausgeführt. */
        DONE,
        /** Dauerhaft fehlgeschlagen und verworfen (gezählt). */
        FAILED,
        /** Datenbank nicht erreichbar - Vorgang bleibt vorne in der Warteschlange. */
        UNAVAILABLE
    }

    private static final String POOL_NAME = "BuildReplay-DB";
    private static final String WRITER_THREAD = "BuildReplay-DB-Writer";
    private static final long OVERFLOW_WARN_NANOS = TimeUnit.SECONDS.toNanos(60);
    private static final long READ_RECONNECT_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final int DEFAULT_SOCKET_TIMEOUT_MILLIS = 60_000;
    private static final int LOCK_WAIT_TIMEOUT_SECONDS = 10;

    private final PluginConfig.DatabaseSettings settings;
    private final PluginConfig.ResilienceSettings resilience;
    private final Clock clock;
    private final Logger logger;
    private final Tuning tuning;
    private final SqlTables tables;
    private final WriteQueue queue;
    private final OutboxJournal journal;
    private final SqlWrites writes;
    private final SqlReads reads;
    private final SchemaMigrator migrator;

    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean connected = new AtomicBoolean();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong droppedSinceWarning = new AtomicLong();
    private final AtomicLong lastOverflowWarning = new AtomicLong(System.nanoTime() - OVERFLOW_WARN_NANOS);
    private final CountDownLatch firstAttempt = new CountDownLatch(1);

    private volatile @Nullable HikariDataSource dataSource;
    private volatile @Nullable Thread writer;
    private volatile boolean closing;
    private volatile boolean migrated;
    private volatile boolean everConnected;
    private volatile boolean reconnectRequested;
    private volatile int schemaVersion;
    private volatile long lastAttemptNanos = System.nanoTime() - READ_RECONNECT_NANOS;
    private volatile @Nullable Instant lastSuccess;
    private volatile @Nullable String lastError;
    private volatile long disconnectedSinceNanos = System.nanoTime();

    // nur vom Schreib-Thread benutzt
    private long backoffNanos;
    private long nextAttemptNanos;
    private long lastUnavailableWarning;
    private long lastJournalWarning;

    public SqlReplayStore(PluginConfig.DatabaseSettings settings, PluginConfig.ResilienceSettings resilience,
                          Path dataFolder, Clock clock, Logger logger) {
        this(settings, resilience, dataFolder, clock, logger, Tuning.DEFAULT);
    }

    SqlReplayStore(PluginConfig.DatabaseSettings settings, PluginConfig.ResilienceSettings resilience,
                   Path dataFolder, Clock clock, Logger logger, Tuning tuning) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.resilience = Objects.requireNonNull(resilience, "resilience");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.tuning = Objects.requireNonNull(tuning, "tuning");
        this.tables = new SqlTables(settings.tablePrefix());
        WriteOpCodec codec = new WriteOpCodec();
        Gson gson = codec.gson();
        this.queue = new WriteQueue(Math.max(1, resilience.outboxMaxEntries()));
        this.journal = new OutboxJournal(dataFolder.resolve("outbox"), codec, logger);
        this.writes = new SqlWrites(tables, gson, logger);
        this.reads = new SqlReads(tables, gson);
        this.migrator = new SchemaMigrator(tables, SqlReplayStore.class.getClassLoader(), clock, logger);
        this.backoffNanos = tuning.initialBackoff().toNanos();
    }

    // ------------------------------------------------------------------ Lebenszyklus

    /**
     * Lädt die Outbox, legt den Pool an und versucht einmal zu verbinden und zu migrieren (wartet höchstens
     * {@code connection-timeout} + 2 s). Wirft nie; Fehler werden protokolliert und im Hintergrund wiederholt.
     */
    public void start() {
        if (!started.compareAndSet(false, true) || closed.get()) {
            return;
        }
        if (!settings.enabled()) {
            logger.info("MariaDB ist deaktiviert (database.enabled: false) - Sitzungen und Replay-Abschnitte werden "
                    + "nicht dauerhaft gespeichert");
            return;
        }
        loadJournal();
        try {
            dataSource = createDataSource();
        } catch (RuntimeException e) {
            // z. B. Treiber fehlt - der Schreib-Thread versucht es erneut
            recordError(e);
        }
        Thread thread = Thread.ofPlatform().name(WRITER_THREAD).daemon(true).unstarted(this::runWriter);
        writer = thread;
        thread.start();
        long waitMillis = settings.connectionTimeout().toMillis() + 2_000;
        try {
            if (!firstAttempt.await(waitMillis, TimeUnit.MILLISECONDS)) {
                logger.warning("MariaDB-Verbindungsaufbau zu " + target() + " dauert länger als " + waitMillis
                        + " ms - er läuft im Hintergrund weiter, Schreibvorgänge werden solange gepuffert");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Nimmt keine Schreibvorgänge mehr an, arbeitet die Warteschlange höchstens ~5 s ab, sichert den Rest in die
     * Outbox und schließt den Pool. Mehrfacher Aufruf ist harmlos.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (!settings.enabled()) {
            return;
        }
        queue.close();
        Thread thread = writer;
        if (thread != null) {
            closing = true;
            queue.signal();
            long waitMillis = tuning.closeDrain().toMillis() + settings.connectionTimeout().toMillis() + 2_000;
            try {
                thread.join(waitMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (thread.isAlive()) {
                logger.warning("MariaDB-Schreib-Thread reagiert nicht - die Outbox wird ohne ihn gesichert");
            }
        }
        // Endgültiger Stand (No-op, wenn der Schreib-Thread genau diesen Stand schon geschrieben hat).
        WriteQueue.Snapshot snapshot = queue.snapshot();
        try {
            if (journal.write(snapshot.ops(), snapshot.version())) {
                queue.markJournaled(snapshot.tailSeq());
            }
            if (!snapshot.isEmpty()) {
                logger.info(snapshot.ops().size() + " Schreibvorgänge in " + journal.file()
                        + " gesichert - sie werden beim nächsten Start nachgeholt");
            }
        } catch (IOException e) {
            logger.severe(snapshot.ops().size() + " Schreibvorgänge konnten nicht in " + journal.file()
                    + " gesichert werden und gehen verloren: " + e.getMessage());
            dropped.addAndGet(snapshot.ops().size());
        }
        journal.freeze();
        queue.completeAllWaiters(null);
        HikariDataSource ds = dataSource;
        if (ds != null) {
            ds.close();
        }
        connected.set(false);
    }

    // ------------------------------------------------------------------ Zustand

    @Override
    public boolean isEnabled() {
        return settings.enabled();
    }

    @Override
    public StoreHealth health() {
        if (!settings.enabled()) {
            return new StoreHealth(false, false, 0, 0, dropped.get(), null, null, 0);
        }
        return new StoreHealth(true, connected.get(), queue.size(), journal.persistedEntries(), dropped.get(),
                lastSuccess, lastError, schemaVersion);
    }

    /** Pfad der Outbox-Datei (für Statusmeldungen und Tests). */
    Path outboxFile() {
        return journal.file();
    }

    // ------------------------------------------------------------------ Schreiben

    @Override
    public void upsertPlayer(UUID playerUuid, String name, Instant seenAt) {
        enqueue(new WriteOp.UpsertPlayer(Objects.requireNonNull(playerUuid, "playerUuid"),
                Objects.requireNonNull(name, "name"), Objects.requireNonNull(seenAt, "seenAt")));
    }

    @Override
    public void upsertSession(SessionUpsert session) {
        enqueue(new WriteOp.UpsertSession(Objects.requireNonNull(session, "session")));
    }

    @Override
    public void closeSession(String sessionKey, Instant logoutTime, @Nullable Long durationSeconds) {
        enqueue(new WriteOp.CloseSession(Objects.requireNonNull(sessionKey, "sessionKey"),
                Objects.requireNonNull(logoutTime, "logoutTime"), durationSeconds));
    }

    @Override
    public void mergeProvisionalSession(String provisionalKey, SessionUpsert target) {
        enqueue(new WriteOp.MergeProvisional(Objects.requireNonNull(provisionalKey, "provisionalKey"),
                Objects.requireNonNull(target, "target")));
    }

    @Override
    public void startVisit(VisitStart visit) {
        enqueue(new WriteOp.StartVisit(Objects.requireNonNull(visit, "visit")));
    }

    @Override
    public void endVisit(String visitKey, Instant leftAt, VisitEndReason reason) {
        enqueue(new WriteOp.EndVisit(Objects.requireNonNull(visitKey, "visitKey"),
                Objects.requireNonNull(leftAt, "leftAt"), Objects.requireNonNull(reason, "reason")));
    }

    @Override
    public void upsertRecording(RecordingUpsert recording) {
        enqueue(new WriteOp.UpsertRecording(Objects.requireNonNull(recording, "recording")));
    }

    @Override
    public void finishRecording(String rawName, RecordingStatus status, @Nullable Instant endedAt, int relevantActions,
                                @Nullable String error) {
        enqueue(new WriteOp.FinishRecording(Objects.requireNonNull(rawName, "rawName"),
                Objects.requireNonNull(status, "status"), endedAt, relevantActions, error));
    }

    @Override
    public void upsertSegment(SegmentUpsert segment) {
        enqueue(new WriteOp.UpsertSegment(Objects.requireNonNull(segment, "segment")));
    }

    @Override
    public void updateSegmentStatus(String replayName, SegmentStatus status, @Nullable String note) {
        enqueue(new WriteOp.UpdateSegmentStatus(Objects.requireNonNull(replayName, "replayName"),
                Objects.requireNonNull(status, "status"), note));
    }

    @Override
    public CompletableFuture<Void> flush() {
        if (!settings.enabled() || closed.get()) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> future = queue.flushFuture();
        if (future.isDone()) {
            return future;
        }
        if (!connected.get()) {
            requestReconnect();
        }
        queue.signal();
        // Abhängige Arbeit der Aufrufer nicht auf dem Schreib-Thread ausführen (sonst könnte sie ihn blockieren).
        return future.thenApplyAsync(ignored -> null);
    }

    private void enqueue(WriteOp op) {
        if (!settings.enabled()) {
            dropped.incrementAndGet();
            return;
        }
        String invalid = op.invalidReason();
        if (invalid != null) {
            dropped.incrementAndGet();
            logger.warning("Ungültiger Schreibvorgang verworfen (" + op.describe() + "): " + invalid);
            return;
        }
        if (closed.get()) {
            dropped.incrementAndGet();
            logger.fine("MariaDB-Anbindung ist beendet - " + op.describe() + " verworfen");
            return;
        }
        if (!queue.offer(op)) {
            dropped.incrementAndGet();
            if (queue.isClosed()) {
                return;
            }
            long sinceWarning = droppedSinceWarning.incrementAndGet();
            long now = System.nanoTime();
            long last = lastOverflowWarning.get();
            if (now - last >= OVERFLOW_WARN_NANOS && lastOverflowWarning.compareAndSet(last, now)) {
                droppedSinceWarning.addAndGet(-sinceWarning);
                logger.warning("MariaDB-Outbox voll (" + queue.capacity() + " Einträge, MariaDB "
                        + (connected.get() ? "kommt nicht hinterher" : "nicht erreichbar") + ") - " + sinceWarning
                        + " Schreibvorgang/-vorgänge verworfen (insgesamt " + dropped.get() + ")");
            }
        }
    }

    // ------------------------------------------------------------------ Schreib-Thread

    private void runWriter() {
        try {
            connectAttempt(true);
            firstAttempt.countDown();
            writerLoop();
            drainForClose();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            logger.log(Level.SEVERE, "MariaDB-Schreib-Thread unerwartet beendet - Schreibvorgänge werden nur noch "
                    + "beim Beenden in die Outbox gesichert", t);
        } finally {
            firstAttempt.countDown();
            persistJournal();
        }
    }

    private void writerLoop() throws InterruptedException {
        while (!closing) {
            try {
                writerIterations();
            } catch (RuntimeException e) {
                // Programmfehler: protokollieren und weiterarbeiten statt die Warteschlange stehen zu lassen.
                logger.log(Level.SEVERE, "Fehler im MariaDB-Schreib-Thread", e);
                queue.await(TimeUnit.SECONDS.toNanos(1));
            }
        }
    }

    private void writerIterations() throws InterruptedException {
        long journalInterval = Math.max(TimeUnit.MILLISECONDS.toNanos(100), resilience.outboxFlushInterval().toNanos());
        long nextJournal = System.nanoTime() + journalInterval;
        while (!closing) {
            if (!connected.get()) {
                long now = System.nanoTime();
                if (queue.hasWaiters() || now - nextJournal >= 0) {
                    persistJournal();
                    nextJournal = now + journalInterval;
                }
                if (reconnectRequested || now - nextAttemptNanos >= 0) {
                    reconnectRequested = false;
                    if (connectAttempt(false)) {
                        continue;
                    }
                    now = System.nanoTime();
                }
                long wait = Math.min(nextAttemptNanos - now, nextJournal - now);
                queue.await(Math.max(TimeUnit.MILLISECONDS.toNanos(1), wait));
                continue;
            }
            WriteQueue.Entry entry = queue.peek();
            if (entry == null) {
                if (journal.exists()) {
                    persistJournal(); // Warteschlange leer: Outbox-Datei löschen
                }
                queue.await(journalInterval);
                continue;
            }
            Outcome outcome = execute(entry.op());
            if (outcome == Outcome.UNAVAILABLE) {
                continue; // connected ist jetzt false; der Vorgang bleibt vorne
            }
            queue.complete(entry);
            long now = System.nanoTime();
            if (now - nextJournal >= 0) {
                if (journal.exists()) {
                    persistJournal(); // Datei nach der Wiederverbindung auf den Rest verkleinern
                }
                nextJournal = now + journalInterval;
            }
        }
    }

    /** Beim Beenden: höchstens {@code closeDrain} weiter ausführen, dann den Rest sichern (im finally). */
    private void drainForClose() throws InterruptedException {
        long deadline = System.nanoTime() + tuning.closeDrain().toNanos();
        while (connected.get() && System.nanoTime() - deadline < 0) {
            WriteQueue.Entry entry = queue.peek();
            if (entry == null) {
                return;
            }
            if (execute(entry.op()) == Outcome.UNAVAILABLE) {
                return;
            }
            queue.complete(entry);
        }
    }

    private Outcome execute(WriteOp op) throws InterruptedException {
        HikariDataSource ds = dataSource;
        if (ds == null || ds.isClosed()) {
            markDisconnected(null);
            return Outcome.UNAVAILABLE;
        }
        SQLException last = null;
        for (int attempt = 0; attempt <= tuning.maxRetries(); attempt++) {
            if (attempt > 0) {
                Thread.sleep(ThreadLocalRandom.current().nextLong(10, 40L * (attempt + 1)));
            }
            try (Connection c = ds.getConnection()) {
                inTransaction(c, op);
                lastSuccess = clock.instant();
                return Outcome.DONE;
            } catch (SQLException e) {
                last = e;
                if (ds.isClosed() || SqlErrors.isConnectionProblem(e)) {
                    markDisconnected(e);
                    return Outcome.UNAVAILABLE;
                }
                if (!SqlErrors.isRetryable(e)) {
                    break;
                }
                logger.fine("Schreibvorgang (" + op.describe() + ") wird wiederholt nach: "
                        + SqlErrors.describe(e, settings.password()));
            } catch (RuntimeException e) {
                dropped.incrementAndGet();
                recordError(e);
                logger.log(Level.SEVERE, "Unerwarteter Fehler bei " + op.describe() + " - Vorgang verworfen", e);
                return Outcome.FAILED;
            }
        }
        dropped.incrementAndGet();
        recordError(last);
        logger.warning("Schreibvorgang verworfen (" + op.describe() + "): " + SqlErrors.describe(last, settings.password()));
        return Outcome.FAILED;
    }

    private void inTransaction(Connection c, WriteOp op) throws SQLException {
        boolean autoCommit = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            writes.apply(c, op, clock.instant());
            c.commit();
        } catch (SQLException | RuntimeException e) {
            try {
                c.rollback();
            } catch (SQLException rollback) {
                e.addSuppressed(rollback);
            }
            throw e;
        } finally {
            try {
                c.setAutoCommit(autoCommit);
            } catch (SQLException ignored) {
                // Hikari verwirft defekte Verbindungen selbst
            }
        }
    }

    /**
     * Ein Verbindungsversuch inklusive Migration (Schreib-Thread). Die Migration läuft bei jeder Wiederverbindung
     * erneut (ein No-op, wenn das Schema aktuell ist), damit auch eine neu aufgesetzte oder umgeschaltete Datenbank
     * ihr Schema bekommt, bevor die Outbox eingespielt wird.
     */
    private boolean connectAttempt(boolean first) {
        lastAttemptNanos = System.nanoTime();
        boolean reachable = false;
        try {
            HikariDataSource ds = dataSource;
            if (ds == null) {
                ds = createDataSource();
                dataSource = ds;
            }
            try (Connection c = ds.getConnection()) {
                reachable = true;
                schemaVersion = migrator.migrate(c);
                migrated = true;
            }
            connected.set(true);
            backoffNanos = tuning.initialBackoff().toNanos();
            lastSuccess = clock.instant();
            if (!everConnected) {
                everConnected = true;
                logger.info("MariaDB verbunden (" + target() + ", Tabellenpräfix '" + tables.prefix() + "', Schema v"
                        + schemaVersion + ")" + (queue.isEmpty() ? "" : " - " + queue.size()
                        + " gepufferte Schreibvorgänge werden nachgeholt"));
            } else {
                long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - disconnectedSinceNanos);
                logger.info("MariaDB wieder erreichbar (nach " + seconds + " s) - " + queue.size()
                        + " gepufferte Schreibvorgänge werden nachgeholt");
            }
            return true;
        } catch (SQLException | RuntimeException e) {
            recordError(e);
            long now = System.nanoTime();
            nextAttemptNanos = now + backoffNanos;
            backoffNanos = Math.min(backoffNanos * 2, tuning.maxBackoff().toNanos());
            String reason = SqlErrors.describe(e, settings.password());
            String problem = reachable && !SqlErrors.isConnectionProblem(e)
                    ? "MariaDB-Schema konnte nicht vorbereitet werden" : "MariaDB nicht erreichbar";
            if (first) {
                disconnectedSinceNanos = now;
                lastUnavailableWarning = now;
                logger.warning(problem + " (" + target() + "): " + reason + " - Schreibvorgänge werden "
                        + "gepuffert, neuer Versuch im Hintergrund");
            } else if (now - lastUnavailableWarning >= tuning.warnInterval().toNanos()) {
                lastUnavailableWarning = now;
                logger.warning(problem + ", weiterhin (" + reason + ") - " + queue.size()
                        + " Schreibvorgänge gepuffert, " + dropped.get() + " verworfen");
            } else {
                logger.fine("MariaDB-Verbindungsversuch fehlgeschlagen: " + reason);
            }
            return false;
        }
    }

    /** Verbindung als verloren markieren (Schreib- oder Lesethread); warnt nur beim Übergang. */
    private void markDisconnected(@Nullable Throwable cause) {
        if (cause != null) {
            recordError(cause);
        }
        if (connected.compareAndSet(true, false)) {
            long now = System.nanoTime();
            disconnectedSinceNanos = now;
            if (Thread.currentThread() == writer) {
                lastUnavailableWarning = now;
                backoffNanos = tuning.initialBackoff().toNanos();
                nextAttemptNanos = now + backoffNanos;
            } else {
                reconnectRequested = true;
                queue.signal();
            }
            logger.warning("Verbindung zu MariaDB verloren" + (cause == null ? ""
                    : " (" + SqlErrors.describe(cause, settings.password()) + ")")
                    + " - Schreibvorgänge werden gepuffert (max. " + queue.capacity() + ") und später nachgeholt");
        }
    }

    /** Sichert den aktuellen Stand in die Outbox (oder löscht sie, wenn die Schlange leer ist). */
    private void persistJournal() {
        WriteQueue.Snapshot snapshot = queue.snapshot();
        try {
            if (journal.write(snapshot.ops(), snapshot.version())) {
                queue.markJournaled(snapshot.tailSeq());
            }
        } catch (IOException | RuntimeException e) {
            long now = System.nanoTime();
            if (now - lastJournalWarning >= tuning.warnInterval().toNanos() || lastJournalWarning == 0) {
                lastJournalWarning = now;
                logger.warning("MariaDB-Outbox " + journal.file() + " konnte nicht geschrieben werden: " + e.getMessage());
            }
            queue.completeAllWaiters(new StorageUnavailableException(
                    "MariaDB nicht erreichbar und Outbox nicht schreibbar: " + e.getMessage(), e));
        }
    }

    private void loadJournal() {
        Path file = journal.file();
        try {
            OutboxJournal.Loaded loaded = journal.load();
            if (loaded.skippedLines() > 0) {
                dropped.addAndGet(loaded.skippedLines());
                backup(file, "bad");
                logger.warning(loaded.skippedLines() + " unlesbare Outbox-Einträge verworfen (Kopie: "
                        + file.getFileName() + ".bad-*)");
            }
            if (!loaded.ops().isEmpty()) {
                int accepted = queue.prepend(loaded.ops());
                int lost = loaded.ops().size() - accepted;
                if (lost > 0) {
                    dropped.addAndGet(lost);
                    logger.warning(lost + " Outbox-Einträge passen nicht in resilience.outbox-max-entries ("
                            + queue.capacity() + ") und werden verworfen");
                }
                logger.info(accepted + " gepufferte Schreibvorgänge aus " + file + " geladen - werden zuerst ausgeführt");
            }
        } catch (IOException | RuntimeException e) {
            logger.warning("MariaDB-Outbox " + file + " ist nicht lesbar (" + e.getMessage()
                    + ") - sie wird beiseitegelegt und nicht eingespielt");
            backup(file, "unreadable");
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // bleibt liegen; wird beim nächsten Schreiben ersetzt
            }
        }
    }

    private void backup(Path file, String suffix) {
        try {
            Files.copy(file, file.resolveSibling(file.getFileName() + "." + suffix + "-" + System.currentTimeMillis()),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logger.fine("Sicherungskopie der Outbox nicht möglich: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ Lesen

    @FunctionalInterface
    private interface Query<T> {
        T run(Connection connection) throws SQLException;
    }

    private <T> T read(String what, Query<T> query) {
        if (!settings.enabled()) {
            throw new StorageUnavailableException("MariaDB ist deaktiviert");
        }
        if (closed.get()) {
            throw new StorageUnavailableException("MariaDB-Anbindung ist beendet");
        }
        HikariDataSource ds = dataSource;
        if (ds == null || !migrated || !connected.get()) {
            requestReconnect();
            String error = lastError;
            throw new StorageUnavailableException("MariaDB ist nicht erreichbar" + (error == null ? "" : " (" + error + ")"));
        }
        try (Connection c = ds.getConnection()) {
            T result = query.run(c);
            lastSuccess = clock.instant();
            return result;
        } catch (SQLException e) {
            if (ds.isClosed() || SqlErrors.isConnectionProblem(e)) {
                markDisconnected(e);
            } else {
                recordError(e);
            }
            throw new StorageUnavailableException("Datenbankabfrage fehlgeschlagen (" + what + "): "
                    + SqlErrors.describe(e, settings.password()), e);
        } catch (RuntimeException e) {
            // z. B. eine unlesbare Zeile: den Aufrufern dieselbe verständliche Ausnahme liefern
            recordError(e);
            logger.log(Level.WARNING, "Unerwarteter Fehler bei der Datenbankabfrage (" + what + ")", e);
            throw new StorageUnavailableException("Datenbankabfrage fehlgeschlagen (" + what + "): "
                    + SqlErrors.describe(e, settings.password()), e);
        }
    }

    private void requestReconnect() {
        if (!connected.get() && writer != null && System.nanoTime() - lastAttemptNanos >= READ_RECONNECT_NANOS) {
            reconnectRequested = true;
            queue.signal();
        }
    }

    @Override
    public Optional<SessionRow> findSessionByKey(String sessionKey) {
        Objects.requireNonNull(sessionKey, "sessionKey");
        return read("Sitzung nach Schlüssel", c -> reads.sessionByKey(c, sessionKey));
    }

    @Override
    public Optional<SessionRow> findSessionById(long sessionId) {
        return read("Sitzung nach ID", c -> reads.sessionById(c, sessionId));
    }

    @Override
    public List<SessionRow> findSessionsByRef(UUID playerUuid, String ref, int limit) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(ref, "ref");
        return read("Sitzungen nach Kurzreferenz", c -> reads.sessionsByRef(c, playerUuid, ref, limit));
    }

    @Override
    public List<SessionRow> sessionsOfPlayer(UUID playerUuid, Instant since, int limit, int offset) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(since, "since");
        return read("Sitzungen eines Spielers", c -> reads.sessionsOfPlayer(c, playerUuid, since, limit, offset));
    }

    @Override
    public int countSessionsOfPlayer(UUID playerUuid, Instant since) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(since, "since");
        return read("Anzahl Sitzungen", c -> reads.countSessionsOfPlayer(c, playerUuid, since));
    }

    @Override
    public Map<String, SessionStats> statsByCloudSessionIds(Collection<String> cloudSessionIds) {
        Objects.requireNonNull(cloudSessionIds, "cloudSessionIds");
        if (cloudSessionIds.isEmpty()) {
            return Map.of();
        }
        return read("Abschnittsstatistik", c -> reads.statsByCloudSessionIds(c, cloudSessionIds));
    }

    @Override
    public Map<Long, SessionStats> statsBySessionIds(Collection<Long> sessionIds) {
        Objects.requireNonNull(sessionIds, "sessionIds");
        if (sessionIds.isEmpty()) {
            return Map.of();
        }
        return read("Abschnittsstatistik", c -> reads.statsBySessionIds(c, sessionIds));
    }

    @Override
    public List<SessionRow> activeSessions(int limit) {
        return read("aktive Sitzungen", c -> reads.activeSessions(c, limit));
    }

    @Override
    public List<SessionRow> provisionalSessions(int limit) {
        return read("vorläufige Sitzungen", c -> reads.provisionalSessions(c, limit));
    }

    @Override
    public List<VisitRow> visitsOfSession(long sessionId) {
        return read("Aufenthalte", c -> reads.visitsOfSession(c, sessionId));
    }

    @Override
    public List<VisitRow> openVisits(String serverName) {
        Objects.requireNonNull(serverName, "serverName");
        return read("offene Aufenthalte", c -> reads.openVisits(c, serverName));
    }

    @Override
    public List<SegmentRow> segmentsOfSession(long sessionId) {
        return read("Abschnitte einer Sitzung", c -> reads.segmentsOfSession(c, sessionId));
    }

    @Override
    public Optional<SegmentRow> findSegment(long segmentId) {
        return read("Abschnitt", c -> reads.segmentById(c, segmentId));
    }

    @Override
    public Optional<SegmentRow> findSegmentByReplayName(String replayName) {
        Objects.requireNonNull(replayName, "replayName");
        return read("Abschnitt nach Name", c -> reads.segmentByReplayName(c, replayName));
    }

    @Override
    public List<RecordingRow> recordingsByStatus(String serverName, Set<RecordingStatus> statuses, int limit) {
        Objects.requireNonNull(serverName, "serverName");
        Objects.requireNonNull(statuses, "statuses");
        return read("Aufnahmen nach Status", c -> reads.recordingsByStatus(c, serverName, statuses, limit));
    }

    @Override
    public List<SegmentRow> segmentsForRetention(String serverName, Instant olderThan, int limit) {
        Objects.requireNonNull(serverName, "serverName");
        Objects.requireNonNull(olderThan, "olderThan");
        return read("Abschnitte für die Aufbewahrungsfrist",
                c -> reads.segmentsForRetention(c, serverName, olderThan, limit));
    }

    @Override
    public long countSegmentsOfPlayer(UUID playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        return read("Anzahl Abschnitte", c -> reads.countSegmentsOfPlayer(c, playerUuid));
    }

    @Override
    public List<SegmentRow> oldestSegmentsOfPlayer(UUID playerUuid, String serverName, int limit) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(serverName, "serverName");
        return read("älteste Abschnitte", c -> reads.oldestSegmentsOfPlayer(c, playerUuid, serverName, limit));
    }

    // ------------------------------------------------------------------ Hilfen

    private HikariDataSource createDataSource() {
        HikariConfig hc = new HikariConfig();
        hc.setPoolName(POOL_NAME);
        // Über den Klassennamen statt DriverManager: im Paper-Bibliotheks-Classloader sieht DriverManager den
        // Treiber nicht zuverlässig.
        hc.setDriverClassName("org.mariadb.jdbc.Driver");
        hc.setJdbcUrl(jdbcUrl());
        hc.setUsername(settings.username());
        hc.setPassword(settings.password());
        hc.setMaximumPoolSize(Math.max(1, settings.poolSize()));
        hc.setMinimumIdle(Math.min(2, Math.max(1, settings.poolSize())));
        long timeout = Math.max(250, settings.connectionTimeout().toMillis());
        hc.setConnectionTimeout(timeout);
        hc.setValidationTimeout(Math.max(250, Math.min(5_000, timeout)));
        // Pool startet auch ohne Datenbank; Verbindungen entstehen im Hintergrund.
        hc.setInitializationFailTimeout(-1);
        // READ COMMITTED: gesperrte Lesezugriffe sehen gleichzeitig angelegte Platzhalterzeilen anderer Server.
        hc.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
        // Fremde, lange gehaltene Sperren sollen den Schreib-Thread nicht 4 x 50 s (MariaDB-Standard) aufhalten.
        hc.setConnectionInitSql("SET SESSION innodb_lock_wait_timeout = " + LOCK_WAIT_TIMEOUT_SECONDS);
        hc.setMaxLifetime(TimeUnit.MINUTES.toMillis(25));
        hc.setKeepaliveTime(TimeUnit.MINUTES.toMillis(4));
        hc.addDataSourceProperty("connectTimeout", String.valueOf(timeout));
        hc.addDataSourceProperty("socketTimeout", String.valueOf(DEFAULT_SOCKET_TIMEOUT_MILLIS));
        settings.properties().forEach(hc::addDataSourceProperty);
        return new HikariDataSource(hc);
    }

    private String jdbcUrl() {
        String host = settings.host();
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
            host = "[" + host + "]"; // IPv6
        }
        return "jdbc:mariadb://" + host + ":" + settings.port() + "/" + settings.database();
    }

    /** Ziel ohne Zugangsdaten für Protokolle. */
    private String target() {
        return settings.host() + ":" + settings.port() + "/" + settings.database();
    }

    private void recordError(@Nullable Throwable error) {
        if (error != null) {
            lastError = SqlErrors.describe(error, settings.password());
        }
    }
}
