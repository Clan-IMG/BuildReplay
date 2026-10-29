package net.techniqstone.buildReplay;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.techniqstone.buildReplay.activity.impl.ActivityListener;
import net.techniqstone.buildReplay.activity.impl.ActivityTracker;
import net.techniqstone.buildReplay.activity.impl.AxiomActivityBridge;
import net.techniqstone.buildReplay.activity.impl.SegmentPlanner;
import net.techniqstone.buildReplay.command.AdminActions;
import net.techniqstone.buildReplay.command.BuildReplayCommand;
import net.techniqstone.buildReplay.config.EnvResolver;
import net.techniqstone.buildReplay.config.Messages;
import net.techniqstone.buildReplay.config.PluginConfig;
import net.techniqstone.buildReplay.core.BukkitTaskRunner;
import net.techniqstone.buildReplay.maintenance.HealthService;
import net.techniqstone.buildReplay.maintenance.HealthSources;
import net.techniqstone.buildReplay.maintenance.InstanceHeartbeat;
import net.techniqstone.buildReplay.maintenance.RecoveryService;
import net.techniqstone.buildReplay.maintenance.RetentionResult;
import net.techniqstone.buildReplay.maintenance.RetentionService;
import net.techniqstone.buildReplay.playback.service.PlaybackService;
import net.techniqstone.buildReplay.recording.advancedreplay.AdvancedReplayEngine;
import net.techniqstone.buildReplay.recording.service.PlayerLifecycleListener;
import net.techniqstone.buildReplay.recording.service.RecordingService;
import net.techniqstone.buildReplay.server.ServerIdentity;
import net.techniqstone.buildReplay.session.service.SessionQueryService;
import net.techniqstone.buildReplay.session.service.SessionService;
import net.techniqstone.buildReplay.simplecloud.SimpleCloudPlatform;
import net.techniqstone.buildReplay.simplecloud.platform.SimpleCloudPlatforms;
import net.techniqstone.buildReplay.simplecloud.rest.RestSimpleCloudClient;
import net.techniqstone.buildReplay.state.SharedEvent;
import net.techniqstone.buildReplay.state.SharedState;
import net.techniqstone.buildReplay.state.redis.SharedStates;
import net.techniqstone.buildReplay.storage.model.VisitEndReason;
import net.techniqstone.buildReplay.storage.sql.SqlReplayStore;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/**
 * Hauptklasse von BuildReplay: verdrahtet alle Module und steuert ihren Lebenszyklus.
 *
 * <p><b>Start</b> ({@link #onEnable()}): Konfiguration und Texte laden, Serveridentität bestimmen, Infrastruktur
 * (MariaDB-Store, Redis-Zustand, SimpleCloud-REST und optional die Java-API) und Fachmodule (Sitzungen, Aktivität,
 * Aufnahme, Wiedergabe, Wartung) erzeugen, Listener und Befehl registrieren, Hintergrundaufgaben starten und bereits
 * anwesende Spieler übernehmen (Plugin-Reload). Die AdvancedReplay-Anbindung wird einen Tick später installiert
 * (AdvancedReplay ist über {@code depend} bereits aktiv), danach läuft die Wiederherstellung nach einem Absturz.
 *
 * <p><b>Ende</b> ({@link #onDisable()}): in umgekehrter Reihenfolge, jeder Schritt einzeln abgesichert -
 * Wiedergaben beenden (Inventare zurück), Aufnahmen abschließen, AdvancedReplay-Anbindung entfernen, Aufenthalte
 * beenden, Dienste schließen, I/O-Pool leerlaufen lassen, Datenbank-Warteschlange sichern und zuletzt die
 * Verbindungen schließen.
 */
public final class BuildReplay extends JavaPlugin {

    private static final String ADVANCED_REPLAY = "AdvancedReplay";
    private static final String COMMAND = "buildreplay";
    private static final int IO_THREADS = 8;
    private static final int IO_QUEUE_CAPACITY = 4_096;
    private static final long TICKS_PER_SECOND = 20L;
    /** So lange dürfen laufende Aufnahmen beim Herunterfahren noch direkt zerlegt werden; der Rest wird roh gesichert. */
    private static final long RECORDING_SHUTDOWN_BUDGET_MILLIS = 8_000L;
    /** Höchstens so lange läuft der I/O-Pool beim Herunterfahren noch leer. */
    private static final long IO_SHUTDOWN_WAIT_MILLIS = 5_000L;
    /** Höchstens so lange wird auf das Sichern der Datenbank-Warteschlange gewartet (danach schließt der Store selbst). */
    private static final long STORE_FLUSH_WAIT_MILLIS = 5_000L;
    /** Fehler im Sekundentakt höchstens so oft protokollieren. */
    private static final Duration TICK_ERROR_LOG_INTERVAL = Duration.ofMinutes(1);

    private final Clock clock = Clock.systemUTC();

    private @Nullable PluginConfig config;
    private @Nullable Messages messages;
    private @Nullable BukkitTaskRunner tasks;
    private @Nullable SqlReplayStore store;
    private @Nullable SharedState shared;
    private @Nullable RestSimpleCloudClient cloud;
    private @Nullable SimpleCloudPlatform platform;
    private @Nullable SessionService sessions;
    private @Nullable SessionQueryService queries;
    private @Nullable ActivityTracker activity;
    private @Nullable AdvancedReplayEngine engine;
    private @Nullable RecordingService recordings;
    private @Nullable PlaybackService playback;
    private @Nullable PlayerLifecycleListener lifecycle;
    private @Nullable AxiomActivityBridge axiom;
    private @Nullable HealthService health;
    private @Nullable RetentionService retention;
    private @Nullable InstanceHeartbeat heartbeat;
    private @Nullable RecoveryService recovery;
    private @Nullable BuildReplayCommand command;
    private @Nullable Instant lastTickError;
    private @Nullable Handler debugForwarder;

    // ------------------------------------------------------------------ Start

    @Override
    public void onEnable() {
        Logger logger = getLogger();
        Plugin advancedReplay = getServer().getPluginManager().getPlugin(ADVANCED_REPLAY);
        if (advancedReplay == null || !advancedReplay.isEnabled()) {
            logger.severe("AdvancedReplay ist nicht installiert oder konnte nicht aktiviert werden - BuildReplay "
                    + "braucht es für Aufnahme und Wiedergabe und wird deaktiviert.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        try {
            enable(logger);
        } catch (RuntimeException | LinkageError e) {
            logger.log(Level.SEVERE, "BuildReplay konnte nicht gestartet werden und wird deaktiviert", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void enable(Logger logger) {
        List<String> warnings = new ArrayList<>();
        PluginConfig cfg = readConfig(warnings);
        config = cfg;
        applyLogLevel(cfg.logLevel());
        warnings.forEach(warning -> logger.warning("Konfiguration: " + warning));
        Messages texts = Messages.load(this);
        messages = texts;
        ServerIdentity identity = ServerIdentity.detect(System.getenv(), cfg.serverNameOverride());
        String version = getPluginMeta().getVersion();
        Path dataFolder = getDataFolder().toPath();
        BukkitTaskRunner runner = new BukkitTaskRunner(this, IO_THREADS, IO_QUEUE_CAPACITY);
        tasks = runner;

        // Infrastruktur: keine davon wirft bei Ausfällen, alle haben eine lokale Rückfallebene.
        SqlReplayStore replayStore = new SqlReplayStore(cfg.database(), cfg.resilience(), dataFolder, clock, logger);
        store = replayStore;
        replayStore.start();
        SharedState sharedState = SharedStates.create(cfg.redis(), identity.instanceId(), clock, logger);
        shared = sharedState;
        RestSimpleCloudClient cloudClient = new RestSimpleCloudClient(cfg.simpleCloud(), version, clock, logger);
        cloud = cloudClient;
        SimpleCloudPlatform cloudPlatform = SimpleCloudPlatforms.create(this, cfg, logger);
        platform = cloudPlatform;

        // Fachmodule
        SessionService sessionService = new SessionService(cfg, identity, runner, clock, cloudClient, replayStore,
                sharedState, logger);
        sessions = sessionService;
        SessionQueryService queryService = new SessionQueryService(cfg, runner, clock, cloudClient, replayStore, logger);
        queries = queryService;
        ActivityTracker tracker = new ActivityTracker(cfg.activity(), clock, logger);
        activity = tracker;
        AdvancedReplayEngine replayEngine = new AdvancedReplayEngine(this, cfg, runner, logger);
        engine = replayEngine;
        RecordingService recordingService = new RecordingService(cfg, identity, runner, clock, replayEngine,
                sessionService, replayStore, sharedState, new SegmentPlanner(cfg.recording(), cfg.activity()),
                dataFolder, logger);
        recordings = recordingService;
        PlaybackService playbackService = new PlaybackService(cfg, identity, runner, clock, replayEngine, replayStore,
                sharedState, cloudPlatform, texts, dataFolder, logger);
        playback = playbackService;
        tracker.addObserver(recordingService);

        // Wartung und Zustand
        Supplier<List<UUID>> onlinePlayers = () -> getServer().getOnlinePlayers().stream()
                .map(Player::getUniqueId)
                .toList();
        HealthService healthService = new HealthService(cfg, identity, version, new HealthSources(
                cloudClient::health, cloudPlatform::isAvailable, replayStore::health, sharedState::health,
                replayEngine::status, recordingService::stats, sessionService::stats), runner, clock, logger);
        health = healthService;
        RetentionService retentionService = new RetentionService(cfg, replayStore, sharedState, replayEngine, identity,
                runner, clock, logger, onlinePlayers);
        retention = retentionService;
        InstanceHeartbeat instanceHeartbeat = new InstanceHeartbeat(cfg, sharedState, identity, version, runner, clock,
                logger);
        heartbeat = instanceHeartbeat;
        recovery = new RecoveryService(replayStore, replayEngine, recordingService, identity, runner, clock, logger);

        // Listener (die AdvancedReplay-Listener registriert die Engine bei install selbst)
        PluginManager plugins = getServer().getPluginManager();
        PlayerLifecycleListener lifecycleListener = new PlayerLifecycleListener(sessionService, tracker,
                recordingService, playbackService, clock);
        lifecycle = lifecycleListener;
        plugins.registerEvents(lifecycleListener, this);
        plugins.registerEvents(new ActivityListener(tracker, cfg.activity(), runner, clock), this);
        AxiomActivityBridge axiomBridge = new AxiomActivityBridge(tracker, cfg.activity(), runner, clock, logger);
        axiom = axiomBridge;
        if (axiomBridge.register(this)) {
            logger.info("Axiom-Bearbeitungen werden als Bauaktivität erkannt");
        }

        // Befehl
        BuildReplayCommand buildReplayCommand = new BuildReplayCommand(cfg, texts, runner, clock, queryService,
                playbackService, recordingService, healthService::report, new Admin(), logger);
        command = buildReplayCommand;
        PluginCommand pluginCommand = getCommand(COMMAND);
        if (pluginCommand == null) {
            logger.severe("Befehl /" + COMMAND + " fehlt in der plugin.yml - Befehle sind nicht verfügbar");
        } else {
            pluginCommand.setExecutor(buildReplayCommand);
            pluginCommand.setTabCompleter(buildReplayCommand);
        }

        // Netzwerkweite Hinweise (SimpleCloud-Events über NATS, BuildReplay-Events über Redis)
        cloudPlatform.subscribe(sessionService);
        sharedState.subscribe(this::onSharedEvent);

        // Hintergrundaufgaben: Sitzungsabgleich, Zustand, Herzschlag, Aufräumen (Scheduler + I/O-Pool) und der
        // Sekundentakt für AFK-Erkennung und Aufnahmen (Hauptthread)
        sessionService.start();
        healthService.start();
        instanceHeartbeat.start();
        retentionService.start();
        getServer().getScheduler().runTaskTimer(this, this::secondTick, TICKS_PER_SECOND, TICKS_PER_SECOND);

        // Bereits anwesende Spieler (Plugin-Reload): wie ein Join behandeln; Aufnahmen starten nach der Installation
        int present = 0;
        for (Player player : getServer().getOnlinePlayers()) {
            lifecycleListener.join(player);
            present++;
        }

        getServer().getScheduler().runTask(this, this::installEngine);

        logger.info("BuildReplay " + version + " gestartet auf Server '" + identity.serverName() + "' (Instanz "
                + identity.instanceId() + (identity.simpleCloudManaged() ? "" : ", ohne SimpleCloud-Umgebung")
                + ") - MariaDB " + (cfg.database().enabled() ? "aktiv" : "deaktiviert")
                + ", Redis " + (cfg.redis().enabled() ? "aktiv" : "deaktiviert")
                + ", SimpleCloud-REST " + (cloudClient.isConfigured() ? "konfiguriert" : "nicht konfiguriert")
                + ", Java-API " + (cloudPlatform.isAvailable() ? "verfügbar" : "nicht verfügbar")
                + (present > 0 ? ", " + present + " anwesende(r) Spieler übernommen" : ""));
    }

    /** Einen Tick nach dem Start: AdvancedReplay anbinden, Aufnahmen anwesender Spieler starten, Wiederherstellung. */
    private void installEngine() {
        AdvancedReplayEngine replayEngine = engine;
        RecordingService recordingService = recordings;
        RecoveryService recoveryService = recovery;
        if (!isEnabled() || replayEngine == null || recordingService == null || recoveryService == null) {
            return;
        }
        try {
            replayEngine.install(recordingService);
        } catch (RuntimeException e) {
            getLogger().log(Level.SEVERE, "AdvancedReplay konnte nicht angebunden werden - Aufnahme und Wiedergabe "
                    + "sind deaktiviert", e);
        }
        for (Player player : getServer().getOnlinePlayers()) {
            try {
                recordingService.reevaluate(player.getUniqueId());
            } catch (RuntimeException e) {
                getLogger().log(Level.WARNING, "Aufnahme für " + player.getName() + " konnte nicht gestartet werden", e);
            }
        }
        recoveryService.start();
    }

    /** Hauptthread, sekündlich: AFK-Übergänge melden, dann Aufnahmen prüfen (Rotation, Neustarts, Herzschlag). */
    private void secondTick() {
        Instant now = clock.instant();
        ActivityTracker tracker = activity;
        RecordingService recordingService = recordings;
        try {
            if (tracker != null) {
                tracker.tick(now);
            }
        } catch (RuntimeException e) {
            logTickError("AFK-Erkennung", now, e);
        }
        try {
            if (recordingService != null) {
                recordingService.tick(now);
            }
        } catch (RuntimeException e) {
            logTickError("Aufnahme-Takt", now, e);
        }
    }

    private void logTickError(String what, Instant now, RuntimeException error) {
        Instant last = lastTickError;
        if (last == null || !now.isBefore(last.plus(TICK_ERROR_LOG_INTERVAL))) {
            lastTickError = now;
            getLogger().log(Level.WARNING, what + " fehlgeschlagen (weitere Fehler für "
                    + TICK_ERROR_LOG_INTERVAL.toMinutes() + " min nur im Debug-Log)", error);
        } else {
            getLogger().log(Level.FINE, what + " fehlgeschlagen", error);
        }
    }

    /** Hintergrundthread (Redis Pub/Sub): zwischengespeicherte Sitzungslisten des betroffenen Spielers verwerfen. */
    private void onSharedEvent(SharedEvent event) {
        SessionQueryService queryService = queries;
        String player = event.data().get("player");
        if (queryService == null || player == null) {
            return;
        }
        try {
            queryService.invalidate(UUID.fromString(player));
        } catch (IllegalArgumentException e) {
            getLogger().fine("Ungültige Spieler-UUID in einem " + event.type() + "-Ereignis");
        }
    }

    // ------------------------------------------------------------------ Ende

    @Override
    public void onDisable() {
        Instant now = clock.instant();
        PlaybackService playbackService = playback;
        RecordingService recordingService = recordings;
        AdvancedReplayEngine replayEngine = engine;
        SessionService sessionService = sessions;
        if (playbackService != null) {
            step("Wiedergaben beenden", playbackService::shutdown);
        }
        if (recordingService != null) {
            step("Aufnahmen abschließen", () -> recordingService.shutdown(RECORDING_SHUTDOWN_BUDGET_MILLIS));
        }
        if (replayEngine != null) {
            step("AdvancedReplay-Anbindung entfernen", replayEngine::uninstall);
        }
        if (sessionService != null) {
            step("Aufenthalte beenden", () -> {
                for (Player player : getServer().getOnlinePlayers()) {
                    sessionService.handleQuit(player.getUniqueId(), player.getName(), now, VisitEndReason.SHUTDOWN);
                }
            });
        }
        AxiomActivityBridge axiomBridge = axiom;
        if (axiomBridge != null) {
            step("Axiom-Brücke schließen", axiomBridge::close);
        }
        closeQuietly("Wiederherstellung", recovery);
        closeQuietly("Aufräumen", retention);
        closeQuietly("Herzschlag", heartbeat);
        closeQuietly("Zustandsabfrage", health);
        closeQuietly("Sitzungsdienst", sessionService);
        BukkitTaskRunner runner = tasks;
        if (runner != null) {
            // Vor dem Schließen der Verbindungen: eingereihte Redis-/Store-Aufrufe (Präsenz, Leader) laufen noch durch
            step("Hintergrundaufgaben beenden", () -> runner.shutdown(IO_SHUTDOWN_WAIT_MILLIS));
        }
        SqlReplayStore replayStore = store;
        if (replayStore != null) {
            step("Datenbank-Warteschlange sichern", () -> awaitFlush(replayStore));
        }
        closeQuietly("MariaDB", replayStore);
        closeQuietly("Redis", shared);
        closeQuietly("SimpleCloud-Java-API", platform);
        closeQuietly("SimpleCloud-REST", cloud);
        Handler forwarder = debugForwarder;
        if (forwarder != null) {
            getLogger().removeHandler(forwarder);
            debugForwarder = null;
        }
        clearReferences();
    }

    private void awaitFlush(SqlReplayStore replayStore) {
        try {
            replayStore.flush().get(STORE_FLUSH_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            getLogger().warning("Datenbank-Warteschlange nach " + STORE_FLUSH_WAIT_MILLIS + " ms noch nicht gesichert"
                    + " - der Rest wird beim Schließen in die Outbox geschrieben");
        } catch (ExecutionException e) {
            getLogger().log(Level.WARNING, "Datenbank-Warteschlange konnte nicht gesichert werden", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void step(String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | LinkageError e) {
            getLogger().log(Level.WARNING, "Beim Herunterfahren fehlgeschlagen: " + what, e);
        }
    }

    private void closeQuietly(String what, @Nullable AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        step(what + " schließen", () -> {
            try {
                closeable.close();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private void clearReferences() {
        command = null;
        recovery = null;
        heartbeat = null;
        retention = null;
        health = null;
        axiom = null;
        lifecycle = null;
        playback = null;
        recordings = null;
        engine = null;
        activity = null;
        queries = null;
        sessions = null;
        platform = null;
        cloud = null;
        shared = null;
        store = null;
        tasks = null;
        messages = null;
        config = null;
    }

    // ------------------------------------------------------------------ Konfiguration

    /** Liest config.yml (legt sie beim ersten Start aus dem Jar an); Probleme landen in {@code warnings}. */
    private PluginConfig readConfig(List<String> warnings) {
        saveDefaultConfig();
        reloadConfig();
        return PluginConfig.from(getConfig(), EnvResolver.system(), warnings);
    }

    /**
     * Protokollstufe von BuildReplay ({@code logging.level}). Paper gibt JUL-Meldungen unter INFO nicht aus; bei
     * DEBUG reicht {@link DebugForwarder} sie daher als INFO mit Kennzeichnung {@code [DEBUG]} weiter.
     */
    private void applyLogLevel(Level level) {
        Logger logger = getLogger();
        try {
            logger.setLevel(level);
            boolean debug = level.intValue() < Level.INFO.intValue();
            if (debug && debugForwarder == null) {
                DebugForwarder forwarder = new DebugForwarder(logger);
                logger.addHandler(forwarder);
                debugForwarder = forwarder;
            } else if (!debug && debugForwarder != null) {
                logger.removeHandler(debugForwarder);
                debugForwarder = null;
            }
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Protokollstufe " + level + " konnte nicht gesetzt werden", e);
        }
    }

    /** Gibt Meldungen unter INFO (FINE, FINER, FINEST) als INFO mit Kennzeichnung über denselben Logger aus. */
    private static final class DebugForwarder extends Handler {

        private final Logger target;

        DebugForwarder(Logger target) {
            this.target = target;
        }

        @Override
        public void publish(LogRecord record) {
            if (record == null || record.getLevel().intValue() >= Level.INFO.intValue()) {
                return;
            }
            LogRecord copy = new LogRecord(Level.INFO, "[DEBUG] " + record.getMessage());
            copy.setParameters(record.getParameters());
            copy.setThrown(record.getThrown());
            copy.setLoggerName(record.getLoggerName());
            copy.setResourceBundle(record.getResourceBundle());
            target.log(copy);
        }

        @Override
        public void flush() {
            // nichts gepuffert
        }

        @Override
        public void close() {
            // nichts zu schließen
        }
    }

    /** Einstellungen, die nur beim Start gelesen werden: nach einem Reload als Hinweis melden. */
    private static List<String> restartRequired(PluginConfig previous, PluginConfig fresh) {
        List<String> notes = new ArrayList<>();
        String suffix = ": Änderungen werden erst nach einem Neustart des Servers wirksam";
        if (!previous.serverNameOverride().equals(fresh.serverNameOverride())) {
            notes.add("server-name" + suffix);
        }
        if (!previous.simpleCloud().equals(fresh.simpleCloud())) {
            notes.add("simplecloud" + suffix);
        }
        if (!previous.database().equals(fresh.database())) {
            notes.add("database" + suffix);
        }
        if (!previous.redis().equals(fresh.redis())) {
            notes.add("redis" + suffix);
        }
        if (!previous.recording().equals(fresh.recording())) {
            notes.add("recording" + suffix);
        }
        if (!previous.activity().equals(fresh.activity())) {
            notes.add("activity" + suffix);
        }
        if (!previous.playback().equals(fresh.playback())) {
            notes.add("playback" + suffix);
        }
        PluginConfig.ResilienceSettings before = previous.resilience();
        PluginConfig.ResilienceSettings after = fresh.resilience();
        // health-check-seconds und heartbeat-seconds übernehmen Zustandsabfrage und Herzschlag sofort
        PluginConfig.ResilienceSettings comparable = new PluginConfig.ResilienceSettings(after.outboxMaxEntries(),
                after.outboxFlushInterval(), before.healthCheckInterval(), after.provisionalSessions(),
                after.presenceTtl(), after.switchGrace(), before.heartbeatInterval(), after.leaderLease());
        if (!before.equals(comparable)) {
            notes.add("resilience (außer health-check-seconds und heartbeat-seconds)" + suffix);
        }
        return notes;
    }

    // ------------------------------------------------------------------ Admin-Aktionen

    /** {@code /buildreplay admin reload|cleanup|sync}. */
    private final class Admin implements AdminActions {

        /** Hauptthread (Befehl): config.yml und messages.yml neu lesen und an die Module weitergeben, die das können. */
        @Override
        public CompletableFuture<List<String>> reload() {
            PluginConfig previous = config;
            Messages texts = messages;
            if (previous == null || texts == null) {
                return CompletableFuture.failedFuture(new IllegalStateException("BuildReplay ist nicht aktiv"));
            }
            try {
                List<String> warnings = new ArrayList<>();
                PluginConfig fresh = readConfig(warnings);
                texts.reload(BuildReplay.this);
                applyLogLevel(fresh.logLevel());
                if (command != null) {
                    command.updateConfig(fresh);
                }
                if (health != null) {
                    health.updateConfig(fresh);
                }
                if (retention != null) {
                    retention.updateConfig(fresh);
                }
                if (heartbeat != null) {
                    heartbeat.updateConfig(fresh);
                }
                config = fresh;
                warnings.addAll(restartRequired(previous, fresh));
                warnings.forEach(warning -> getLogger().warning("Konfiguration: " + warning));
                getLogger().info("config.yml und messages.yml neu geladen");
                return CompletableFuture.completedFuture(List.copyOf(warnings));
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        @Override
        public CompletableFuture<RetentionResult> cleanup(boolean dryRun) {
            RetentionService retentionService = retention;
            if (retentionService == null) {
                return CompletableFuture.failedFuture(new IllegalStateException("BuildReplay ist nicht aktiv"));
            }
            return retentionService.runNow(dryRun);
        }

        /** Erst die Datenbank-Warteschlange sichern, dann den Sitzungsabgleich sofort ausführen. */
        @Override
        public CompletableFuture<Void> syncNow() {
            SqlReplayStore replayStore = store;
            SessionService sessionService = sessions;
            HealthService healthService = health;
            if (replayStore == null || sessionService == null) {
                return CompletableFuture.failedFuture(new IllegalStateException("BuildReplay ist nicht aktiv"));
            }
            CompletableFuture<@Nullable Throwable> flushed = replayStore.flush().handle((ignored, error) -> error);
            return flushed.thenCompose(flushError -> sessionService.reconcileNow().thenRun(() -> {
                if (flushError != null) {
                    throw flushError instanceof CompletionException completion ? completion
                            : new CompletionException(flushError);
                }
            })).whenComplete((ignored, error) -> {
                if (healthService != null) {
                    healthService.refreshAsync();
                }
            });
        }
    }
}
