package net.techniqstone.buildReplay.simplecloud;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Zugriff auf die SimpleCloud-REST-API ({@code https://controller.simplecloud.app}). Authentifizierung mit
 * {@code X-Network-ID} + {@code X-Network-Credential} (Netzwerk-Secret; {@code Authorization: Bearer} akzeptiert
 * nur JWTs). {@code player_id} ist die Minecraft-UUID. Alle Methoden sind asynchron, nie auf dem Hauptthread
 * blockieren. Fehler: Future schlägt mit {@link SimpleCloudException} fehl (nach Wiederholungen mit Backoff).
 */
public interface SimpleCloudClient extends AutoCloseable {

    /** Ob base-url, network-id und network-secret gesetzt sind. */
    boolean isConfigured();

    /** {@code GET /v0/players/sessions?player_id=&limit=&offset=} - neueste zuerst, {@code limit} 1..100. */
    CompletableFuture<CloudSessionPage> listSessions(UUID playerId, int limit, int offset);

    /** {@code GET /v0/players/sessions/current?player_id=} - leer, wenn der Spieler nicht online ist (HTTP 404). */
    CompletableFuture<Optional<CloudSession>> currentSession(UUID playerId);

    /** {@code GET /v0/players/online}. */
    CompletableFuture<List<CloudOnlinePlayer>> onlinePlayers();

    ClientHealth health();

    @Override
    void close();
}
