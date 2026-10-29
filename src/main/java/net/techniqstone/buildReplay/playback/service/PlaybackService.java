package net.techniqstone.buildReplay.playback.service;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.techniqstone.buildReplay.command.Permissions;
import net.techniqstone.buildReplay.config.Messages;
import net.techniqstone.buildReplay.config.PluginConfig;
import net.techniqstone.buildReplay.core.SerialExecutor;
import net.techniqstone.buildReplay.core.TaskRunner;
import net.techniqstone.buildReplay.playback.Playback;
import net.techniqstone.buildReplay.recording.EngineStatus;
import net.techniqstone.buildReplay.recording.PlaybackOptions;
import net.techniqstone.buildReplay.recording.PlaybackStart;
import net.techniqstone.buildReplay.recording.ReplayEngine;
import net.techniqstone.buildReplay.server.ServerIdentity;
import net.techniqstone.buildReplay.session.NetworkSession;
import net.techniqstone.buildReplay.simplecloud.SimpleCloudPlatform;
import net.techniqstone.buildReplay.simplecloud.SimpleCloudPlatform.ConnectOutcome;
import net.techniqstone.buildReplay.state.HandoffRequest;
import net.techniqstone.buildReplay.state.SharedState;
import net.techniqstone.buildReplay.storage.ReplayStore;
import net.techniqstone.buildReplay.storage.StorageUnavailableException;
import net.techniqstone.buildReplay.storage.model.SegmentRow;
import net.techniqstone.buildReplay.storage.model.SegmentStatus;
import net.techniqstone.buildReplay.storage.model.SessionRow;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Wiedergabe von Replay-Abschnitten über AdvancedReplay.
 *
 * <p>Prüft Berechtigung (eigene Abschnitte bzw. {@link Permissions#OTHERS}), Status und Kompatibilität, startet
 * Abschnitte anderer Server per Übergabe ({@link HandoffRequest} in Redis + SimpleCloud-Connect) und spielt ganze
 * Sitzungen als Warteschlange ab ({@code playback.auto-advance}). Das Inventar des Zuschauers wird vor dem Start
 * gesichert ({@code playback.protect-inventory}): beim Quit sofort wiederhergestellt (AdvancedReplay setzt erst
 * zurück, wenn der Spieler schon weg ist), nach einem normalen Ende verworfen (AR hat zurückgesetzt) und, falls AR
 * die Wiedergabe ohne Rückmeldung beendet, aus der Sicherung wiederhergestellt.
 *
 * <p>Alle öffentlichen Methoden auf dem Hauptthread. Einbindung: {@link #onJoin}/{@link #onQuitEarly} über den
 * {@code PlayerLifecycleListener}, {@link #shutdown()} in {@code onDisable} vor AdvancedReplay. Den Ende-Listener der
 * Engine registriert der Konstruktor.
 */
public final class PlaybackService implements Playback {

    /** Pause zwischen zwei Abschnitten einer Sitzung (2 s). */
    static final long NEXT_SEGMENT_DELAY_TICKS = 40L;
    /** Verzögerung nach dem Join, bevor eine übergebene Wiedergabe startet (~2 s). */
    static final long HANDOFF_START_DELAY_TICKS = 40L;
    /** Prüfabstand für Wiedergaben, die ohne Ende-Ereignis verschwinden. */
    static final long WATCHDOG_PERIOD_TICKS = 20L;
    /** So viele Prüfungen ohne laufende Wiedergabe, bevor die Sicherung angewendet wird. */
    static final int WATCHDOG_GRACE_CHECKS = 3;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final long SHUTDOWN_FLUSH_MILLIS = 2_000L;
    private static final int SESSION_MATCH_LIMIT = 5;

    static final String KEY_NOT_FOUND = "playback.not-found";
    static final String KEY_CORRUPT = "playback.corrupt";
    static final String KEY_WORLD_MISSING = "playback.world-missing";
    static final String KEY_INCOMPATIBLE = "playback.incompatible";
    static final String KEY_START_FAILED = "playback.start-failed";
    /** Fehler, die AdvancedReplay vor dem Umbau des Inventars meldet (Sicherung verwerfen statt anwenden). */
    private static final Set<String> PRECHECK_FAILURES = Set.of(KEY_NOT_FOUND, KEY_CORRUPT, KEY_WORLD_MISSING,
            KEY_INCOMPATIBLE);

    private final PluginConfig config;
    private final ServerIdentity identity;
    private final TaskRunner tasks;
    private final Clock clock;
    private final ReplayEngine engine;
    private final ReplayStore store;
    private final SharedState shared;
    private final SimpleCloudPlatform platform;
    private final Messages messages;
    private final Logger logger;
    private final InventoryProtection inventory;
    private final Executor sharedOps;

    /** Laufende Wiedergaben (threadsicher lesbar für {@link #isWatching(UUID)}). */
    private final Map<UUID, Watch> watches = new ConcurrentHashMap<>();
    /** Zuschauer, deren Start gerade vorbereitet wird (Laden aus dem Store bzw. von AdvancedReplay). */
    private final Map<UUID, Player> pendingStarts = new ConcurrentHashMap<>();
    /** Sitzungs-Warteschlangen (Hauptthread). */
    private final Map<UUID, SessionQueue> queues = new HashMap<>();
    /** Starts, die per {@link #stop} oder Quit abgebrochen wurden, während sie noch vorbereitet wurden. */
    private final Set<UUID> cancelledStarts = ConcurrentHashMap.newKeySet();

    public PlaybackService(PluginConfig config, ServerIdentity identity, TaskRunner tasks, Clock clock,
                           ReplayEngine engine, ReplayStore store, SharedState shared, SimpleCloudPlatform platform,
                           Messages messages, Path dataFolder, Logger logger) {
        this(config, identity, tasks, clock, engine, store, shared, platform, messages, logger,
                new InventoryGuard(dataFolder.resolve("inventory-backups"), tasks, clock, logger));
    }

    /** Für Tests: eigene Inventar-Sicherung. */
    PlaybackService(PluginConfig config, ServerIdentity identity, TaskRunner tasks, Clock clock, ReplayEngine engine,
                    ReplayStore store, SharedState shared, SimpleCloudPlatform platform, Messages messages,
                    Logger logger, InventoryProtection inventory) {
        this.config = Objects.requireNonNull(config, "config");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.store = Objects.requireNonNull(store, "store");
        this.shared = Objects.requireNonNull(shared, "shared");
        this.platform = Objects.requireNonNull(platform, "platform");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.sharedOps = new SerialExecutor(tasks.io(), logger, false);
        engine.onPlaybackFinished(this::handlePlaybackFinished);
    }

    // ------------------------------------------------------------------ Playback

    @Override
    public void playSegment(Player viewer, long segmentId) {
        queues.remove(viewer.getUniqueId());
        requestSegment(viewer, segmentId, null);
    }

    @Override
    public void playSession(Player viewer, UUID owner, String sessionRef) {
        playSessionFrom(viewer, owner, sessionRef, null);
    }

    @Override
    public void stop(Player viewer) {
        UUID id = viewer.getUniqueId();
        boolean hadQueue = queues.remove(id) != null;
        boolean pending = pendingStarts.containsKey(id);
        if (pending) {
            cancelledStarts.add(id);
        }
        if (engineWatching(viewer)) {
            try {
                engine.stopWatching(viewer);
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "AdvancedReplay konnte die Wiedergabe von " + viewer.getName()
                        + " nicht beenden - Inventar wird aus der Sicherung wiederhergestellt", e);
                watches.remove(id);
                restoreInventory(viewer);
            }
            send(viewer, "playback.stopped", "&7Wiedergabe beendet.");
            return;
        }
        if (hadQueue || pending || watches.containsKey(id)) {
            send(viewer, "playback.stopped", "&7Wiedergabe beendet.");
            return;
        }
        send(viewer, "playback.not-watching", "&7Du siehst gerade kein Replay.");
    }

    @Override
    public boolean isWatching(UUID viewer) {
        return watches.containsKey(viewer) || pendingStarts.containsKey(viewer);
    }

    // ------------------------------------------------------------------ Lebenszyklus

    /** Join: verwaiste Inventar-Sicherung anwenden, danach eine Übergabe von einem anderen Server ausführen. */
    public void onJoin(Player viewer) {
        UUID id = viewer.getUniqueId();
        CompletableFuture<Boolean> orphan;
        try {
            orphan = inventory.restoreOrphan(viewer, () -> !engineWatching(viewer) && !watches.containsKey(id));
        } catch (RuntimeException e) {
            orphan = CompletableFuture.failedFuture(e);
        }
        orphan.whenComplete((restored, error) -> tasks.runOnMain(() -> {
            if (error != null) {
                logger.log(Level.WARNING, "Verwaiste Inventar-Sicherung von " + viewer.getName()
                        + " konnte nicht wiederhergestellt werden", error);
            } else if (Boolean.TRUE.equals(restored) && viewer.isOnline()) {
                send(viewer, "playback.inventory-restored",
                        "&aDein Inventar aus einer unterbrochenen Wiedergabe wurde wiederhergestellt.");
            }
            checkHandoff(viewer);
        }));
    }

    /**
     * Quit, vor AdvancedReplays eigenem Quit-Handler: liegt eine Sicherung vor (Wiedergabe läuft oder startet gerade),
     * wird sie jetzt synchron angewendet, damit der Server den Spieler mit seinem echten Inventar speichert.
     */
    public void onQuitEarly(Player viewer) {
        UUID id = viewer.getUniqueId();
        queues.remove(id);
        if (pendingStarts.containsKey(id)) {
            cancelledStarts.add(id);
        }
        if (!inventory.hasBackup(id)) {
            return;
        }
        boolean restored = restoreInventory(viewer);
        Watch watch = watches.get(id);
        if (watch != null && restored) {
            watch.restoredOnQuit = true;
        }
    }

    /**
     * {@code onDisable}, bevor AdvancedReplay deaktiviert wird: AR beendet laufende Wiedergaben beim Abschalten nicht
     * und setzt die Zuschauer daher nie zurück - Wiedergaben beenden und Inventare jetzt wiederherstellen.
     */
    public void shutdown() {
        List<Player> viewers = new ArrayList<>();
        watches.values().forEach(watch -> viewers.add(watch.viewer));
        viewers.addAll(pendingStarts.values());
        for (Player viewer : viewers) {
            if (!viewer.isOnline()) {
                continue;
            }
            try {
                if (engineWatching(viewer)) {
                    engine.stopWatching(viewer);
                }
            } catch (RuntimeException e) {
                logger.log(Level.FINE, "Wiedergabe von " + viewer.getName() + " ließ sich nicht beenden", e);
            }
            restoreInventory(viewer);
        }
        watches.clear();
        pendingStarts.clear();
        queues.clear();
        cancelledStarts.clear();
        inventory.flush(SHUTDOWN_FLUSH_MILLIS);
    }

    // ------------------------------------------------------------------ Einzelner Abschnitt

    private void requestSegment(Player viewer, long segmentId, @Nullable SessionQueue queue) {
        UUID id = viewer.getUniqueId();
        if (pendingStarts.putIfAbsent(id, viewer) != null) {
            send(viewer, "playback.pending", "&7Die Wiedergabe wird bereits vorbereitet ...");
            return;
        }
        cancelledStarts.remove(id);
        CompletableFuture<Optional<SegmentRow>> lookup;
        try {
            lookup = tasks.supplyIo(() -> store.findSegment(segmentId));
        } catch (RuntimeException e) {
            lookup = CompletableFuture.failedFuture(e);
        }
        lookup.whenComplete((row, error) -> tasks.runOnMain(() -> {
            pendingStarts.remove(id);
            if (cancelledStarts.remove(id) || !viewer.isOnline()) {
                return;
            }
            if (queue != null && queues.get(id) != queue) {
                return;
            }
            if (error != null) {
                sendStorageError(viewer, error);
                dropQueue(id, queue);
                return;
            }
            onSegmentLoaded(viewer, segmentId, row == null ? Optional.empty() : row, queue);
        }));
    }

    private void onSegmentLoaded(Player viewer, long segmentId, Optional<SegmentRow> row,
                                 @Nullable SessionQueue queue) {
        String idText = Long.toString(segmentId);
        if (row.isEmpty()) {
            send(viewer, "playback.segment-not-found", "&cAbschnitt &f#{id} &cwurde nicht gefunden.", "id", idText);
            continueQueue(viewer, queue);
            return;
        }
        SegmentRow segment = row.get();
        if (!mayWatch(viewer, segment.playerUuid())) {
            send(viewer, "playback.no-permission-others",
                    "&cDu darfst nur deine eigenen Replays abspielen.", "id", idText);
            dropQueue(viewer.getUniqueId(), queue);
            return;
        }
        if (!segment.status().playable()) {
            send(viewer, "playback.unavailable",
                    "&cAbschnitt &f#{id} &cist nicht abspielbar (Status: &f{status}&c).",
                    "id", idText, "status", statusText(segment.status()));
            continueQueue(viewer, queue);
            return;
        }
        if (!isLocal(segment)) {
            handleRemote(viewer, segment, queue);
            return;
        }
        startLocal(viewer, segment, queue);
    }

    private void startLocal(Player viewer, SegmentRow segment, @Nullable SessionQueue queue) {
        UUID id = viewer.getUniqueId();
        EngineStatus status = engineStatus();
        if (status == null || !status.available()) {
            send(viewer, "playback.engine-unavailable", "&cAdvancedReplay ist auf diesem Server nicht verfügbar.");
            dropQueue(id, queue);
            return;
        }
        if (!status.playbackCompatible()) {
            String reason = status.incompatibilityReason() == null ? "unbekannt" : status.incompatibilityReason();
            send(viewer, KEY_INCOMPATIBLE, "&cDie Wiedergabe ist auf diesem Server nicht möglich: &f{reason}",
                    "id", Long.toString(segment.id()), "reason", reason, "detail", reason);
            dropQueue(id, queue);
            return;
        }
        if (engineWatching(viewer) || watches.containsKey(id)) {
            send(viewer, "playback.already-watching",
                    "&cDu siehst bereits ein Replay. Beende es mit &f/buildreplay stop&c.");
            return;
        }
        boolean guarded = false;
        if (config.playback().protectInventory()) {
            try {
                guarded = inventory.backup(viewer);
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "Inventar von " + viewer.getName() + " konnte nicht gesichert werden", e);
                send(viewer, "playback.backup-failed",
                        "&cDein Inventar konnte nicht gesichert werden - die Wiedergabe startet zur Sicherheit nicht.");
                dropQueue(id, queue);
                return;
            }
        }
        pendingStarts.put(id, viewer);
        PlaybackOptions options = new PlaybackOptions(config.playback().showInitialState(),
                config.playback().forceClientSideBlockChanges());
        CompletableFuture<PlaybackStart> start;
        try {
            start = engine.play(segment.replayName(), viewer, options);
        } catch (RuntimeException e) {
            start = CompletableFuture.completedFuture(PlaybackStart.failed(KEY_START_FAILED, describe(e)));
        }
        if (start == null) {
            start = CompletableFuture.completedFuture(PlaybackStart.failed(KEY_START_FAILED, "keine Antwort der Engine"));
        }
        boolean backupTaken = guarded;
        start.whenComplete((result, error) -> tasks.runOnMain(() -> onStartResult(viewer, segment, queue, backupTaken,
                result != null ? result : PlaybackStart.failed(KEY_START_FAILED,
                        error == null ? "unbekannter Fehler" : describe(error)))));
    }

    private void onStartResult(Player viewer, SegmentRow segment, @Nullable SessionQueue queue, boolean guarded,
                               PlaybackStart result) {
        UUID id = viewer.getUniqueId();
        pendingStarts.remove(id);
        boolean cancelled = cancelledStarts.remove(id);
        if (result.started()) {
            Watch watch = new Watch(viewer, segment, clock.instant());
            watches.put(id, watch);
            if (cancelled || !viewer.isOnline()) {
                // während des Ladens abgebrochen - AR setzt beim Beenden selbst zurück
                stopQuietly(viewer);
                scheduleWatchdog(watch);
                return;
            }
            int ticks = result.durationTicks() > 0 ? result.durationTicks() : segment.durationTicks();
            send(viewer, "playback.started", "&aReplay &f#{id} &avon &f{player} &astartet &7({duration})&a.",
                    "id", Long.toString(segment.id()), "player", segment.playerName(), "duration", formatTicks(ticks));
            if (queue != null && queues.get(id) == queue) {
                send(viewer, "playback.session-progress", "&7Abschnitt &f{position}/{total} &7der Sitzung &f{ref}",
                        "position", Integer.toString(queue.position()), "total", Integer.toString(queue.size()),
                        "ref", queue.displayRef());
            }
            scheduleWatchdog(watch);
            return;
        }

        String key = result.failureKey() == null || result.failureKey().isBlank() ? KEY_START_FAILED : result.failureKey();
        String detail = result.detail() == null ? "" : result.detail();
        boolean arHoldsViewer = engineWatching(viewer);
        if (arHoldsViewer) {
            stopQuietly(viewer);
        }
        if (guarded) {
            if (arHoldsViewer || !PRECHECK_FAILURES.contains(key)) {
                // AR hat das Inventar womöglich schon geleert - Sicherung anwenden
                restoreInventory(viewer);
            } else {
                inventory.discard(id);
            }
        }
        markBroken(segment, key, detail);
        if (!cancelled && viewer.isOnline()) {
            sendFailure(viewer, segment, key, detail);
        }
        if (KEY_INCOMPATIBLE.equals(key)) {
            dropQueue(id, queue);
        } else {
            continueQueue(viewer, queue);
        }
    }

    /** Fehlende bzw. unlesbare Replay-Dateien im Store markieren, damit Listen sie nicht mehr als abspielbar zeigen. */
    private void markBroken(SegmentRow segment, String key, String detail) {
        SegmentStatus status = switch (key) {
            case KEY_NOT_FOUND -> SegmentStatus.MISSING;
            case KEY_CORRUPT -> SegmentStatus.CORRUPT;
            default -> null;
        };
        if (status == null) {
            return;
        }
        String note = detail.isBlank()
                ? (status == SegmentStatus.MISSING ? "Replay-Datei fehlt" : "Replay-Datei ist beschädigt")
                : detail;
        try {
            store.updateSegmentStatus(segment.replayName(), status, truncate(note));
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Status von " + segment.replayName() + " konnte nicht gesetzt werden", e);
        }
        logger.warning("Abschnitt #" + segment.id() + " (" + segment.replayName() + ") ist " + status + ": " + note);
    }

    private void sendFailure(Player viewer, SegmentRow segment, String key, String detail) {
        String id = Long.toString(segment.id());
        String world = segment.worldName() == null ? "?" : segment.worldName();
        switch (key) {
            case KEY_NOT_FOUND -> send(viewer, key,
                    "&cDie Replay-Datei von Abschnitt &f#{id} &cfehlt. Der Abschnitt wurde als fehlend markiert.",
                    "id", id, "detail", detail);
            case KEY_CORRUPT -> send(viewer, key,
                    "&cDie Replay-Datei von Abschnitt &f#{id} &cist beschädigt und wurde als defekt markiert.",
                    "id", id, "detail", detail);
            case KEY_WORLD_MISSING -> send(viewer, key,
                    "&cDie Welt &f{world} &cvon Abschnitt &f#{id} &cist auf diesem Server nicht geladen.",
                    "id", id, "world", world, "detail", detail);
            case KEY_INCOMPATIBLE -> send(viewer, key,
                    "&cDie Wiedergabe ist auf diesem Server nicht möglich: &f{reason}",
                    "id", id, "reason", detail.isBlank() ? "unbekannt" : detail, "detail", detail);
            default -> send(viewer, key,
                    "&cDie Wiedergabe von Abschnitt &f#{id} &ckonnte nicht gestartet werden: &7{detail}",
                    "id", id, "detail", detail.isBlank() ? "unbekannter Fehler" : detail, "world", world);
        }
    }

    // ------------------------------------------------------------------ Andere Server

    private void handleRemote(Player viewer, SegmentRow segment, @Nullable SessionQueue queue) {
        UUID id = viewer.getUniqueId();
        String server = segment.serverName();
        String idText = Long.toString(segment.id());
        dropQueue(id, queue);
        if (!config.playback().crossServer()) {
            send(viewer, "playback.other-server",
                    "&eAbschnitt &f#{id} &ewurde auf &f{server} &eaufgenommen und kann nur dort abgespielt werden.",
                    "id", idText, "server", server);
            return;
        }
        HandoffRequest request = new HandoffRequest(segment.id(), segment.replayName(), server, identity.serverName(),
                queue == null ? null : queue.owner(), queue == null ? null : queue.ref(), clock.instant());
        send(viewer, "playback.handoff",
                "&7Abschnitt &f#{id} &7liegt auf &f{server}&7 - du wirst verbunden, das Replay startet dort automatisch.",
                "id", idText, "server", server);
        Duration ttl = config.playback().handoffTimeout();
        CompletableFuture<ConnectOutcome> outcome;
        try {
            outcome = tasks.runIo(() -> shared.putHandoff(id, request, ttl))
                    .thenCompose(ignored -> platform.connect(id, server))
                    .completeOnTimeout(ConnectOutcome.FAILED, CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            outcome = CompletableFuture.completedFuture(ConnectOutcome.FAILED);
        }
        outcome.whenComplete((result, error) -> tasks.runOnMain(() -> {
            if (error != null) {
                logger.log(Level.FINE, "Verbinden von " + viewer.getName() + " mit " + server + " fehlgeschlagen",
                        error);
            }
            onConnectOutcome(viewer, server, result == null ? ConnectOutcome.FAILED : result);
        }));
    }

    private void onConnectOutcome(Player viewer, String server, ConnectOutcome outcome) {
        if (!viewer.isOnline()) {
            return;
        }
        switch (outcome) {
            case SUCCESS -> logger.fine(viewer.getName() + " wird für eine Wiedergabe mit " + server + " verbunden");
            case ALREADY_CONNECTED -> {
                cancelHandoff(viewer.getUniqueId());
                send(viewer, "playback.already-connected",
                        "&cDu bist laut SimpleCloud bereits mit &f{server} &cverbunden - der Abschnitt kann nicht "
                                + "übergeben werden.", "server", server);
            }
            case SERVER_NOT_FOUND -> {
                cancelHandoff(viewer.getUniqueId());
                send(viewer, "playback.server-offline",
                        "&cDer Server &f{server} &cist gerade nicht online. Versuche es später erneut.",
                        "server", server);
            }
            default -> sendSwitchHint(viewer, server);
        }
    }

    /** Ohne (funktionierende) SimpleCloud-API: anklickbarer Hinweis auf {@code /server <name>}. */
    private void sendSwitchHint(Player viewer, String server) {
        String command = "/server " + server;
        try {
            Component line = messages.component("playback.switch-hint",
                            "&eWechsle mit &f{command} &eauf &f{server}&e - das Replay startet dort automatisch. "
                                    + "&7(Klicken)", "server", server, "command", command)
                    .clickEvent(ClickEvent.runCommand(command))
                    .hoverEvent(HoverEvent.showText(messages.component("playback.switch-hint-hover",
                            "&7Klicken, um zu &f{server} &7zu wechseln", "server", server, "command", command)));
            messages.sendComponent(viewer, line);
        } catch (RuntimeException e) {
            logger.log(Level.FINE, "Hinweis konnte nicht gesendet werden", e);
        }
    }

    private void cancelHandoff(UUID viewer) {
        runShared(() -> shared.takeHandoff(viewer));
    }

    private void checkHandoff(Player viewer) {
        UUID id = viewer.getUniqueId();
        CompletableFuture<Optional<HandoffRequest>> take;
        try {
            take = tasks.supplyIo(() -> shared.takeHandoff(id));
        } catch (RuntimeException e) {
            logger.log(Level.FINE, "Übergabe für " + viewer.getName() + " nicht abrufbar", e);
            return;
        }
        take.whenComplete((request, error) -> tasks.runOnMain(() -> {
            if (error != null) {
                logger.log(Level.FINE, "Übergabe für " + viewer.getName() + " nicht abrufbar", error);
                return;
            }
            if (request != null && request.isPresent()) {
                onHandoff(viewer, request.get());
            }
        }));
    }

    private void onHandoff(Player viewer, HandoffRequest request) {
        Instant now = clock.instant();
        Instant expires = request.createdAt().plus(config.playback().handoffTimeout());
        if (!now.isBefore(expires)) {
            logger.fine("Abgelaufene Übergabe für " + viewer.getName() + " verworfen");
            return;
        }
        UUID id = viewer.getUniqueId();
        if (!request.targetServer().equalsIgnoreCase(identity.serverName())) {
            // gehört einem anderen Server (z. B. Umweg über die Lobby) - für die Restlaufzeit zurücklegen
            Duration remaining = Duration.between(now, expires);
            runShared(() -> shared.putHandoff(id, request, remaining));
            return;
        }
        if (!viewer.isOnline()) {
            return;
        }
        send(viewer, "playback.handoff-arrived", "&7Replay &f#{id} &7startet gleich ...",
                "id", Long.toString(request.segmentId()));
        tasks.runOnMainLater(() -> {
            if (!viewer.isOnline()) {
                return;
            }
            UUID owner = request.sessionOwner();
            String ref = request.sessionRef();
            if (owner != null && ref != null) {
                playSessionFrom(viewer, owner, ref, request.segmentId());
            } else {
                playSegment(viewer, request.segmentId());
            }
        }, HANDOFF_START_DELAY_TICKS);
    }

    // ------------------------------------------------------------------ Sitzungen

    private record SessionLookup(List<SessionRow> matches, List<SegmentRow> segments) {
    }

    private void playSessionFrom(Player viewer, UUID owner, String sessionRef, @Nullable Long startSegmentId) {
        UUID id = viewer.getUniqueId();
        if (!mayWatch(viewer, owner)) {
            send(viewer, "playback.no-permission-others", "&cDu darfst nur deine eigenen Replays abspielen.");
            return;
        }
        queues.remove(id);
        String ref = sessionRef.trim().toLowerCase(Locale.ROOT);
        CompletableFuture<SessionLookup> lookup;
        try {
            lookup = tasks.supplyIo(() -> lookupSession(owner, ref));
        } catch (RuntimeException e) {
            lookup = CompletableFuture.failedFuture(e);
        }
        lookup.whenComplete((result, error) -> tasks.runOnMain(() -> {
            if (!viewer.isOnline()) {
                return;
            }
            if (error != null || result == null) {
                sendStorageError(viewer, error == null ? new IllegalStateException("keine Daten") : error);
                return;
            }
            onSessionLoaded(viewer, owner, ref, startSegmentId, result);
        }));
    }

    private SessionLookup lookupSession(UUID owner, String ref) {
        List<SessionRow> matches = store.findSessionsByRef(owner, ref, SESSION_MATCH_LIMIT);
        if (matches.size() != 1) {
            return new SessionLookup(matches, List.of());
        }
        List<SegmentRow> segments = new ArrayList<>(store.segmentsOfSession(matches.get(0).id()));
        segments.sort(Comparator.comparing(SegmentRow::startedAt).thenComparingLong(SegmentRow::id));
        return new SessionLookup(matches, segments);
    }

    private void onSessionLoaded(Player viewer, UUID owner, String ref, @Nullable Long startSegmentId,
                                 SessionLookup lookup) {
        UUID id = viewer.getUniqueId();
        if (lookup.matches().isEmpty()) {
            send(viewer, "playback.session-not-found", "&cSitzung &f{ref} &cwurde nicht gefunden.", "ref", ref);
            return;
        }
        if (lookup.matches().size() > 1) {
            String candidates = lookup.matches().stream()
                    .map(row -> longRef(row.sessionKey()))
                    .collect(Collectors.joining(", "));
            send(viewer, "playback.session-ambiguous",
                    "&cDie Referenz &f{ref} &cpasst auf mehrere Sitzungen: &f{candidates}&c. Gib mehr Zeichen an.",
                    "ref", ref, "candidates", candidates);
            return;
        }
        SessionRow session = lookup.matches().get(0);
        String displayRef = NetworkSession.shortRef(session.sessionKey());
        List<SegmentRow> ordered = lookup.segments();
        if (startSegmentId != null) {
            for (int i = 0; i < ordered.size(); i++) {
                if (ordered.get(i).id() == startSegmentId) {
                    ordered = ordered.subList(i, ordered.size());
                    break;
                }
            }
        }
        List<SegmentRow> local = new ArrayList<>();
        List<SegmentRow> remote = new ArrayList<>();
        int unavailable = 0;
        for (SegmentRow segment : ordered) {
            if (!segment.status().playable()) {
                unavailable++;
            } else if (isLocal(segment)) {
                local.add(segment);
            } else {
                remote.add(segment);
            }
        }
        if (!remote.isEmpty()) {
            send(viewer, "playback.session-other-servers",
                    "&7{count} Abschnitt(e) dieser Sitzung liegen auf anderen Servern: &f{servers}",
                    "count", Integer.toString(remote.size()), "servers", serverSummary(remote), "ref", displayRef);
        }
        if (unavailable > 0) {
            send(viewer, "playback.session-unavailable", "&7{count} Abschnitt(e) sind nicht mehr verfügbar.",
                    "count", Integer.toString(unavailable), "ref", displayRef);
        }
        if (local.isEmpty()) {
            if (!remote.isEmpty() && config.playback().crossServer()) {
                handleRemote(viewer, remote.get(0), new SessionQueue(owner, ref, displayRef, List.of()));
                return;
            }
            send(viewer, "playback.session-empty",
                    "&cDie Sitzung &f{ref} &chat auf diesem Server keine abspielbaren Abschnitte.", "ref", displayRef);
            return;
        }
        SessionQueue queue = new SessionQueue(owner, ref, displayRef, local.stream().map(SegmentRow::id).toList());
        queues.put(id, queue);
        send(viewer, "playback.session-start", "&aSpiele &f{count} &aAbschnitt(e) der Sitzung &f{ref} &aab.",
                "count", Integer.toString(local.size()), "ref", displayRef);
        requestSegment(viewer, queue.next(), queue);
    }

    // ------------------------------------------------------------------ Ende einer Wiedergabe

    /**
     * Hauptthread, {@code ReplaySessionFinishEvent}: AdvancedReplay hat den Zuschauer zurückgesetzt - Sicherung
     * verwerfen (keine doppelte Wiederherstellung) und ggf. den nächsten Abschnitt der Sitzung starten.
     */
    void handlePlaybackFinished(Player viewer) {
        UUID id = viewer.getUniqueId();
        Watch watch = watches.remove(id);
        inventory.discard(id);
        if (watch == null) {
            return;
        }
        SessionQueue queue = queues.get(id);
        if (queue == null) {
            return;
        }
        if (!viewer.isOnline()) {
            queues.remove(id);
            return;
        }
        if (!queue.hasNext()) {
            queues.remove(id);
            send(viewer, "playback.session-finished", "&aSitzung &f{ref} &avollständig abgespielt.",
                    "ref", queue.displayRef());
            return;
        }
        scheduleNext(viewer, queue);
    }

    private void scheduleNext(Player viewer, SessionQueue queue) {
        UUID id = viewer.getUniqueId();
        if (!config.playback().autoAdvance()) {
            queues.remove(id);
            send(viewer, "playback.session-next-manual", "&7Nächster Abschnitt: &f/buildreplay play {id}",
                    "id", Long.toString(queue.peek()));
            return;
        }
        send(viewer, "playback.session-next", "&7Nächster Abschnitt (&f{position}/{total}&7) startet gleich ...",
                "position", Integer.toString(queue.position() + 1), "total", Integer.toString(queue.size()));
        tasks.runOnMainLater(() -> advance(viewer, queue), NEXT_SEGMENT_DELAY_TICKS);
    }

    private void advance(Player viewer, SessionQueue queue) {
        UUID id = viewer.getUniqueId();
        if (queues.get(id) != queue || !viewer.isOnline() || !queue.hasNext()) {
            return;
        }
        requestSegment(viewer, queue.next(), queue);
    }

    /** Nach einem Fehler beim aktuellen Abschnitt mit dem nächsten weitermachen. */
    private void continueQueue(Player viewer, @Nullable SessionQueue queue) {
        if (queue == null) {
            return;
        }
        UUID id = viewer.getUniqueId();
        if (queues.get(id) != queue) {
            return;
        }
        if (!queue.hasNext()) {
            queues.remove(id);
            return;
        }
        scheduleNext(viewer, queue);
    }

    private void dropQueue(UUID viewer, @Nullable SessionQueue queue) {
        if (queue != null) {
            queues.remove(viewer, queue);
        }
    }

    private void scheduleWatchdog(Watch watch) {
        tasks.runOnMainLater(() -> watchdog(watch), WATCHDOG_PERIOD_TICKS);
    }

    /**
     * Erkennt Wiedergaben, die ohne Ende-Ereignis verschwunden sind (z. B. Fehler in AdvancedReplay beim Stoppen):
     * dann hat AR das Inventar nicht zurückgesetzt, und die Sicherung wird angewendet.
     */
    private void watchdog(Watch watch) {
        Player viewer = watch.viewer;
        UUID id = viewer.getUniqueId();
        if (watches.get(id) != watch) {
            return;
        }
        boolean online = viewer.isOnline();
        if (online && engineWatching(viewer)) {
            watch.missingChecks = 0;
            scheduleWatchdog(watch);
            return;
        }
        watch.missingChecks++;
        if (watch.missingChecks < WATCHDOG_GRACE_CHECKS) {
            scheduleWatchdog(watch);
            return;
        }
        watches.remove(id, watch);
        if (!online) {
            queues.remove(id);
            return;
        }
        if (inventory.hasBackup(id)) {
            logger.warning("Wiedergabe von " + viewer.getName() + " endete ohne Rückmeldung von AdvancedReplay - "
                    + "Inventar wird aus der Sicherung wiederhergestellt");
            if (restoreInventory(viewer)) {
                send(viewer, "playback.inventory-recovered",
                        "&eDie Wiedergabe wurde unerwartet beendet - dein Inventar wurde wiederhergestellt.");
            }
        }
        continueQueue(viewer, queues.get(id));
    }

    // ------------------------------------------------------------------ Hilfen

    /** Laufende Wiedergabe eines Zuschauers (Hauptthread). */
    private static final class Watch {
        private final Player viewer;
        private final SegmentRow segment;
        private final Instant startedAt;
        private int missingChecks;
        private boolean restoredOnQuit;

        private Watch(Player viewer, SegmentRow segment, Instant startedAt) {
            this.viewer = viewer;
            this.segment = segment;
            this.startedAt = startedAt;
        }

        @Override
        public String toString() {
            return "Watch[" + viewer.getName() + ", #" + segment.id() + " seit " + startedAt
                    + (restoredOnQuit ? ", Inventar beim Quit wiederhergestellt" : "") + "]";
        }
    }

    private boolean restoreInventory(Player viewer) {
        try {
            return inventory.restore(viewer);
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "Inventar von " + viewer.getName() + " konnte nicht wiederhergestellt werden", e);
            return false;
        }
    }

    private boolean mayWatch(Player viewer, UUID owner) {
        if (viewer.hasPermission(Permissions.OTHERS)) {
            return true;
        }
        return owner.equals(viewer.getUniqueId()) && viewer.hasPermission(Permissions.PLAY);
    }

    private boolean isLocal(SegmentRow segment) {
        return segment.serverName().equalsIgnoreCase(identity.serverName());
    }

    private boolean engineWatching(Player viewer) {
        try {
            return engine.isWatching(viewer);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void stopQuietly(Player viewer) {
        try {
            engine.stopWatching(viewer);
        } catch (RuntimeException e) {
            logger.log(Level.FINE, "Wiedergabe von " + viewer.getName() + " ließ sich nicht beenden", e);
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

    private void sendStorageError(Player viewer, Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof StorageUnavailableException) {
            send(viewer, "playback.storage-unavailable",
                    "&cDie Replay-Datenbank ist gerade nicht erreichbar. Bitte versuche es später erneut.");
        } else {
            logger.log(Level.WARNING, "Wiedergabe für " + viewer.getName() + " konnte nicht vorbereitet werden", cause);
            send(viewer, "playback.error", "&cDie Wiedergabe konnte nicht vorbereitet werden.");
        }
    }

    private void send(CommandSender to, String key, String fallback, String... placeholders) {
        try {
            messages.send(to, key, fallback, placeholders);
        } catch (RuntimeException e) {
            logger.log(Level.FINE, "Nachricht " + key + " konnte nicht gesendet werden", e);
        }
    }

    private void runShared(Runnable action) {
        try {
            sharedOps.execute(action);
        } catch (RejectedExecutionException e) {
            logger.fine("Gemeinsamer Zustand wird nicht mehr aktualisiert (I/O-Pool beendet)");
        }
    }

    static String statusText(SegmentStatus status) {
        return switch (status) {
            case SAVING -> "wird gespeichert";
            case AVAILABLE -> "verfügbar";
            case MISSING -> "Datei fehlt";
            case CORRUPT -> "beschädigt";
            case DELETED -> "gelöscht";
            case FAILED -> "fehlgeschlagen";
        };
    }

    /** {@code m:ss} bzw. {@code h:mm:ss} aus AdvancedReplay-Ticks (20 je Sekunde). */
    static String formatTicks(int ticks) {
        long seconds = Math.max(0, ticks) / 20L;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long rest = seconds % 60;
        return hours > 0
                ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, rest)
                : String.format(Locale.ROOT, "%d:%02d", minutes, rest);
    }

    private static String serverSummary(List<SegmentRow> segments) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (SegmentRow segment : segments) {
            counts.merge(segment.serverName(), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .map(entry -> entry.getKey() + " (" + entry.getValue() + ")")
                .collect(Collectors.joining(", "));
    }

    private static String longRef(String sessionKey) {
        String shortRef = NetworkSession.shortRef(sessionKey);
        String compact = sessionKey.startsWith(NetworkSession.PROVISIONAL_PREFIX)
                ? "p" + sessionKey.substring(NetworkSession.PROVISIONAL_PREFIX.length()).replace("-", "")
                : sessionKey.replace("-", "");
        return compact.length() > 12 ? compact.substring(0, 12).toLowerCase(Locale.ROOT) : shortRef;
    }

    private static String truncate(String text) {
        return text.length() <= 500 ? text : text.substring(0, 497) + "...";
    }

    private static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static String describe(Throwable error) {
        Throwable cause = unwrap(error);
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
