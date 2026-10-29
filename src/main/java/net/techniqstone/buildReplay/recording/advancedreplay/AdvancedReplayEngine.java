package net.techniqstone.buildReplay.recording.advancedreplay;

import io.papermc.paper.event.packet.PlayerChunkLoadEvent;
import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import me.jumper251.replay.api.ReplayAPI;
import me.jumper251.replay.api.ReplaySessionFinishEvent;
import me.jumper251.replay.filesystem.ConfigManager;
import me.jumper251.replay.filesystem.saving.IReplaySaver;
import me.jumper251.replay.filesystem.saving.ReplaySaver;
import me.jumper251.replay.replaysystem.Replay;
import me.jumper251.replay.replaysystem.data.ActionData;
import me.jumper251.replay.replaysystem.data.ReplayData;
import me.jumper251.replay.replaysystem.data.types.LocationData;
import me.jumper251.replay.replaysystem.data.types.SpawnData;
import me.jumper251.replay.replaysystem.replaying.ReplayHelper;
import me.jumper251.replay.replaysystem.replaying.Replayer;
import me.jumper251.replay.replaysystem.utils.entities.IEntity;
import me.jumper251.replay.replaysystem.utils.entities.INPC;
import me.jumper251.replay.utils.ReplayManager;
import net.techniqstone.buildReplay.config.PluginConfig;
import net.techniqstone.buildReplay.core.TaskRunner;
import net.techniqstone.buildReplay.recording.EngineStatus;
import net.techniqstone.buildReplay.recording.PlaybackOptions;
import net.techniqstone.buildReplay.recording.PlaybackStart;
import net.techniqstone.buildReplay.recording.RawRecording;
import net.techniqstone.buildReplay.recording.RawRecordingSink;
import net.techniqstone.buildReplay.recording.ReplayEngine;
import net.techniqstone.buildReplay.recording.ReplayEngineException;
import net.techniqstone.buildReplay.recording.ReplayNames;
import net.techniqstone.buildReplay.recording.SavedSegment;
import net.techniqstone.buildReplay.recording.SegmentCut;
import net.techniqstone.buildReplay.recording.advancedreplay.PlaybackOverlays.ViewerOverlay;
import net.techniqstone.buildReplay.recording.advancedreplay.RawSnapshot.KnownPlayer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * {@link ReplayEngine} auf Basis von AdvancedReplay 1.8.19 (Commit dfebae7). Die einzige Klasse, über die BuildReplay
 * AR anspricht. AR-Klassen werden erst in {@link #install} berührt - die statischen Initialisierer von
 * {@code ConfigManager} und {@code DefaultReplaySaver} rufen {@code ReplaySystem.getInstance()} auf.
 *
 * <p>Laufzeit-Eingriffe in AR's Konfiguration (je {@code /replay reload} erneut angewendet):
 * {@code general.add_new_players} wird auf {@code false} gesetzt (sonst landet jeder neue Spieler in jeder
 * Einzelaufnahme), {@code recording.blocks.real_changes} auf {@code false}, wenn
 * {@code playback.force-client-side-block-changes} aktiv ist (sonst schreibt die Wiedergabe in die echte Welt).
 */
public final class AdvancedReplayEngine implements ReplayEngine, Listener {

    private static final String AR_PLUGIN = "AdvancedReplay";
    private static final String PROTOCOLLIB_PLUGIN = "ProtocolLib";
    private static final long LOAD_TIMEOUT_SECONDS = 60L;
    private static final long FIDELITY_CLEANUP_DELAY_TICKS = 40L;

    private final Plugin plugin;
    private final PluginConfig config;
    private final TaskRunner tasks;
    private final Logger logger;
    private final PlaybackOverlays overlays;

    /** Eigene laufende Pufferaufnahmen (Rohname). */
    private final Map<String, ActiveRaw> active = new ConcurrentHashMap<>();
    /** Rohnamen, die der Decorator gerade abgefangen hat (für die Sink-Garantie in {@link #stopRecording}). */
    private final Set<String> intercepted = ConcurrentHashMap.newKeySet();
    /** Von dieser Engine gestartete Wiedergaben je Zuschauer. */
    private final Map<UUID, ViewerSession> viewers = new ConcurrentHashMap<>();
    private final Set<UUID> pendingStarts = ConcurrentHashMap.newKeySet();
    private final List<Consumer<Player>> finishListeners = new CopyOnWriteArrayList<>();

    private volatile @Nullable RawRecordingSink sink;
    private volatile @Nullable SaverInterceptor interceptor;
    private volatile @Nullable BlockFidelityHook fidelityHook;
    private volatile @Nullable SegmentWriter writer;
    private volatile @Nullable Supplier<ReplayData> dataFactory;
    private volatile @Nullable File replaysDirectory;
    private volatile boolean available;
    private volatile boolean installed;
    private volatile CompatibilityProbe.Result probe = CompatibilityProbe.Result.unknown();
    private volatile String advancedReplayVersion = "unbekannt";
    private volatile String protocolLibVersion = "unbekannt";
    private boolean listenerRegistered;
    private @Nullable Boolean changedAddPlayers;
    private boolean maxLengthWarned;

    /** Laufende Pufferaufnahme. */
    private record ActiveRaw(String rawName, UUID playerUuid, String playerName, @Nullable String world,
                             Instant startedAt, Replay replay) {

        boolean recording() {
            return replay.isRecording();
        }
    }

    /** Von dieser Engine gestartete Wiedergabe. */
    private record ViewerSession(String replayName, Location returnPoint, Replayer replayer) {
    }

    /** Auf dem I/O-Thread vorbereitete Wiedergabe. */
    private record Prepared(@Nullable Replay replay, @Nullable String failureKey, @Nullable String detail,
                            @Nullable String world, @Nullable LocationData spawn, boolean spawnAtTickZero,
                            BlockTimeline timeline, int durationTicks) {

        static Prepared failed(String key, String detail) {
            return new Prepared(null, key, detail, null, null, false, BlockTimeline.empty(), 0);
        }
    }

    public AdvancedReplayEngine(Plugin plugin, PluginConfig config, TaskRunner tasks, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNull(config, "config");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.overlays = new PlaybackOverlays(tasks, logger);
    }

    // ------------------------------------------------------------------ Status

    @Override
    public EngineStatus status() {
        boolean isInstalled = installed;
        CompatibilityProbe.Result result = isInstalled ? probe
                : available ? new CompatibilityProbe.Result(false, "AdvancedReplay-Anbindung ist nicht installiert")
                : CompatibilityProbe.Result.unavailable();
        boolean clientSide = isInstalled && !ConfigManager.REAL_CHANGES;
        int maxLength = isInstalled ? ConfigManager.MAX_LENGTH : 0;
        int recording = (int) active.values().stream().filter(ActiveRaw::recording).count();
        return new EngineStatus(available, isInstalled, isInstalled && result.compatible(), result.reason(),
                clientSide, maxLength, storageBackend(), advancedReplayVersion, recording);
    }

    // ------------------------------------------------------------------ Installation

    @Override
    public void install(RawRecordingSink sink) {
        requireMainThread("install");
        this.sink = Objects.requireNonNull(sink, "sink");
        Plugin advancedReplay = Bukkit.getPluginManager().getPlugin(AR_PLUGIN);
        if (advancedReplay == null || !advancedReplay.isEnabled()) {
            available = false;
            installed = false;
            logger.warning("AdvancedReplay ist nicht aktiv - Aufnahme und Wiedergabe von Bau-Replays sind deaktiviert");
            return;
        }
        available = true;
        advancedReplayVersion = advancedReplay.getPluginMeta().getVersion();
        replaysDirectory = new File(advancedReplay.getDataFolder(), "replays");
        Plugin protocolLib = Bukkit.getPluginManager().getPlugin(PROTOCOLLIB_PLUGIN);
        protocolLibVersion = protocolLib == null ? "(nicht installiert)" : protocolLib.getPluginMeta().getVersion();
        try {
            if (dataFactory == null) {
                Supplier<ReplayData> factory = ReplayData::new;
                dataFactory = factory;
                writer = new SegmentWriter(factory, new ReplaySlicer(factory), logger);
            }
            boolean wrapped = ensureSaverWrapped();
            if (!listenerRegistered) {
                Bukkit.getPluginManager().registerEvents(this, plugin);
                listenerRegistered = true;
            }
            if (config.recording().blockFidelity()) {
                installFidelityHook();
            }
            applyRuntimeSettings(config.playback().forceClientSideBlockChanges());
            runProbe();
            checkMaxLength();
            installed = wrapped;
            if (wrapped) {
                logger.info("AdvancedReplay " + advancedReplayVersion + " angebunden (Speicher "
                        + storageBackend() + ", exakte Blockzustände " + (fidelityHook != null ? "an" : "aus")
                        + ", Wiedergabe " + (probe.compatible() ? "verfügbar" : "deaktiviert") + ")");
            }
        } catch (Throwable t) {
            installed = false;
            logger.log(Level.SEVERE, "AdvancedReplay konnte nicht angebunden werden", t);
        }
    }

    @Override
    public void uninstall() {
        requireMainThread("uninstall");
        if (!available) {
            return;
        }
        try {
            SaverInterceptor mine = interceptor;
            if (mine != null) {
                mine.detach();
                if (ReplaySaver.getReplaySaver() == mine) {
                    ReplayAPI.getInstance().registerReplaySaver(mine.delegate());
                } else {
                    logger.info("AdvancedReplay-Speicher wurde von einem anderen Plugin umhüllt - BuildReplays "
                            + "Decorator bleibt als reine Weiterleitung bestehen");
                }
            }
            BlockFidelityHook hook = fidelityHook;
            if (hook != null) {
                ReplayAPI.getInstance().unregisterHook(hook);
                HandlerList.unregisterAll(hook);
                hook.clearAll();
                fidelityHook = null;
            }
            if (listenerRegistered) {
                HandlerList.unregisterAll(this);
                listenerRegistered = false;
            }
            List<String> running = activeRawRecordings();
            if (!running.isEmpty()) {
                logger.warning("Beim Entfernen der AdvancedReplay-Anbindung laufen noch Pufferaufnahmen: " + running);
            } else if (Boolean.TRUE.equals(changedAddPlayers)) {
                ConfigManager.ADD_PLAYERS = true;
            }
            changedAddPlayers = null;
        } catch (Throwable t) {
            logger.log(Level.WARNING, "AdvancedReplay-Anbindung konnte nicht vollständig entfernt werden", t);
        } finally {
            interceptor = null;
            installed = false;
            overlays.clearAll();
            viewers.clear();
            pendingStarts.clear();
        }
    }

    /** Hauptthread: prüft, ob AR noch unseren Decorator nutzt, und umhüllt einen neu registrierten Saver. */
    private boolean ensureSaverWrapped() {
        IReplaySaver current = ReplaySaver.getReplaySaver();
        SaverInterceptor mine = interceptor;
        if (mine != null && current == mine) {
            return true;
        }
        if (current == null) {
            logger.warning("AdvancedReplay hat keinen Speicher registriert - Rohaufnahmen können nicht abgefangen werden");
            return mine != null;
        }
        if (mine != null) {
            mine.detach();
            logger.info("AdvancedReplay-Speicher wurde ersetzt (" + current.getClass().getName()
                    + ") - BuildReplay-Decorator wird neu installiert");
        }
        SaverInterceptor fresh = new SaverInterceptor(current, this::knownPlayer, this::onRawIntercepted, logger);
        ReplayAPI.getInstance().registerReplaySaver(fresh);
        interceptor = fresh;
        return true;
    }

    private void installFidelityHook() {
        if (fidelityHook != null) {
            ReplayAPI.getInstance().registerHook(fidelityHook);
            return;
        }
        BlockFidelityHook hook = new BlockFidelityHook(tasks, logger, this::isRecordingPlayer, overlays);
        ReplayAPI.getInstance().registerHook(hook);
        Bukkit.getPluginManager().registerEvents(hook, plugin);
        fidelityHook = hook;
    }

    /** Hauptthread: AR-Einstellungen erzwingen, die {@code /replay reload} zurücksetzt. */
    private void applyRuntimeSettings(boolean forceClientSide) {
        if (ConfigManager.ADD_PLAYERS) {
            ConfigManager.ADD_PLAYERS = false;
            if (changedAddPlayers == null) {
                changedAddPlayers = Boolean.TRUE;
            }
            logger.warning("AdvancedReplay general.add_new_players=true würde jeden neu beitretenden Spieler in jede "
                    + "laufende Aufnahme aufnehmen - für BuildReplays Einzelaufnahmen zur Laufzeit auf false gesetzt");
        }
        if (forceClientSide && ConfigManager.REAL_CHANGES) {
            ConfigManager.REAL_CHANGES = false;
            logger.info("AdvancedReplay recording.blocks.real_changes=true würde bei der Wiedergabe die echte Welt "
                    + "verändern - zur Laufzeit auf false gesetzt (nur clientseitige Blockänderungen)");
        }
    }

    private void runProbe() {
        String server = Bukkit.getName() + " " + Bukkit.getMinecraftVersion();
        CompatibilityProbe.Result previous = probe;
        CompatibilityProbe.Result result = CompatibilityProbe.run(advancedReplayVersion, protocolLibVersion, server);
        probe = result;
        if (!result.compatible() && (previous.compatible() || !Objects.equals(previous.reason(), result.reason()))) {
            logger.warning(result.reason());
        } else if (result.compatible() && !previous.compatible()
                && !previous.equals(CompatibilityProbe.Result.unknown())) {
            logger.info("AdvancedReplay-Wiedergabe ist wieder kompatibel");
        }
    }

    private void checkMaxLength() {
        int maxSeconds = ConfigManager.MAX_LENGTH;
        long rotationSeconds = config.recording().rotationInterval().toSeconds();
        if (!maxLengthWarned && maxSeconds <= rotationSeconds + 60) {
            maxLengthWarned = true;
            logger.warning("AdvancedReplay general.max_length=" + maxSeconds + " s ist nicht deutlich größer als "
                    + "recording.rotation-minutes (" + rotationSeconds + " s) - AR beendet Aufnahmen bei max_length "
                    + "selbst und verwirft sie bei save_on_stop=false");
        }
    }

    // ------------------------------------------------------------------ Aufnahme

    @Override
    public void startRecording(String rawName, Player player) throws ReplayEngineException {
        if (!tasks.isMainThread()) {
            throw new ReplayEngineException("startRecording nur auf dem Hauptthread");
        }
        if (!installed) {
            throw new ReplayEngineException("AdvancedReplay-Anbindung ist nicht installiert");
        }
        if (!ReplayNames.isValid(rawName) || !ReplayNames.isRaw(rawName)) {
            throw new ReplayEngineException("Ungültiger Name für eine Pufferaufnahme: " + rawName);
        }
        if (active.containsKey(rawName) || ReplayManager.activeReplays.containsKey(rawName)) {
            throw new ReplayEngineException("Aufnahme " + rawName + " läuft bereits");
        }
        if (!player.isOnline()) {
            throw new ReplayEngineException("Spieler " + player.getName() + " ist nicht online");
        }
        applyRuntimeSettings(config.playback().forceClientSideBlockChanges());
        ensureSaverWrapped();
        Replay replay;
        try {
            // Sender = Spieler: AR nutzt ihn nur für sender.getName() (Ersteller), sendet ihm keine Nachrichten.
            replay = ReplayAPI.getInstance().recordReplay(rawName, player, List.of(player));
        } catch (Throwable t) {
            Replay partial = ReplayManager.activeReplays.get(rawName);
            if (partial != null) {
                try {
                    if (partial.isRecording()) {
                        partial.getRecorder().stop(false);
                    }
                } catch (Throwable ignored) {
                    // Aufräumen nach gescheitertem Start
                }
                ReplayManager.activeReplays.remove(rawName);
            }
            throw new ReplayEngineException("AdvancedReplay konnte die Aufnahme " + rawName + " nicht starten", t);
        }
        if (replay == null || replay.getRecorder() == null) {
            throw new ReplayEngineException("AdvancedReplay hat für " + rawName + " keine Aufnahme geliefert");
        }
        active.put(rawName, new ActiveRaw(rawName, player.getUniqueId(), player.getName(),
                player.getWorld().getName(), Instant.now(), replay));
        logger.fine(() -> "Pufferaufnahme " + rawName + " für " + player.getName() + " gestartet");
    }

    @Override
    public boolean isRecording(String rawName) {
        ActiveRaw raw = rawName == null ? null : active.get(rawName);
        return raw != null && raw.recording();
    }

    @Override
    public int currentTick(String rawName) {
        ActiveRaw raw = rawName == null ? null : active.get(rawName);
        if (raw == null || !raw.recording() || raw.replay().getRecorder() == null) {
            return -1;
        }
        return raw.replay().getRecorder().getCurrentTick();
    }

    @Override
    public void stopRecording(String rawName, boolean keep) {
        requireMainThread("stopRecording");
        ActiveRaw raw = active.get(rawName);
        if (!available) {
            active.remove(rawName);
            return;
        }
        if (installed) {
            ensureSaverWrapped();
        }
        Replay replay = ReplayManager.activeReplays.get(rawName);
        if (replay == null || !replay.isRecording()) {
            active.remove(rawName);
            boolean delivered = intercepted.remove(rawName);
            if (keep && raw != null && !delivered) {
                logger.warning("Pufferaufnahme " + rawName + " war bei AdvancedReplay nicht mehr aktiv "
                        + "(max_length erreicht?) - ihre Daten sind verloren");
            }
            scheduleFidelityCleanup(raw);
            return;
        }
        intercepted.remove(rawName);
        try {
            ReplayAPI.getInstance().stopReplay(rawName, keep, true);
        } catch (Throwable t) {
            logger.log(Level.SEVERE, "AdvancedReplay konnte die Aufnahme " + rawName + " nicht regulär beenden", t);
            ReplayManager.activeReplays.remove(rawName, replay);
            replay.setRecording(false);
        } finally {
            active.remove(rawName);
        }
        if (keep && !intercepted.remove(rawName)) {
            // AR speichert leere Aufnahmen nicht (ignoreEmpty) oder ein fremder Saver hat uns umgangen:
            // Die Sink erhält die Rohaufnahme trotzdem noch während dieses Aufrufs.
            try {
                ReplayData data = replay.getRecorder() != null ? replay.getRecorder().getData() : replay.getData();
                RawSnapshot snapshot = RawSnapshot.of(rawName, data != null ? data : newReplayData(),
                        known(raw));
                deliverRaw(snapshot);
            } catch (Throwable t) {
                logger.log(Level.SEVERE, "Rohaufnahme " + rawName + " konnte nicht übergeben werden", t);
            }
        }
        scheduleFidelityCleanup(raw);
    }

    @Override
    public List<String> activeRawRecordings() {
        List<String> names = new ArrayList<>();
        for (ActiveRaw raw : active.values()) {
            if (raw.recording()) {
                names.add(raw.rawName());
            }
        }
        names.sort(null);
        return names;
    }

    /** Hauptthread: läuft für diesen Spielernamen eine BuildReplay-Pufferaufnahme? */
    private boolean isRecordingPlayer(String playerName) {
        for (ActiveRaw raw : active.values()) {
            if (raw.playerName().equals(playerName) && raw.recording()) {
                return true;
            }
        }
        return false;
    }

    private @Nullable KnownPlayer knownPlayer(String rawName) {
        return known(active.get(rawName));
    }

    private static @Nullable KnownPlayer known(@Nullable ActiveRaw raw) {
        return raw == null ? null : new KnownPlayer(raw.playerName(), raw.playerUuid(), raw.world());
    }

    /** Vom Decorator auf dem stoppenden Thread aufgerufen (Hauptthread oder AR-Timer bei max_length). */
    private void onRawIntercepted(RawSnapshot snapshot) {
        intercepted.add(snapshot.rawName());
        active.remove(snapshot.rawName());
        deliverRaw(snapshot);
    }

    private void deliverRaw(RawSnapshot snapshot) {
        RawRecordingSink target = sink;
        if (target == null) {
            throw new IllegalStateException("Keine Sink für Rohaufnahmen installiert");
        }
        target.accept(snapshot);
    }

    private void scheduleFidelityCleanup(@Nullable ActiveRaw raw) {
        BlockFidelityHook hook = fidelityHook;
        if (raw == null || hook == null) {
            return;
        }
        String name = raw.playerName();
        tasks.runOnMainLater(() -> {
            if (!isRecordingPlayer(name)) {
                hook.clearPlayer(name);
            }
        }, FIDELITY_CLEANUP_DELAY_TICKS);
    }

    // ------------------------------------------------------------------ Speichern (I/O-Thread)

    @Override
    public List<SavedSegment> saveSegments(RawRecording raw, List<SegmentCut> cuts) {
        RawSnapshot snapshot = requireSnapshot(raw);
        SaverInterceptor current = interceptor;
        SegmentWriter currentWriter = writer;
        if (current == null || currentWriter == null) {
            List<SavedSegment> failed = new ArrayList<>(cuts.size());
            for (SegmentCut cut : cuts) {
                failed.add(SegmentWriter.failed(cut.replayName(), "AdvancedReplay-Anbindung ist nicht installiert"));
            }
            return failed;
        }
        return currentWriter.saveSegments(current.delegate(), fileDirectory(), snapshot, cuts);
    }

    @Override
    public void saveRaw(RawRecording raw) {
        RawSnapshot snapshot = requireSnapshot(raw);
        SaverInterceptor current = interceptor;
        SegmentWriter currentWriter = writer;
        if (current == null || currentWriter == null) {
            throw new ReplayEngineException("AdvancedReplay-Anbindung ist nicht installiert");
        }
        currentWriter.saveRaw(current.delegate(), fileDirectory(), snapshot);
    }

    @Override
    public CompletableFuture<Optional<RawRecording>> loadRaw(String rawName) {
        SaverInterceptor current = interceptor;
        if (current == null || !ReplayNames.isValid(rawName)) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        CompletableFuture<Optional<RawRecording>> future = new CompletableFuture<>();
        IReplaySaver delegate = current.delegate();
        try {
            tasks.io().execute(() -> {
                try {
                    // AR's Datei- und DB-Saver übergeben bei Fehlern null; S3 ruft bei Fehlern gar nicht zurück.
                    delegate.loadReplay(rawName, replay -> {
                        try {
                            future.complete(replay == null || replay.getData() == null ? Optional.empty()
                                    : Optional.of(RawSnapshot.of(rawName, replay.getData(), null)));
                        } catch (Throwable t) {
                            future.complete(Optional.empty());
                        }
                    });
                } catch (Throwable t) {
                    logger.log(Level.WARNING, "Rohaufnahme " + rawName + " konnte nicht geladen werden", t);
                    future.complete(Optional.empty());
                }
            });
        } catch (RejectedExecutionException e) {
            future.complete(Optional.empty());
        }
        return future.completeOnTimeout(Optional.empty(), LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public boolean exists(String replayName) {
        SaverInterceptor current = interceptor;
        if (current == null || !ReplayNames.isValid(replayName)) {
            return false;
        }
        // Datei-Saver: Dateiprüfung; MySQL/S3: nur AR's beim Start gefüllter Cache plus eigene Speicherungen.
        return current.delegate().replayExists(replayName);
    }

    @Override
    public void delete(String replayName) {
        SaverInterceptor current = interceptor;
        // AR's Datei-Saver prüft beim Löschen keine Namen (Pfad-Traversal) - nur gültige Namen weiterreichen.
        if (current == null || !ReplayNames.isValid(replayName)) {
            return;
        }
        current.delegate().deleteReplay(replayName);
    }

    @Override
    public @Nullable Long sizeOf(String replayName) {
        if (!ReplayNames.isValid(replayName)) {
            return null;
        }
        File file = replayFile(replayName);
        return file != null && file.isFile() ? file.length() : null;
    }

    @Override
    public String storageBackend() {
        SaverInterceptor current = interceptor;
        return current == null ? StorageBackends.CUSTOM : StorageBackends.of(current.delegate());
    }

    @Override
    public @Nullable String storageRef(String replayName) {
        return StorageBackends.storageRef(storageBackend(), replayName);
    }

    @Override
    public List<String> storedReplayNames() {
        SaverInterceptor current = interceptor;
        if (current == null) {
            return List.of();
        }
        List<String> names = current.delegate().getReplays();
        return names == null ? List.of() : List.copyOf(names);
    }

    /** {@code plugins/AdvancedReplay/replays} beim Datei-Saver, sonst {@code null}. */
    private @Nullable File fileDirectory() {
        File directory = replaysDirectory;
        return directory != null && StorageBackends.FILE.equals(storageBackend()) ? directory : null;
    }

    /** Datei des Datei-Savers oder {@code null} bei anderen Speicherarten. */
    private @Nullable File replayFile(String replayName) {
        return SegmentWriter.file(fileDirectory(), replayName);
    }

    private static RawSnapshot requireSnapshot(RawRecording raw) {
        if (raw instanceof RawSnapshot snapshot) {
            return snapshot;
        }
        throw new IllegalArgumentException("Rohaufnahme stammt nicht von dieser Engine: " + raw);
    }

    private ReplayData newReplayData() {
        Supplier<ReplayData> factory = dataFactory;
        return factory != null ? factory.get() : new ReplayData();
    }

    // ------------------------------------------------------------------ Wiedergabe

    @Override
    public CompletableFuture<PlaybackStart> play(String replayName, Player viewer, PlaybackOptions options) {
        CompletableFuture<PlaybackStart> result = new CompletableFuture<>();
        tasks.runOnMain(() -> {
            try {
                startPlayback(replayName, viewer, options, result);
            } catch (Throwable t) {
                pendingStarts.remove(viewer.getUniqueId());
                logger.log(Level.WARNING, "Wiedergabe von " + replayName + " für " + viewer.getName()
                        + " konnte nicht gestartet werden", t);
                result.complete(PlaybackStart.failed("playback.start-failed", describe(t)));
            }
        });
        return result;
    }

    /** Hauptthread. */
    private void startPlayback(String replayName, Player viewer, PlaybackOptions options,
                               CompletableFuture<PlaybackStart> result) {
        SaverInterceptor current = interceptor;
        if (!installed || current == null) {
            result.complete(PlaybackStart.failed("playback.incompatible",
                    "AdvancedReplay ist nicht aktiv oder nicht angebunden"));
            return;
        }
        CompatibilityProbe.Result compatibility = probe;
        if (!compatibility.compatible()) {
            result.complete(PlaybackStart.failed("playback.incompatible",
                    Objects.requireNonNullElse(compatibility.reason(), "AdvancedReplay ist nicht kompatibel")));
            return;
        }
        UUID viewerId = viewer.getUniqueId();
        if (isWatching(viewer) || pendingStarts.contains(viewerId)) {
            result.complete(PlaybackStart.failed("playback.already-watching",
                    viewer.getName() + " sieht bereits ein Replay"));
            return;
        }
        if (!ReplayNames.isValid(replayName)) {
            result.complete(PlaybackStart.failed("playback.not-found", "Ungültiger Replay-Name: " + replayName));
            return;
        }
        String realChanges = guardRealChanges(options);
        if (realChanges != null) {
            result.complete(PlaybackStart.failed("playback.incompatible", realChanges));
            return;
        }
        pendingStarts.add(viewerId);
        IReplaySaver delegate = current.delegate();
        CompletableFuture<Replay> loaded = new CompletableFuture<>();
        try {
            tasks.io().execute(() -> {
                try {
                    // Frische Kopie laden: AR verändert ein abgespieltes Replay (Tick 0 entfernt, Aktionen geleert).
                    delegate.loadReplay(replayName, loaded::complete);
                } catch (Throwable t) {
                    loaded.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            loaded.completeExceptionally(e);
        }
        loaded.orTimeout(LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .handleAsync((replay, error) -> prepare(replayName, replay, error, delegate), tasks.io())
                .thenAcceptAsync(prepared -> finishStart(replayName, viewer, options, prepared, result),
                        tasks.mainExecutor())
                .exceptionally(error -> {
                    tasks.runOnMain(() -> {
                        pendingStarts.remove(viewerId);
                        result.complete(PlaybackStart.failed("playback.start-failed", describe(error)));
                    });
                    return null;
                });
    }

    /** I/O-Thread: geladenes Replay prüfen und die Blockzeitachse vorberechnen. */
    private Prepared prepare(String replayName, @Nullable Replay replay, @Nullable Throwable error,
                             IReplaySaver delegate) {
        if (error != null) {
            return Prepared.failed("playback.start-failed", "Replay konnte nicht geladen werden: " + describe(error));
        }
        if (replay == null || replay.getData() == null) {
            boolean exists;
            try {
                exists = delegate.replayExists(replayName);
            } catch (Throwable t) {
                exists = false;
            }
            return exists ? Prepared.failed("playback.corrupt", "Replay " + replayName + " ist nicht lesbar")
                    : Prepared.failed("playback.not-found", "Replay " + replayName + " existiert nicht");
        }
        Map<Integer, List<ActionData>> actions = replay.getData().getActions();
        if (actions == null || actions.isEmpty()) {
            return Prepared.failed("playback.corrupt", "Replay " + replayName + " enthält keine Aktionen");
        }
        LocationData spawn = null;
        boolean atZero = false;
        List<ActionData> zero = actions.get(0);
        if (zero != null) {
            for (ActionData action : zero) {
                if (action != null && action.getPacketData() instanceof SpawnData data && data.getLocation() != null) {
                    spawn = data.getLocation();
                    atZero = true;
                    break;
                }
            }
        }
        if (spawn == null) {
            outer:
            for (Integer tick : new TreeSet<>(actions.keySet())) {
                for (ActionData action : actions.get(tick)) {
                    if (action != null && action.getPacketData() instanceof SpawnData data
                            && data.getLocation() != null) {
                        spawn = data.getLocation();
                        break outer;
                    }
                }
            }
        }
        if (spawn == null || spawn.getWorld() == null) {
            return Prepared.failed("playback.corrupt", "Replay " + replayName + " enthält keinen Spieler-Spawn");
        }
        BlockTimeline timeline = BlockTimeline.fromActions(actions);
        return new Prepared(replay, null, null, spawn.getWorld(), spawn, atZero, timeline,
                replay.getData().getDuration());
    }

    /** Hauptthread: Welt prüfen, Overlay vorbereiten und AR starten. */
    private void finishStart(String replayName, Player viewer, PlaybackOptions options, Prepared prepared,
                             CompletableFuture<PlaybackStart> result) {
        UUID viewerId = viewer.getUniqueId();
        pendingStarts.remove(viewerId);
        if (prepared.failureKey() != null || prepared.replay() == null) {
            result.complete(PlaybackStart.failed(Objects.requireNonNullElse(prepared.failureKey(),
                    "playback.start-failed"), Objects.requireNonNullElse(prepared.detail(), replayName)));
            return;
        }
        if (!viewer.isOnline()) {
            result.complete(PlaybackStart.failed("playback.start-failed", viewer.getName() + " ist nicht mehr online"));
            return;
        }
        if (isWatching(viewer)) {
            result.complete(PlaybackStart.failed("playback.already-watching",
                    viewer.getName() + " sieht bereits ein Replay"));
            return;
        }
        World world = Bukkit.getWorld(Objects.requireNonNull(prepared.world()));
        if (world == null) {
            result.complete(PlaybackStart.failed("playback.world-missing", prepared.world()));
            return;
        }
        String realChanges = guardRealChanges(options);
        if (realChanges != null) {
            result.complete(PlaybackStart.failed("playback.incompatible", realChanges));
            return;
        }
        Replay replay = prepared.replay();
        Location returnPoint = viewer.getLocation();
        ViewerOverlay overlay = overlays.begin(viewer, prepared.timeline(), options.showInitialState());
        try {
            // Hauptthread: AR startet sofort (Teleport, Inventar leeren, executeTick(0)).
            replay.play(viewer);
        } catch (Throwable t) {
            overlays.discard(viewer);
            cleanupFailedStart(viewer, replay, returnPoint);
            logger.log(Level.WARNING, "AdvancedReplay konnte " + replayName + " für " + viewer.getName()
                    + " nicht starten", t);
            result.complete(PlaybackStart.failed("playback.start-failed", describe(t)));
            return;
        }
        Replayer replayer = replay.getReplayer();
        if (replayer == null || !replay.isPlaying()) {
            overlays.discard(viewer);
            result.complete(PlaybackStart.failed("playback.world-missing", prepared.world()));
            return;
        }
        overlays.attach(overlay, replayer);
        viewers.put(viewerId, new ViewerSession(replayName, returnPoint, replayer));
        if (!prepared.spawnAtTickZero() && prepared.spawn() != null) {
            // Ohne Spawn an Tick 0 teleportiert AR den Zuschauer nicht.
            LocationData spawn = prepared.spawn();
            viewer.teleport(new Location(world, spawn.getX(), spawn.getY(), spawn.getZ(), spawn.getYaw(),
                    spawn.getPitch()));
        }
        if (options.showInitialState()) {
            overlays.sendAll(viewer);
        }
        result.complete(PlaybackStart.ok(prepared.durationTicks()));
    }

    /**
     * {@code null}, wenn die Wiedergabe nur clientseitig Blöcke ändert (ggf. nach Erzwingen); sonst Begründung.
     */
    private @Nullable String guardRealChanges(PlaybackOptions options) {
        applyRuntimeSettings(options.forceClientSideBlockChanges());
        if (ConfigManager.REAL_CHANGES) {
            return "AdvancedReplay recording.blocks.real_changes=true würde bei der Wiedergabe die echte Welt "
                    + "verändern (playback.force-client-side-block-changes ist aus)";
        }
        return null;
    }

    /**
     * Hauptthread: halb registrierte Sitzung nach einer Ausnahme in {@code Replayer.start()} entfernen. Das
     * Inventar stellt der Aufrufer aus seiner eigenen Sicherung wieder her (AR hat es bereits geleert).
     */
    @SuppressWarnings("deprecation")
    private void cleanupFailedStart(Player viewer, Replay replay, Location returnPoint) {
        Replayer replayer = replay.getReplayer();
        Replayer registered = ReplayHelper.replaySessions.get(viewer.getName());
        boolean sessionStarted = registered != null && registered == replayer;
        if (sessionStarted) {
            ReplayHelper.replaySessions.remove(viewer.getName());
        }
        replay.setPlaying(false);
        if (replayer == null) {
            return;
        }
        releaseReplayer(replayer);
        if (sessionStarted) {
            if (viewer.getGameMode() != GameMode.CREATIVE && viewer.getGameMode() != GameMode.SPECTATOR) {
                viewer.setFlying(false);
                viewer.setAllowFlight(false);
            }
            if (ConfigManager.HIDE_PLAYERS) {
                for (Player other : Bukkit.getOnlinePlayers()) {
                    if (other != viewer) {
                        viewer.showPlayer(other);
                    }
                }
            }
            try {
                viewer.teleport(returnPoint);
            } catch (RuntimeException e) {
                logger.log(Level.FINE, "Zuschauer konnte nicht zurückteleportiert werden", e);
            }
        }
    }

    /** Paket-Listener, NPCs und Entities einer Wiedergabe freigeben, ohne {@code Replayer.stop()}. */
    private void releaseReplayer(Replayer replayer) {
        try {
            replayer.getSession().getPacketListener().unregister();
        } catch (Throwable ignored) {
            // nie registriert
        }
        try {
            for (INPC npc : new ArrayList<>(replayer.getNPCList().values())) {
                npc.remove();
            }
            replayer.getNPCList().clear();
        } catch (Throwable ignored) {
            // NPC-Pakete scheitern auf inkompatiblen Servern bereits beim Erzeugen
        }
        try {
            for (IEntity entity : new ArrayList<>(replayer.getEntityList().values())) {
                entity.remove();
            }
            replayer.getEntityList().clear();
        } catch (Throwable ignored) {
            // wie oben
        }
        try {
            replayer.getReplay().getData().getActions().clear();
        } catch (Throwable ignored) {
            // nichts zu leeren
        }
    }

    @Override
    public boolean isWatching(Player viewer) {
        return ReplayHelper.replaySessions.containsKey(viewer.getName());
    }

    @Override
    public void stopWatching(Player viewer) {
        requireMainThread("stopWatching");
        Replayer replayer = ReplayHelper.replaySessions.get(viewer.getName());
        if (replayer == null) {
            ViewerSession stale = viewers.remove(viewer.getUniqueId());
            overlays.end(viewer);
            if (stale != null) {
                notifyFinished(viewer);
            }
            return;
        }
        try {
            replayer.stop();
        } catch (Throwable t) {
            // z. B. NPE in Replayer.stop(), wenn der Start in executeTick(0) scheiterte ("this.run" ist null)
            logger.log(Level.WARNING, "AdvancedReplay konnte die Wiedergabe von " + viewer.getName()
                    + " nicht regulär beenden - wird manuell aufgeräumt", t);
            releaseReplayer(replayer);
            replayer.getReplay().setPlaying(false);
            try {
                // entfernt die Sitzung, stellt Inventar/Position wieder her und löst ReplaySessionFinishEvent aus
                replayer.getSession().stopSession();
            } catch (Throwable second) {
                logger.log(Level.WARNING, "Wiedergabe-Sitzung von " + viewer.getName() + " wird verworfen", second);
                ReplayHelper.replaySessions.remove(viewer.getName());
                finishSession(viewer, replayer.getReplay());
            }
        }
    }

    @Override
    public void onPlaybackFinished(Consumer<Player> listener) {
        finishListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSessionFinish(ReplaySessionFinishEvent event) {
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        finishSession(player, event.getReplay());
    }

    /**
     * Hauptthread: Overlay zurückbauen und Listener informieren (nur für Wiedergaben dieser Engine). Mit
     * {@code replay} werden verspätete Ende-Ereignisse einer älteren Sitzung desselben Zuschauers ignoriert
     * (AR entfernt die Sitzung sofort, das Ereignis folgt einen Tick später).
     */
    private void finishSession(Player player, @Nullable Replay replay) {
        UUID id = player.getUniqueId();
        ViewerSession session = viewers.get(id);
        if (session != null && replay != null && session.replayer().getReplay() != replay) {
            return;
        }
        if (session != null) {
            viewers.remove(id, session);
        }
        overlays.end(player, replay);
        if (session != null) {
            notifyFinished(player);
        }
    }

    private void notifyFinished(Player player) {
        for (Consumer<Player> listener : finishListeners) {
            try {
                listener.accept(player);
            } catch (Throwable t) {
                logger.log(Level.WARNING, "Fehler in einem Wiedergabe-Ende-Listener", t);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(PlayerChunkLoadEvent event) {
        Player player = event.getPlayer();
        if (overlays.has(player.getUniqueId())) {
            overlays.onChunkLoad(player, event.getWorld().getName(), event.getChunk().getX(), event.getChunk().getZ());
        }
    }

    /** Scheitert AR's eigenes Aufräumen beim Verlassen, bleibt kein Zustand dieser Engine zurück. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        pendingStarts.remove(id);
        if (!viewers.containsKey(id) && !overlays.has(id)) {
            return;
        }
        String name = player.getName();
        tasks.runOnMainLater(() -> {
            Replayer leftover = ReplayHelper.replaySessions.get(name);
            if (leftover != null && leftover.getWatchingPlayer() != null
                    && leftover.getWatchingPlayer().getUniqueId().equals(id)) {
                ReplayHelper.replaySessions.remove(name);
                releaseReplayer(leftover);
            }
            if (viewers.containsKey(id) || overlays.has(id)) {
                finishSession(player, null);
            }
        }, 5L);
    }

    private void requireMainThread(String operation) {
        if (!tasks.isMainThread()) {
            throw new IllegalStateException(operation + " darf nur auf dem Hauptthread aufgerufen werden");
        }
    }

    private static String describe(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof TimeoutException) {
            return "Zeitüberschreitung beim Laden des Replays";
        }
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
