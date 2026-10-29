package net.techniqstone.buildReplay.recording.service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.techniqstone.buildReplay.activity.ActivityObserver;
import net.techniqstone.buildReplay.activity.ActivityType;
import net.techniqstone.buildReplay.activity.SegmentPlanning;
import net.techniqstone.buildReplay.command.Permissions;
import net.techniqstone.buildReplay.config.PluginConfig;
import net.techniqstone.buildReplay.core.SerialExecutor;
import net.techniqstone.buildReplay.core.TaskRunner;
import net.techniqstone.buildReplay.recording.ActiveRecordingInfo;
import net.techniqstone.buildReplay.recording.EngineStatus;
import net.techniqstone.buildReplay.recording.RawRecording;
import net.techniqstone.buildReplay.recording.RawRecordingSink;
import net.techniqstone.buildReplay.recording.RecordingRecovery;
import net.techniqstone.buildReplay.recording.RecordingStats;
import net.techniqstone.buildReplay.recording.Recordings;
import net.techniqstone.buildReplay.recording.ReplayEngine;
import net.techniqstone.buildReplay.recording.ReplayNames;
import net.techniqstone.buildReplay.server.ServerIdentity;
import net.techniqstone.buildReplay.session.NetworkSession;
import net.techniqstone.buildReplay.session.Sessions;
import net.techniqstone.buildReplay.state.RecordingState;
import net.techniqstone.buildReplay.state.SharedState;
import net.techniqstone.buildReplay.storage.ReplayStore;
import net.techniqstone.buildReplay.storage.model.RecordingRow;
import net.techniqstone.buildReplay.storage.model.RecordingStatus;
import net.techniqstone.buildReplay.storage.model.RecordingUpsert;
import net.techniqstone.buildReplay.storage.model.SessionRow;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Lebenszyklus der Pufferaufnahmen dieses Servers.
 *
 * <p>Jeder anwesende, nicht AFK-Spieler wird fortlaufend von AdvancedReplay aufgezeichnet (Rohname
 * {@link ReplayNames#RAW_PREFIX}). Parallel führt der Dienst je Aufnahme eine {@link
 * net.techniqstone.buildReplay.activity.ActivityTimeline} mit den Aktivitätsereignissen und regelmäßigen
 * Tick/Uhrzeit-Proben. Endet eine Aufnahme (AFK, Quit, Weltwechsel, Rotation), übergibt die Engine die Rohdaten an
 * {@link #accept(RawRecording)}; der I/O-Pool plant daraus Abschnitte (Vor-/Nachlauf, AFK-Lücken entfernt), lässt sie
 * als eigene AdvancedReplay-Replays speichern und legt sie unter der netzwerkweiten Sitzung im Store ab.
 *
 * <p><b>Threads</b>: {@link #onJoin}, {@link #onQuit}, {@link #reevaluate}, {@link #tick}, {@link #shutdown} und die
 * {@link ActivityObserver}-Methoden laufen auf dem Hauptthread. {@link #accept} wird auf dem stoppenden Thread
 * aufgerufen (Hauptthread oder AdvancedReplays Timer-Thread) und reicht nur weiter. Die lesenden Methoden aus
 * {@link Recordings} sind threadsicher.
 *
 * <p><b>Einbindung</b>: {@code engine.install(service)} (Senke), {@code activity.addObserver(service)}, sekündlich
 * {@link #tick(Instant)} auf dem Hauptthread, beim Aktivieren {@link #onJoin} für bereits anwesende Spieler und
 * {@link #recoverPending} mit den RAW_PENDING-Zeilen dieses Servers, in {@code onDisable} vor AdvancedReplay
 * {@link #shutdown(long)}.
 */
public final class RecordingService implements Recordings, RecordingRecovery, ActivityObserver, RawRecordingSink {

    /** Abstand der Tick/Uhrzeit-Proben. */
    static final Duration SAMPLE_INTERVAL = Duration.ofSeconds(5);
    /** Wartezeit nach einem gescheiterten Aufnahmestart. */
    static final Duration START_RETRY_DELAY = Duration.ofSeconds(30);
    /** Ruhephase für die Rotation: Nachlauf plus dieser Puffer ohne relevante Aktion. */
    static final Duration LULL_MARGIN = Duration.ofSeconds(2);
    /** Sicherheitsabstand zu AdvancedReplays {@code max_length} (dort wird ohne Speichern verworfen). */
    static final Duration MAX_LENGTH_MARGIN = Duration.ofSeconds(60);
    private static final Duration MIN_ROTATION = Duration.ofSeconds(10);
    private static final Duration MIN_SESSION_WAIT = Duration.ofSeconds(5);
    private static final Duration MAX_SESSION_WAIT = Duration.ofSeconds(60);
    private static final Duration LOAD_RAW_TIMEOUT = Duration.ofSeconds(60);
    /** Mindestens so viel Restbudget braucht eine Zerlegung beim Herunterfahren, sonst wird roh gesichert. */
    private static final long MIN_INLINE_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(250);
    private static final long MILLIS_PER_TICK = 50L;

    private final PluginConfig config;
    private final ServerIdentity identity;
    private final TaskRunner tasks;
    private final Clock clock;
    private final ReplayEngine engine;
    private final Sessions sessions;
    private final ReplayStore store;
    private final SharedState shared;
    private final Logger logger;
    private final SegmentFinalizer finalizer;
    private final TimelineFiles timelines;
    private final Executor sharedWrites;

    /** Anwesende Spieler (nur Hauptthread). */
    private final Map<UUID, TrackedPlayer> players = new HashMap<>();
    /** Laufende Aufnahmen je Spieler (für die threadsicheren Abfragen). */
    private final Map<UUID, ActiveRecording> activeByPlayer = new ConcurrentHashMap<>();
    /** Alle noch nicht abgeschlossenen Aufnahmen (laufend oder in Zerlegung) je Rohname. */
    private final Map<String, ActiveRecording> byRaw = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<?>> inFlight = ConcurrentHashMap.newKeySet();
    /** Rohaufnahmen, die gerade nachträglich zerlegt werden (verhindert doppelte Abschnitte bei parallelen Aufrufen). */
    private final Set<String> recovering = ConcurrentHashMap.newKeySet();

    private final AtomicLong rawStarted = new AtomicLong();
    private final AtomicLong segmentsSaved = new AtomicLong();
    private final AtomicLong discarded = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicInteger pendingFinalizations = new AtomicInteger();

    private volatile boolean shuttingDown;
    private @Nullable Instant lastHeartbeat;

    public RecordingService(PluginConfig config, ServerIdentity identity, TaskRunner tasks, Clock clock,
                            ReplayEngine engine, Sessions sessions, ReplayStore store, SharedState shared,
                            SegmentPlanning planner, Path dataFolder, Logger logger) {
        this.config = Objects.requireNonNull(config, "config");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.store = Objects.requireNonNull(store, "store");
        this.shared = Objects.requireNonNull(shared, "shared");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.timelines = new TimelineFiles(dataFolder.resolve("pending"));
        this.sharedWrites = new SerialExecutor(tasks.io(), logger, false);
        this.finalizer = new SegmentFinalizer(Objects.requireNonNull(planner, "planner"), engine, store, identity,
                event -> runShared(() -> shared.publish(event)), logger);
    }

    // ------------------------------------------------------------------ Spieler-Lebenszyklus (Hauptthread)

    /** Spieler hat den Server betreten (nach {@code sessions.handleJoin} und {@code activity.start}). */
    public void onJoin(Player player) {
        if (shuttingDown) {
            return;
        }
        UUID id = player.getUniqueId();
        Instant now = clock.instant();
        TrackedPlayer previous = players.remove(id);
        if (previous != null) {
            endRecording(previous, now, "erneuter Join");
        }
        TrackedPlayer tracked = new TrackedPlayer(id, player.getName(), player);
        players.put(id, tracked);
        startIfEligible(tracked, now);
    }

    /** Spieler hat den Server verlassen - laufende Aufnahme abschließen. */
    public void onQuit(Player player) {
        onQuit(player.getUniqueId());
    }

    public void onQuit(UUID player) {
        TrackedPlayer tracked = players.remove(player);
        if (tracked != null) {
            endRecording(tracked, clock.instant(), "Server verlassen");
        }
    }

    /**
     * Welt- oder Spielmoduswechsel: im nächsten Tick neu bewerten ({@code PlayerGameModeChangeEvent} feuert vor der
     * Änderung).
     */
    public void reevaluateSoon(Player player) {
        UUID id = player.getUniqueId();
        tasks.runOnMainLater(() -> reevaluate(id), 1L);
    }

    /** Aufnahme beenden, wenn der Spieler nicht mehr aufgezeichnet werden darf, bzw. starten, wenn wieder erlaubt. */
    public void reevaluate(UUID player) {
        TrackedPlayer tracked = players.get(player);
        if (tracked == null || shuttingDown) {
            return;
        }
        Instant now = clock.instant();
        EngineStatus status = engineStatus();
        if (tracked.recording() != null) {
            if (!eligible(tracked, status)) {
                endRecording(tracked, now, "nicht mehr aufzeichnungsberechtigt");
            }
        } else if (!now.isBefore(tracked.retryAfter()) && eligible(tracked, status)) {
            startRecording(tracked, now);
        }
    }

    /**
     * Sekündlicher Takt (Hauptthread): Tick-Proben, Rotation vor AdvancedReplays {@code max_length}, Erkennung von
     * Aufnahmen, die AR selbst beendet hat, Neustarts und Herzschlag des Aufnahmestatus.
     */
    public void tick(Instant now) {
        if (shuttingDown) {
            return;
        }
        EngineStatus status = engineStatus();
        for (TrackedPlayer tracked : new ArrayList<>(players.values())) {
            ActiveRecording recording = tracked.recording();
            if (recording != null) {
                checkRecording(tracked, recording, status, now);
            }
            if (tracked.recording() == null && !now.isBefore(tracked.retryAfter()) && eligible(tracked, status)) {
                startRecording(tracked, now);
            }
        }
        heartbeatIfDue(now);
    }

    // ------------------------------------------------------------------ ActivityObserver (Hauptthread)

    @Override
    public void onRelevant(UUID player, ActivityType type, Instant at) {
        TrackedPlayer tracked = players.get(player);
        if (tracked == null || shuttingDown) {
            return;
        }
        if (tracked.afk()) {
            setAfk(tracked, false);
        }
        if (tracked.recording() == null) {
            startIfEligible(tracked, clock.instant());
        }
        ActiveRecording recording = tracked.recording();
        if (recording != null) {
            recording.record(safeTick(recording.rawName()), at, type, true);
        }
    }

    @Override
    public void onIndicator(UUID player, ActivityType type, Instant at) {
        TrackedPlayer tracked = players.get(player);
        if (tracked == null) {
            return;
        }
        ActiveRecording recording = tracked.recording();
        if (recording != null) {
            recording.record(safeTick(recording.rawName()), at, type, false);
        }
    }

    @Override
    public void onAfk(UUID player, Instant lastInputAt) {
        TrackedPlayer tracked = players.get(player);
        if (tracked == null) {
            return;
        }
        setAfk(tracked, true);
        if (config.recording().stopWhileAfk() && tracked.recording() != null) {
            endRecording(tracked, clock.instant(), "AFK");
        }
    }

    @Override
    public void onActive(UUID player, Instant at) {
        TrackedPlayer tracked = players.get(player);
        if (tracked == null || shuttingDown) {
            return;
        }
        setAfk(tracked, false);
        startIfEligible(tracked, clock.instant());
    }

    // ------------------------------------------------------------------ RawRecordingSink

    /**
     * Von der Engine während {@code stopRecording(raw, true)} aufgerufen (oder von AdvancedReplays Timer-Thread, wenn
     * AR die Aufnahme selbst gespeichert hat). Arbeitet nur kurz und reicht an den I/O-Pool weiter.
     */
    @Override
    public void accept(RawRecording raw) {
        ActiveRecording recording = byRaw.get(raw.rawName());
        if (recording == null) {
            logger.warning("Rohaufnahme " + raw.rawName() + " ist unbekannt (z. B. aus einem früheren Lauf) und wird "
                    + "verworfen");
            return;
        }
        boolean stoppedByAdvancedReplay;
        synchronized (recording) {
            if (!recording.attachRaw(raw)) {
                return;
            }
            stoppedByAdvancedReplay = recording.phase() == ActiveRecording.Phase.RECORDING;
        }
        if (stoppedByAdvancedReplay) {
            logger.warning("AdvancedReplay hat die Aufnahme " + raw.rawName() + " von " + recording.playerName()
                    + " selbst beendet - die Abschnitte werden trotzdem gespeichert");
            beginFinalizing(recording, clock.instant());
        }
        if (recording.inlineProcessing()) {
            return;
        }
        submitProcessing(recording, raw);
    }

    // ------------------------------------------------------------------ Herunterfahren und Wiederaufnahme

    /**
     * {@code onDisable} auf dem Hauptthread, bevor AdvancedReplay deaktiviert wird: schließt alle Aufnahmen innerhalb
     * von {@code budgetMillis} direkt ab (der I/O-Pool wird gleich beendet). Was nicht mehr hineinpasst, wird roh in
     * AdvancedReplays Speicher und mit der Zeitachse unter {@code pending/<raw>.json} gesichert (RAW_PENDING) und
     * beim nächsten Start über {@link #recoverPending} zerlegt.
     */
    public void shutdown(long budgetMillis) {
        if (shuttingDown) {
            return;
        }
        shuttingDown = true;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, budgetMillis));
        Instant now = clock.instant();
        List<TrackedPlayer> recordingPlayers = players.values().stream()
                .filter(tracked -> tracked.recording() != null)
                .sorted(Comparator.comparingInt((TrackedPlayer tracked) ->
                        Objects.requireNonNull(tracked.recording()).timeline().relevantCount()).reversed())
                .toList();
        int inline = 0;
        int parked = 0;
        long lastCost = 0L;
        for (TrackedPlayer tracked : recordingPlayers) {
            ActiveRecording recording = Objects.requireNonNull(tracked.recording());
            detach(tracked, recording);
            recording.sample(safeTick(recording.rawName()), now);
            long remaining = deadline - System.nanoTime();
            try {
                if (remaining > Math.max(MIN_INLINE_BUDGET_NANOS, lastCost * 2)) {
                    long begin = System.nanoTime();
                    RawRecording raw = stopAndHandOver(recording, now, true);
                    if (raw != null) {
                        process(recording, raw, Duration.ZERO);
                        inline++;
                    }
                    lastCost = System.nanoTime() - begin;
                } else {
                    RawRecording raw = stopAndHandOver(recording, now, true);
                    if (raw != null) {
                        persistPending(recording, raw, recording.endedAt(now));
                        parked++;
                    }
                }
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "Aufnahme " + recording.rawName() + " konnte beim Herunterfahren nicht "
                        + "abgeschlossen werden", e);
                fail(recording, recording.endedAt(now), "Herunterfahren: " + describe(e));
            }
        }
        players.clear();
        int stillRunning = awaitInFlight(deadline);
        logger.info("Aufnahmen beim Herunterfahren: " + inline + " zerlegt, " + parked + " roh gesichert"
                + (stillRunning > 0 ? ", " + stillRunning + " Zerlegung(en) liefen noch" : ""));
    }

    @Override
    public CompletableFuture<Integer> recoverPending(List<RecordingRow> rows) {
        List<RecordingRow> copy = List.copyOf(rows);
        if (copy.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        return tasks.supplyIo(() -> {
            int recovered = 0;
            for (RecordingRow row : copy) {
                try {
                    if (recoverOne(row)) {
                        recovered++;
                    }
                } catch (RuntimeException e) {
                    logger.log(Level.WARNING, "Rohaufnahme " + row.rawName() + " konnte nicht zerlegt werden", e);
                    failures.incrementAndGet();
                    storeWrite(() -> store.finishRecording(row.rawName(), RecordingStatus.FAILED,
                            row.endedAt() != null ? row.endedAt() : clock.instant(), row.relevantActions(),
                            SegmentFinalizer.truncate(describe(e))));
                }
            }
            logger.info("Ausstehende Rohaufnahmen: " + recovered + " von " + copy.size() + " nachträglich zerlegt");
            return recovered;
        });
    }

    // ------------------------------------------------------------------ Recordings

    @Override
    public List<ActiveRecordingInfo> activeRecordings() {
        return activeByPlayer.values().stream()
                .sorted(Comparator.comparing(ActiveRecording::startedAt))
                .map(this::info)
                .toList();
    }

    @Override
    public Optional<ActiveRecordingInfo> recordingOf(UUID player) {
        return Optional.ofNullable(activeByPlayer.get(player)).map(this::info);
    }

    @Override
    public RecordingStats stats() {
        return new RecordingStats(activeByPlayer.size(), rawStarted.get(), segmentsSaved.get(), discarded.get(),
                failures.get(), pendingFinalizations.get());
    }

    // ------------------------------------------------------------------ Start / Prüfung

    private void startIfEligible(TrackedPlayer tracked, Instant now) {
        if (tracked.recording() == null && !now.isBefore(tracked.retryAfter()) && eligible(tracked, engineStatus())) {
            startRecording(tracked, now);
        }
    }

    private boolean eligible(TrackedPlayer tracked, @Nullable EngineStatus status) {
        PluginConfig.RecordingSettings settings = config.recording();
        if (!settings.enabled() || (tracked.afk() && settings.stopWhileAfk())) {
            return false;
        }
        // Ohne installierten Saver-Decorator würde AR die Rohaufnahme selbst speichern statt sie zu übergeben
        if (status == null || !status.available() || !status.installed()) {
            return false;
        }
        Player player = tracked.player();
        try {
            if (!player.isOnline() || player.hasPermission(Permissions.EXEMPT)) {
                return false;
            }
            String world = worldOf(player);
            if (world == null || !settings.recordsWorld(world)) {
                return false;
            }
            GameMode mode = player.getGameMode();
            if (mode != null && settings.excludedGameModes().contains(mode)) {
                return false;
            }
            // Zuschauer einer Wiedergabe nicht aufzeichnen (Steuer-Items, Flug, Teleport ins Replay)
            return !engine.isWatching(player);
        } catch (RuntimeException e) {
            logger.log(Level.FINE, "Aufnahmeprüfung für " + tracked.name() + " fehlgeschlagen", e);
            return false;
        }
    }

    private void startRecording(TrackedPlayer tracked, Instant now) {
        String rawName = ReplayNames.newRawName();
        try {
            engine.startRecording(rawName, tracked.player());
        } catch (RuntimeException e) {
            failures.incrementAndGet();
            tracked.retryAfter(now.plus(START_RETRY_DELAY));
            logger.warning("Aufnahme von " + tracked.name() + " konnte nicht gestartet werden: " + describe(e));
            return;
        }
        String sessionKey = sessions.current(tracked.uuid()).map(NetworkSession::sessionKey).orElse(null);
        ActiveRecording recording = new ActiveRecording(
                new RecordingMeta(rawName, tracked.uuid(), tracked.name(), worldOf(tracked.player()), now), sessionKey);
        recording.afk(tracked.afk());
        tracked.recording(recording);
        activeByPlayer.put(tracked.uuid(), recording);
        byRaw.put(rawName, recording);
        rawStarted.incrementAndGet();
        storeWrite(() -> store.upsertRecording(upsert(recording, RecordingStatus.RECORDING)));
        publishRecordingState(recording);
        logger.fine("Aufnahme " + rawName + " für " + tracked.name() + " gestartet");

        CompletableFuture<NetworkSession> resolving;
        try {
            resolving = sessions.resolve(tracked.uuid());
        } catch (RuntimeException e) {
            logger.log(Level.FINE, "Sitzung von " + tracked.name() + " konnte nicht aufgelöst werden", e);
            return;
        }
        if (resolving != null) {
            resolving.whenComplete((session, error) -> attachSession(recording, session, error));
        }
    }

    /** Sitzung nachtragen, sobald sie aufgelöst ist (beliebiger Thread). */
    private void attachSession(ActiveRecording recording, @Nullable NetworkSession session, @Nullable Throwable error) {
        if (error != null || session == null) {
            logger.log(Level.FINE, "Sitzung für " + recording.rawName() + " nicht aufgelöst", error);
            return;
        }
        synchronized (recording) {
            if (session.sessionKey().equals(recording.sessionKey())) {
                return;
            }
            recording.sessionKey(session.sessionKey());
            ActiveRecording.Phase phase = recording.phase();
            if (phase != ActiveRecording.Phase.DONE) {
                RecordingStatus status = phase == ActiveRecording.Phase.RECORDING
                        ? RecordingStatus.RECORDING : RecordingStatus.FINALIZING;
                storeWrite(() -> store.upsertRecording(upsert(recording, status)));
            }
        }
    }

    private void checkRecording(TrackedPlayer tracked, ActiveRecording recording, @Nullable EngineStatus status,
                                Instant now) {
        if (recording.hasRaw()) {
            // AR hat selbst gespeichert und übergeben - die Zerlegung läuft bereits
            detach(tracked, recording);
            return;
        }
        String rawName = recording.rawName();
        if (!engine.isRecording(rawName)) {
            detach(tracked, recording);
            failures.incrementAndGet();
            complete(recording, RecordingStatus.FAILED, now, recording.timeline().relevantCount(),
                    "AdvancedReplay hat die Aufnahme ohne Speichern beendet (z. B. max_length erreicht)");
            logger.warning("AdvancedReplay hat die Aufnahme " + rawName + " von " + tracked.name()
                    + " unerwartet beendet - neue Aufnahme wird gestartet");
            return;
        }
        if (!eligible(tracked, status)) {
            endRecording(tracked, now, "nicht mehr aufzeichnungsberechtigt");
            return;
        }
        int tick = safeTick(rawName);
        recording.sampleIfDue(tick, now, SAMPLE_INTERVAL);
        recording.currentWorld(worldOf(tracked.player()));
        if (rotationDue(recording, tick, now, status)) {
            endRecording(tracked, now, "Rotation");
        }
    }

    /**
     * Rotation: ab {@code recording.rotation-minutes} an einer Ruhestelle (kein relevantes Ereignis seit Nachlauf +
     * 2 s), spätestens beim harten Limit (1,5 × Rotation, höchstens {@code max_length} − 60 s).
     */
    boolean rotationDue(ActiveRecording recording, int tick, Instant now, @Nullable EngineStatus status) {
        Duration age = Duration.between(recording.startedAt(), now);
        Duration tickAge = Duration.ofMillis(Math.max(0, tick) * MILLIS_PER_TICK);
        if (tickAge.compareTo(age) > 0) {
            age = tickAge;
        }
        Duration hard = hardRotationLimit(status);
        if (age.compareTo(hard) >= 0) {
            return true;
        }
        Duration soft = config.recording().rotationInterval();
        if (soft.compareTo(hard) >= 0) {
            soft = hard.multipliedBy(3).dividedBy(4);
        }
        if (age.compareTo(soft) < 0) {
            return false;
        }
        Instant lastRelevant = recording.lastRelevantAt();
        Duration lull = config.recording().postRoll().plus(LULL_MARGIN);
        return lastRelevant == null || Duration.between(lastRelevant, now).compareTo(lull) >= 0;
    }

    Duration hardRotationLimit(@Nullable EngineStatus status) {
        Duration limit = config.recording().rotationInterval().multipliedBy(3).dividedBy(2);
        int maxLength = status == null ? 0 : status.maxLengthSeconds();
        if (maxLength > 0) {
            Duration arLimit = Duration.ofSeconds(maxLength).minus(MAX_LENGTH_MARGIN);
            Duration half = Duration.ofSeconds(maxLength).dividedBy(2);
            if (arLimit.compareTo(half) < 0) {
                arLimit = half;
            }
            if (arLimit.compareTo(limit) < 0) {
                limit = arLimit;
            }
        }
        return limit.compareTo(MIN_ROTATION) < 0 ? MIN_ROTATION : limit;
    }

    // ------------------------------------------------------------------ Abschluss

    /** Hauptthread: Aufnahme vom Spieler lösen, stoppen und verwerfen bzw. zur Zerlegung übergeben. */
    private void endRecording(TrackedPlayer tracked, Instant now, String reason) {
        ActiveRecording recording = tracked.recording();
        if (recording == null) {
            return;
        }
        detach(tracked, recording);
        recording.sample(safeTick(recording.rawName()), now);
        logger.fine("Aufnahme " + recording.rawName() + " von " + tracked.name() + " endet (" + reason + ")");
        try {
            stopAndHandOver(recording, now, false);
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Aufnahme " + recording.rawName() + " konnte nicht abgeschlossen werden", e);
            fail(recording, now, describe(e));
        }
    }

    private void detach(TrackedPlayer tracked, ActiveRecording recording) {
        if (tracked.recording() == recording) {
            tracked.recording(null);
        }
        activeByPlayer.remove(tracked.uuid(), recording);
        runShared(() -> shared.clearRecordingState(recording.player(), recording.rawName()));
    }

    /**
     * Hauptthread: stoppt die AR-Aufnahme. Zu wenig relevante Aktionen → verworfen. Sonst FINALIZING und
     * {@code stopRecording(raw, true)}; die Engine ruft {@link #accept} noch während des Aufrufs auf.
     *
     * @return bei {@code inline=true} die Rohaufnahme, die der Aufrufer selbst verarbeiten muss, sonst {@code null}
     */
    private @Nullable RawRecording stopAndHandOver(ActiveRecording recording, Instant now, boolean inline) {
        String rawName = recording.rawName();
        int relevant = recording.timeline().relevantCount();
        if (relevant < config.recording().minRelevantActions()) {
            stopEngineQuietly(rawName, false);
            if (complete(recording, RecordingStatus.DISCARDED, now, relevant, null)) {
                discarded.incrementAndGet();
            }
            return null;
        }
        if (!beginFinalizing(recording, now)) {
            // AR hat bereits selbst übergeben; die Zerlegung läuft schon
            return null;
        }
        recording.inlineProcessing(inline);
        try {
            engine.stopRecording(rawName, true);
        } catch (RuntimeException e) {
            if (!recording.hasRaw()) {
                fail(recording, now, "Stoppen fehlgeschlagen: " + describe(e));
                return null;
            }
            logger.log(Level.FINE, "Fehler nach der Übergabe von " + rawName, e);
        }
        RawRecording raw = recording.raw();
        if (raw == null) {
            // AR speichert Aufnahmen ohne Aktionen nicht - es gab nichts zu sichern
            if (complete(recording, RecordingStatus.DISCARDED, now, relevant, null)) {
                discarded.incrementAndGet();
            }
            return null;
        }
        return inline ? raw : null;
    }

    private boolean beginFinalizing(ActiveRecording recording, Instant at) {
        synchronized (recording) {
            if (!recording.beginFinalizing(at)) {
                return false;
            }
            pendingFinalizations.incrementAndGet();
            int relevant = recording.timeline().relevantCount();
            storeWrite(() -> store.finishRecording(recording.rawName(), RecordingStatus.FINALIZING, at, relevant, null));
            return true;
        }
    }

    /** Endstatus genau einmal einreihen; {@code false}, wenn die Aufnahme bereits abgeschlossen war. */
    private boolean complete(ActiveRecording recording, RecordingStatus status, Instant endedAt, int relevant,
                             @Nullable String error) {
        synchronized (recording) {
            ActiveRecording.Phase previous = recording.complete();
            if (previous == ActiveRecording.Phase.DONE) {
                return false;
            }
            if (previous == ActiveRecording.Phase.FINALIZING) {
                pendingFinalizations.decrementAndGet();
            }
            storeWrite(() -> store.finishRecording(recording.rawName(), status, endedAt, relevant, error));
        }
        byRaw.remove(recording.rawName(), recording);
        return true;
    }

    private void fail(ActiveRecording recording, Instant endedAt, String error) {
        if (complete(recording, RecordingStatus.FAILED, endedAt, recording.timeline().relevantCount(),
                SegmentFinalizer.truncate(error))) {
            failures.incrementAndGet();
        }
    }

    private void submitProcessing(ActiveRecording recording, RawRecording raw) {
        Duration wait = sessionWait();
        CompletableFuture<Void> future;
        try {
            future = CompletableFuture.runAsync(() -> process(recording, raw, wait), tasks.io());
        } catch (RejectedExecutionException e) {
            logger.warning("I/O-Pool nimmt keine Aufgaben an - Rohaufnahme " + raw.rawName()
                    + " wird zur späteren Zerlegung gesichert");
            try {
                persistPending(recording, raw, recording.endedAt(clock.instant()));
            } catch (RuntimeException inner) {
                fail(recording, recording.endedAt(clock.instant()), describe(inner));
            }
            return;
        }
        inFlight.add(future);
        future.whenComplete((ignored, error) -> inFlight.remove(future));
    }

    /** Rohaufnahme zerlegen und Endstatus setzen (I/O-Thread bzw. beim Herunterfahren Hauptthread). */
    private void process(ActiveRecording recording, RawRecording raw, Duration sessionWait) {
        Instant endedAt = recording.endedAt(clock.instant());
        int relevant = recording.timeline().relevantCount();
        try {
            SegmentFinalizer.Outcome outcome = finalizer.finalizeRaw(recording.meta(), raw,
                    recording.timeline().snapshot(),
                    () -> resolveSessionKey(recording.player(), recording.sessionKey(), sessionWait));
            count(outcome);
            complete(recording, outcome.status(), endedAt, relevant, outcome.error());
            logger.fine("Aufnahme " + recording.rawName() + ": " + outcome.status() + ", " + outcome.savedSegments()
                    + " Abschnitt(e)");
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Zerlegung von " + recording.rawName() + " fehlgeschlagen", e);
            fail(recording, endedAt, describe(e));
        }
    }

    /** Rohaufnahme und Zeitachse für die spätere Zerlegung sichern (RAW_PENDING). */
    private void persistPending(ActiveRecording recording, RawRecording raw, Instant endedAt) {
        int relevant = recording.timeline().relevantCount();
        try {
            timelines.write(new TimelineFiles.Pending(recording.meta(), recording.sessionKey(), endedAt, relevant,
                    recording.timeline().snapshot()));
        } catch (IOException e) {
            fail(recording, endedAt, "Zeitachse konnte nicht gesichert werden: " + e.getMessage());
            return;
        }
        try {
            engine.saveRaw(raw);
        } catch (RuntimeException e) {
            timelines.delete(recording.rawName());
            fail(recording, endedAt, "Rohaufnahme konnte nicht gesichert werden: " + describe(e));
            return;
        }
        complete(recording, RecordingStatus.RAW_PENDING, endedAt, relevant, null);
    }

    private boolean recoverOne(RecordingRow row) {
        String rawName = row.rawName();
        if (row.status() != RecordingStatus.RAW_PENDING) {
            logger.fine("Rohaufnahme " + rawName + " hat Status " + row.status() + " - übersprungen");
            return false;
        }
        if (!row.serverName().equalsIgnoreCase(identity.serverName())) {
            logger.fine("Rohaufnahme " + rawName + " liegt auf " + row.serverName() + " - übersprungen");
            return false;
        }
        if (!recovering.add(rawName)) {
            logger.fine("Rohaufnahme " + rawName + " wird bereits zerlegt - übersprungen");
            return false;
        }
        try {
            return recoverClaimed(row);
        } finally {
            recovering.remove(rawName);
        }
    }

    private boolean recoverClaimed(RecordingRow row) {
        String rawName = row.rawName();
        Instant fallbackEnd = row.endedAt() != null ? row.endedAt() : clock.instant();
        Optional<TimelineFiles.Pending> pending;
        try {
            pending = timelines.read(rawName);
        } catch (IOException | IllegalArgumentException e) {
            failRecovered(row, fallbackEnd, "Zeitachse unlesbar: " + e.getMessage());
            return false;
        }
        if (pending.isEmpty()) {
            if (!rawExists(rawName)) {
                // Zeitachse und Rohdaten fehlen beide: bereits zerlegt (Zeile noch nicht aktualisiert) - nichts tun,
                // sonst würde ein FINALIZED mit FAILED überschrieben
                logger.fine("Rohaufnahme " + rawName + " ist bereits verarbeitet - übersprungen");
                return false;
            }
            failRecovered(row, fallbackEnd, "Zeitachse pending/" + rawName + ".json fehlt");
            deleteRawQuietly(rawName);
            return false;
        }
        Optional<RawRecording> raw = loadRaw(rawName);
        if (raw.isEmpty()) {
            failRecovered(row, fallbackEnd, "Rohaufnahme fehlt im AdvancedReplay-Speicher oder ist unlesbar");
            timelines.delete(rawName);
            return false;
        }
        TimelineFiles.Pending timeline = pending.get();
        Instant endedAt = timeline.endedAt() != null ? timeline.endedAt() : fallbackEnd;
        SegmentFinalizer.Outcome outcome = finalizer.finalizeRaw(timeline.meta(), raw.get(), timeline.timeline(),
                () -> recoveredSessionKey(timeline, row));
        count(outcome);
        if (outcome.status() == RecordingStatus.FAILED) {
            // Rohdaten und Zeitachse bleiben für eine manuelle Analyse liegen
            storeWrite(() -> store.finishRecording(rawName, RecordingStatus.FAILED, endedAt,
                    timeline.relevantActions(), outcome.error()));
            return false;
        }
        deleteRawQuietly(rawName);
        timelines.delete(rawName);
        storeWrite(() -> store.finishRecording(rawName, outcome.status(), endedAt, timeline.relevantActions(),
                outcome.error()));
        return true;
    }

    private void failRecovered(RecordingRow row, Instant endedAt, String error) {
        failures.incrementAndGet();
        logger.warning("Rohaufnahme " + row.rawName() + " kann nicht zerlegt werden: " + error);
        storeWrite(() -> store.finishRecording(row.rawName(), RecordingStatus.FAILED, endedAt, row.relevantActions(),
                SegmentFinalizer.truncate(error)));
    }

    private Optional<RawRecording> loadRaw(String rawName) {
        try {
            CompletableFuture<Optional<RawRecording>> future = engine.loadRaw(rawName);
            if (future == null) {
                return Optional.empty();
            }
            Optional<RawRecording> result = future.get(LOAD_RAW_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return result == null ? Optional.empty() : result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            logger.log(Level.FINE, "Rohaufnahme " + rawName + " konnte nicht geladen werden", e);
            return Optional.empty();
        }
    }

    private boolean rawExists(String rawName) {
        try {
            return engine.exists(rawName);
        } catch (RuntimeException e) {
            logger.log(Level.FINE, "Existenz von " + rawName + " nicht prüfbar", e);
            return true;
        }
    }

    private void deleteRawQuietly(String rawName) {
        try {
            engine.delete(rawName);
        } catch (RuntimeException e) {
            logger.log(Level.FINE, "Rohaufnahme " + rawName + " konnte nicht gelöscht werden", e);
        }
    }

    // ------------------------------------------------------------------ Sitzungsschlüssel

    /**
     * Sitzung für die Abschnitte: beim Start bereits aufgelöst → aktuell bekannt → begrenzt auf die Auflösung warten →
     * vorläufige Sitzung (wird vom Abgleich später zusammengeführt).
     */
    private String resolveSessionKey(UUID player, @Nullable String known, Duration wait) {
        if (known != null) {
            return known;
        }
        Optional<NetworkSession> current = sessions.current(player);
        if (current.isPresent()) {
            return current.get().sessionKey();
        }
        try {
            CompletableFuture<NetworkSession> future = sessions.resolve(player);
            if (future != null) {
                NetworkSession session = wait.isZero()
                        ? (future.isDone() && !future.isCompletedExceptionally() ? future.join() : null)
                        : future.get(wait.toMillis(), TimeUnit.MILLISECONDS);
                if (session != null) {
                    return session.sessionKey();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            logger.log(Level.FINE, "Sitzung für " + player + " nicht rechtzeitig aufgelöst", e);
        }
        String provisional = NetworkSession.newProvisionalKey();
        logger.warning("Keine Sitzung für " + player + " bekannt - Abschnitte werden vorläufig unter " + provisional
                + " gespeichert");
        return provisional;
    }

    private String recoveredSessionKey(TimelineFiles.Pending timeline, RecordingRow row) {
        if (timeline.sessionKey() != null && !timeline.sessionKey().isBlank()) {
            return timeline.sessionKey();
        }
        if (row.sessionId() != null) {
            try {
                Optional<String> key = store.findSessionById(row.sessionId()).map(SessionRow::sessionKey);
                if (key.isPresent()) {
                    return key.get();
                }
            } catch (RuntimeException e) {
                logger.log(Level.FINE, "Sitzung " + row.sessionId() + " nicht lesbar", e);
            }
        }
        return resolveSessionKey(timeline.meta().player(), null, Duration.ZERO);
    }

    private Duration sessionWait() {
        Duration wait = config.simpleCloud().currentSessionRetryWindow().plus(config.simpleCloud().requestTimeout());
        if (wait.compareTo(MIN_SESSION_WAIT) < 0) {
            return MIN_SESSION_WAIT;
        }
        return wait.compareTo(MAX_SESSION_WAIT) > 0 ? MAX_SESSION_WAIT : wait;
    }

    // ------------------------------------------------------------------ Hilfen

    private void count(SegmentFinalizer.Outcome outcome) {
        segmentsSaved.addAndGet(outcome.savedSegments());
        if (outcome.status() == RecordingStatus.DISCARDED) {
            discarded.incrementAndGet();
        } else if (outcome.status() == RecordingStatus.FAILED) {
            failures.incrementAndGet();
        }
    }

    private void setAfk(TrackedPlayer tracked, boolean afk) {
        tracked.afk(afk);
        ActiveRecording recording = tracked.recording();
        if (recording != null) {
            recording.afk(afk);
        }
    }

    private void heartbeatIfDue(Instant now) {
        Instant last = lastHeartbeat;
        if (last != null && Duration.between(last, now).compareTo(config.resilience().heartbeatInterval()) < 0) {
            return;
        }
        lastHeartbeat = now;
        for (ActiveRecording recording : activeByPlayer.values()) {
            publishRecordingState(recording);
        }
    }

    private void publishRecordingState(ActiveRecording recording) {
        RecordingState state = new RecordingState(recording.rawName(), identity.serverName(), identity.instanceId(),
                recording.startedAt());
        Duration ttl = recordingStateTtl();
        runShared(() -> shared.putRecordingState(recording.player(), state, ttl));
    }

    private Duration recordingStateTtl() {
        Duration beats = config.resilience().heartbeatInterval().multipliedBy(3);
        Duration presence = config.resilience().presenceTtl();
        return beats.compareTo(presence) > 0 ? beats : presence;
    }

    private RecordingUpsert upsert(ActiveRecording recording, RecordingStatus status) {
        RecordingMeta meta = recording.meta();
        return new RecordingUpsert(meta.rawName(), recording.sessionKey(), meta.player(), meta.playerName(),
                identity.serverName(), identity.instanceId(), meta.worldName(), meta.startedAt(), status);
    }

    private ActiveRecordingInfo info(ActiveRecording recording) {
        String key = recording.sessionKey();
        return new ActiveRecordingInfo(recording.player(), recording.playerName(), recording.rawName(),
                recording.currentWorld(), recording.startedAt(), safeTick(recording.rawName()),
                recording.timeline().relevantCount(), recording.afk(), key == null ? null : NetworkSession.shortRef(key));
    }

    private int safeTick(String rawName) {
        try {
            return engine.currentTick(rawName);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private @Nullable EngineStatus engineStatus() {
        try {
            return engine.status();
        } catch (RuntimeException e) {
            logger.log(Level.FINE, "Engine-Status nicht abrufbar", e);
            return null;
        }
    }

    private void stopEngineQuietly(String rawName, boolean keep) {
        try {
            engine.stopRecording(rawName, keep);
        } catch (RuntimeException e) {
            logger.log(Level.FINE, "Aufnahme " + rawName + " konnte nicht gestoppt werden", e);
        }
    }

    private int awaitInFlight(long deadline) {
        for (CompletableFuture<?> future : List.copyOf(inFlight)) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            try {
                future.get(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (TimeoutException e) {
                break;
            } catch (ExecutionException | CancellationException e) {
                // bereits in process() protokolliert
            }
        }
        return (int) inFlight.stream().filter(future -> !future.isDone()).count();
    }

    private void storeWrite(Runnable write) {
        try {
            write.run();
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Schreibvorgang im Store fehlgeschlagen", e);
        }
    }

    private void runShared(Runnable action) {
        try {
            sharedWrites.execute(action);
        } catch (RejectedExecutionException e) {
            logger.fine("Gemeinsamer Zustand wird nicht mehr aktualisiert (I/O-Pool beendet)");
        }
    }

    private static @Nullable String worldOf(Player player) {
        World world = player.getWorld();
        return world == null ? null : world.getName();
    }

    private static String describe(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
