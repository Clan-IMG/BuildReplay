package net.techniqstone.buildReplay.simplecloud.platform;

import app.simplecloud.api.CloudApi;
import app.simplecloud.api.CloudApiOptions;
import app.simplecloud.api.event.Subscription;
import app.simplecloud.api.event.player.PlayerEventApi;
import app.simplecloud.api.player.CloudPlayer;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.techniqstone.buildReplay.config.PluginConfig;
import net.techniqstone.buildReplay.simplecloud.SimpleCloudPlatform;
import org.jspecify.annotations.Nullable;

/**
 * {@link SimpleCloudPlatform} über die SimpleCloud-Java-API (Plugin {@code simplecloud-api}). Die EINZIGE Klasse
 * in BuildReplay, die {@code app.simplecloud.api}-Typen verwendet; sie wird nur geladen, wenn
 * {@link SimpleCloudPlatforms} das Plugin und die Klassen gefunden hat.
 *
 * <p>Pro Plugin genau eine {@link CloudApi} (es gibt keine geteilte Instanz). Signaturen per {@code javap} gegen
 * {@code cloud-api-paper.jar} 0.1.0-platform.60 geprüft. Events kommen auf einem NATS-Dispatcher-Thread
 * (nur live, keine Zustellgarantie), {@link #connect}-Futures auf virtuellen Threads der API - Aufrufer wechseln für
 * Bukkit-Aufrufe selbst auf den Hauptthread.
 */
final class CloudApiPlatform implements SimpleCloudPlatform {

    private final CloudApi api;
    private final Logger logger;
    private final List<Subscription> subscriptions = new ArrayList<>();
    private volatile boolean closed;

    private CloudApiPlatform(CloudApi api, Logger logger) {
        this.api = api;
        this.logger = logger;
    }

    /**
     * Erzeugt die {@link CloudApi}. Gesetzte Konfigurationswerte haben Vorrang, sonst greifen die Standardwerte der
     * API (Umgebungsvariablen {@code SIMPLECLOUD_*}). Der Abfrage-Cache ist aus: BuildReplay nutzt die Java-API nur
     * für Connect und Events, Abfragen laufen über REST. Blockiert nicht (NATS verbindet asynchron).
     */
    static SimpleCloudPlatform create(PluginConfig.SimpleCloudSettings settings, Logger logger) {
        CloudApiOptions.Builder options = CloudApiOptions.builder();
        if (!isBlank(settings.baseUrl())) {
            options.controllerUrl(settings.baseUrl());
        }
        if (!isBlank(settings.networkId())) {
            options.networkId(settings.networkId());
        }
        if (!isBlank(settings.networkSecret())) {
            options.networkSecret(settings.networkSecret());
        }
        if (settings.connectTimeout() != null && !settings.connectTimeout().isNegative()
                && !settings.connectTimeout().isZero()) {
            options.httpConnectTimeout(settings.connectTimeout());
        }
        if (settings.requestTimeout() != null && !settings.requestTimeout().isNegative()
                && !settings.requestTimeout().isZero()) {
            options.httpReadTimeout(settings.requestTimeout());
        }
        options.disableCache();
        CloudApi api = CloudApi.create(options.build());
        logger.info("SimpleCloud-Java-API aktiv (NATS-Spielerevents, Server-Connect)");
        return new CloudApiPlatform(api, logger);
    }

    @Override
    public boolean isAvailable() {
        return !closed;
    }

    @Override
    public CompletableFuture<ConnectOutcome> connect(UUID player, String serverName) {
        Objects.requireNonNull(player, "player");
        if (closed) {
            return CompletableFuture.completedFuture(ConnectOutcome.FAILED);
        }
        if (serverName == null || serverName.isBlank()) {
            return CompletableFuture.completedFuture(ConnectOutcome.SERVER_NOT_FOUND);
        }
        String target = serverName.trim();
        try {
            return api.player().connect(player, target).handle((result, error) -> {
                if (error != null) {
                    logger.log(Level.FINE, "SimpleCloud: Verbinden von " + player + " mit " + target
                            + " fehlgeschlagen", error);
                    return ConnectOutcome.FAILED;
                }
                return outcome(result);
            });
        } catch (RuntimeException | LinkageError e) {
            logger.log(Level.FINE, "SimpleCloud: Verbinden von " + player + " mit " + target
                    + " nicht möglich", e);
            return CompletableFuture.completedFuture(ConnectOutcome.FAILED);
        }
    }

    @Override
    public void subscribe(CloudEventListener listener) {
        Objects.requireNonNull(listener, "listener");
        if (closed) {
            return;
        }
        List<Subscription> created = new ArrayList<>(3);
        try {
            PlayerEventApi events = api.event().player();
            created.add(events.onLogin(event -> deliver("Login", () -> {
                UUID player = event.getPlayerId();
                if (player != null) {
                    listener.onNetworkLogin(player, blankToNull(event.getSessionId()), timestamp(event.getTimestamp()));
                }
            })));
            created.add(events.onDisconnect(event -> deliver("Disconnect", () -> {
                UUID player = event.getPlayerId();
                if (player != null) {
                    listener.onNetworkDisconnect(player, timestamp(event.getTimestamp()),
                            Math.max(0, event.getSessionDurationSeconds()));
                }
            })));
            created.add(events.onServerSwitch(event -> deliver("ServerSwitch", () -> {
                UUID player = event.getPlayerId();
                if (player != null) {
                    listener.onServerSwitch(player, blankToNull(event.getPreviousServerName()),
                            blankToNull(event.getNewServerName()), timestamp(event.getTimestamp()));
                }
            })));
        } catch (RuntimeException | LinkageError e) {
            logger.log(Level.WARNING, "SimpleCloud-Spielerevents konnten nicht (vollständig) abonniert werden", e);
        }
        synchronized (subscriptions) {
            if (!closed) {
                subscriptions.addAll(created);
                return;
            }
        }
        created.forEach(this::closeQuietly);
    }

    /** Schließt zuerst alle Abonnements, dann die {@link CloudApi}. Mehrfacher Aufruf ist harmlos. */
    @Override
    public void close() {
        List<Subscription> toClose;
        synchronized (subscriptions) {
            if (closed) {
                return;
            }
            closed = true;
            toClose = new ArrayList<>(subscriptions);
            subscriptions.clear();
        }
        toClose.forEach(this::closeQuietly);
        try {
            api.close();
        } catch (RuntimeException | LinkageError e) {
            logger.log(Level.WARNING, "SimpleCloud-Java-API konnte nicht sauber geschlossen werden", e);
        }
    }

    private void deliver(String event, Runnable delivery) {
        if (closed) {
            return;
        }
        try {
            delivery.run();
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError fatal) {
                throw fatal;
            }
            logger.log(Level.WARNING, "Fehler beim Verarbeiten des SimpleCloud-Events " + event, t);
        }
    }

    private void closeQuietly(Subscription subscription) {
        try {
            subscription.close();
        } catch (RuntimeException | LinkageError e) {
            logger.log(Level.FINE, "SimpleCloud-Abonnement konnte nicht geschlossen werden", e);
        }
    }

    private static ConnectOutcome outcome(CloudPlayer.@Nullable ConnectResult result) {
        if (result == null) {
            return ConnectOutcome.FAILED;
        }
        return switch (result) {
            case SUCCESS -> ConnectOutcome.SUCCESS;
            case ALREADY_CONNECTED -> ConnectOutcome.ALREADY_CONNECTED;
            case SERVER_NOT_FOUND -> ConnectOutcome.SERVER_NOT_FOUND;
            case PLAYER_NOT_FOUND -> ConnectOutcome.PLAYER_NOT_FOUND;
            case CONNECTION_FAILED -> ConnectOutcome.FAILED;
            default -> ConnectOutcome.FAILED;
        };
    }

    /** {@code getTimestamp()} ist {@code Instant.toString()} (UTC, Millisekunden); unlesbar -> jetzt. */
    private static Instant timestamp(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return Instant.now();
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            return Instant.now();
        }
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }
}
