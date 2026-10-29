package net.techniqstone.buildReplay.simplecloud;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * Optionale Funktionen der SimpleCloud-Java-API (Plugin {@code simplecloud-api}): Spieler auf einen anderen Server
 * verbinden und NATS-Spielerevents empfangen. Die Events sind undokumentiert, nur "live" (kein Nachholen) und
 * werden nur als Hinweis genutzt - maßgeblich bleibt die REST-API. Ohne das Plugin wird eine No-Op-Variante
 * verwendet.
 */
public interface SimpleCloudPlatform extends AutoCloseable {

    enum ConnectOutcome { SUCCESS, ALREADY_CONNECTED, SERVER_NOT_FOUND, PLAYER_NOT_FOUND, FAILED, UNSUPPORTED }

    /** Ob die Java-API geladen und eine Verbindung aufgebaut wurde. */
    boolean isAvailable();

    /** Verbindet einen Spieler netzwerkweit mit einem Server (Proxy-Name, z. B. "dev"). */
    CompletableFuture<ConnectOutcome> connect(UUID player, String serverName);

    /** Registriert einen Empfänger für NATS-Spielerevents; Aufrufe kommen auf einem Fremdthread. */
    void subscribe(CloudEventListener listener);

    @Override
    void close();

    /** Netzwerkweite Spielerevents (Hinweise, keine Zustellgarantie). */
    interface CloudEventListener {

        void onNetworkLogin(UUID player, @Nullable String sessionId, Instant timestamp);

        void onNetworkDisconnect(UUID player, Instant timestamp, long sessionDurationSeconds);

        void onServerSwitch(UUID player, @Nullable String fromServer, @Nullable String toServer, Instant timestamp);
    }

    /** Variante ohne Java-API. */
    SimpleCloudPlatform NONE = new SimpleCloudPlatform() {
        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public CompletableFuture<ConnectOutcome> connect(UUID player, String serverName) {
            return CompletableFuture.completedFuture(ConnectOutcome.UNSUPPORTED);
        }

        @Override
        public void subscribe(CloudEventListener listener) {
            // keine Events ohne Java-API
        }

        @Override
        public void close() {
            // nichts zu schließen
        }
    };
}
