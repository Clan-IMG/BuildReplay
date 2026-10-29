package net.techniqstone.buildReplay.simplecloud.rest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import net.techniqstone.buildReplay.simplecloud.CloudOnlinePlayer;
import net.techniqstone.buildReplay.simplecloud.CloudSession;
import net.techniqstone.buildReplay.simplecloud.CloudSessionPage;
import org.jspecify.annotations.Nullable;

/**
 * Liest die JSON-Antworten der SimpleCloud-REST-API (Formen live geprüft):
 * <ul>
 *     <li>{@code /v0/players/sessions}: {@code {"sessions":[...],"count":n,"total":n}}</li>
 *     <li>{@code /v0/players/sessions/current}: {@code {"session":{...},"settings":{...}}}</li>
 *     <li>{@code /v0/players/online}: {@code {"players":[...],"count":n}}</li>
 * </ul>
 * Bei aktiven Sitzungen fehlen {@code logout_time}/{@code duration_seconds}, {@code address_hash} kann fehlen;
 * Zeitstempel sind RFC 3339 in UTC mit 2-9 Nachkommastellen. Einzelne kaputte Einträge werden übersprungen
 * (Log FINE), ein unlesbarer Rahmen führt zu {@link MalformedResponseException}.
 */
final class SimpleCloudJson {

    private final Logger logger;

    SimpleCloudJson(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Antwortrahmen ist unlesbar oder unvollständig. */
    static final class MalformedResponseException extends Exception {
        MalformedResponseException(String message) {
            super(message);
        }
    }

    /**
     * {@code GET /v0/players/sessions}. {@code count} ist die Anzahl laut Server (inklusive übersprungener
     * Einträge), damit {@code offset + count} für die nächste Seite stimmt.
     */
    CloudSessionPage sessionPage(String body, UUID playerId, int offset) throws MalformedResponseException {
        JsonObject root = rootObject(body);
        JsonArray entries = requiredArray(root, "sessions");
        List<CloudSession> sessions = new ArrayList<>(entries.size());
        for (JsonElement entry : entries) {
            CloudSession session = sessionOrNull(entry, playerId);
            if (session != null) {
                sessions.add(session);
            }
        }
        int count = intOr(root, "count", entries.size());
        int total = intOr(root, "total", Math.max(0, offset) + entries.size());
        return new CloudSessionPage(sessions, Math.max(0, count), Math.max(0, total));
    }

    /** {@code GET /v0/players/sessions/current} mit Status 200. */
    Optional<CloudSession> currentSession(String body, UUID playerId) throws MalformedResponseException {
        JsonObject root = rootObject(body);
        JsonElement session = root.get("session");
        if (session == null || !session.isJsonObject()) {
            throw new MalformedResponseException("Feld 'session' fehlt oder ist kein Objekt");
        }
        CloudSession parsed = sessionOrNull(session, playerId);
        if (parsed == null) {
            throw new MalformedResponseException("Feld 'session' ist unvollständig");
        }
        return Optional.of(parsed);
    }

    /** {@code GET /v0/players/online}. Ein explizites {@code "players":null} zählt als leere Liste. */
    List<CloudOnlinePlayer> onlinePlayers(String body) throws MalformedResponseException {
        JsonObject root = rootObject(body);
        if (root.has("players") && root.get("players").isJsonNull()) {
            return List.of();
        }
        JsonArray entries = requiredArray(root, "players");
        List<CloudOnlinePlayer> players = new ArrayList<>(entries.size());
        for (JsonElement entry : entries) {
            CloudOnlinePlayer player = onlinePlayerOrNull(entry);
            if (player != null) {
                players.add(player);
            }
        }
        return List.copyOf(players);
    }

    /** Fehlermeldung aus {@code {"error":"..."}} oder {@code null}, wenn der Körper nicht so aussieht. */
    static @Nullable String errorMessage(@Nullable String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonElement root = JsonParser.parseString(body);
            if (root.isJsonObject()) {
                String error = string(root.getAsJsonObject(), "error");
                return error == null || error.isBlank() ? null : error;
            }
        } catch (RuntimeException | StackOverflowError e) {
            return null;
        }
        return null;
    }

    /** RFC 3339 / ISO 8601; zuerst {@link Instant#parse}, sonst mit Offset. */
    static Instant parseInstant(String text) {
        String value = text.trim();
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return OffsetDateTime.parse(value).toInstant();
        }
    }

    private @Nullable CloudSession sessionOrNull(JsonElement element, UUID expectedPlayer) {
        if (!element.isJsonObject()) {
            skip("Sitzung", "kein Objekt");
            return null;
        }
        JsonObject object = element.getAsJsonObject();
        String id = string(object, "id");
        if (id == null || id.isBlank()) {
            skip("Sitzung", "ohne id");
            return null;
        }
        id = id.trim();
        UUID playerId = expectedPlayer;
        String rawPlayer = string(object, "player_id");
        if (rawPlayer != null && !rawPlayer.isBlank()) {
            UUID parsed = uuidOrNull(rawPlayer);
            if (parsed == null || !parsed.equals(expectedPlayer)) {
                skip("Sitzung " + id, "player_id passt nicht zur Anfrage");
                return null;
            }
            playerId = parsed;
        }
        String rawLogin = string(object, "login_time");
        if (rawLogin == null || rawLogin.isBlank()) {
            skip("Sitzung " + id, "ohne login_time");
            return null;
        }
        Instant login;
        Instant logout = null;
        try {
            login = parseInstant(rawLogin);
            String rawLogout = string(object, "logout_time");
            if (rawLogout != null && !rawLogout.isBlank()) {
                logout = parseInstant(rawLogout);
            }
        } catch (DateTimeParseException e) {
            skip("Sitzung " + id, "Zeitstempel unlesbar");
            return null;
        }
        Long duration = longOrNull(object, "duration_seconds");
        if (duration != null && duration < 0) {
            duration = null;
        }
        return new CloudSession(id, playerId, login, logout, duration);
    }

    private @Nullable CloudOnlinePlayer onlinePlayerOrNull(JsonElement element) {
        if (!element.isJsonObject()) {
            skip("Online-Spieler", "kein Objekt");
            return null;
        }
        JsonObject object = element.getAsJsonObject();
        String rawId = string(object, "id");
        UUID uuid = rawId == null ? null : uuidOrNull(rawId);
        if (uuid == null) {
            skip("Online-Spieler", "ohne gültige id");
            return null;
        }
        String name = blankToNull(string(object, "name"));
        if (name == null) {
            name = blankToNull(string(object, "display_name"));
        }
        if (name == null) {
            logger.fine(() -> "SimpleCloud: Online-Spieler " + uuid + " ohne Namen - verwende die UUID");
            name = uuid.toString();
        }
        return new CloudOnlinePlayer(uuid, name,
                blankToNull(string(object, "session_id")),
                blankToNull(string(object, "connected_server_name")),
                blankToNull(string(object, "connected_proxy_name")));
    }

    private void skip(String what, String why) {
        logger.fine(() -> "SimpleCloud: überspringe Eintrag (" + what + "): " + why);
    }

    private static JsonObject rootObject(String body) throws MalformedResponseException {
        if (body == null || body.isBlank()) {
            throw new MalformedResponseException("leere Antwort");
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(body);
        } catch (RuntimeException | StackOverflowError e) {
            throw new MalformedResponseException("kein gültiges JSON");
        }
        if (root == null || !root.isJsonObject()) {
            throw new MalformedResponseException("JSON-Objekt erwartet");
        }
        return root.getAsJsonObject();
    }

    private static JsonArray requiredArray(JsonObject root, String key) throws MalformedResponseException {
        JsonElement value = root.get(key);
        if (value == null || !value.isJsonArray()) {
            throw new MalformedResponseException("Feld '" + key + "' fehlt oder ist keine Liste");
        }
        return value.getAsJsonArray();
    }

    private static @Nullable String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        return primitive.isString() ? primitive.getAsString() : null;
    }

    private static @Nullable Long longOrNull(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        try {
            return value.getAsLong();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int intOr(JsonObject object, String key, int fallback) {
        Long value = longOrNull(object, key);
        if (value == null || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            return fallback;
        }
        return value.intValue();
    }

    private static @Nullable UUID uuidOrNull(String raw) {
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
