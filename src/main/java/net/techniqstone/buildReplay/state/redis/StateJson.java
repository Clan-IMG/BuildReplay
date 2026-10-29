package net.techniqstone.buildReplay.state.redis;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.techniqstone.buildReplay.state.ActivityStamp;
import net.techniqstone.buildReplay.state.CachedSession;
import net.techniqstone.buildReplay.state.HandoffRequest;
import net.techniqstone.buildReplay.state.InstanceInfo;
import net.techniqstone.buildReplay.state.PresenceInfo;
import net.techniqstone.buildReplay.state.SharedEvent;
import org.jspecify.annotations.Nullable;

/**
 * JSON-Abbildung der Zustandsdatensätze für Redis (Gson; {@link Instant} als ISO-8601-String, {@link UUID} als
 * Standard-String). Ungültige oder unvollständige Einträge gelten als nicht vorhanden und werden höchstens einmal
 * pro Minute gemeldet - ein fremder oder veralteter Eintrag darf BuildReplay nie stören.
 */
final class StateJson {

    private static final long WARN_INTERVAL_NANOS = 60_000_000_000L;

    private final Gson gson;
    private final Logger logger;
    private volatile long lastWarning = System.nanoTime() - WARN_INTERVAL_NANOS;

    StateJson(Logger logger) {
        this.logger = logger;
        this.gson = new GsonBuilder()
                .registerTypeAdapter(Instant.class, new InstantAdapter().nullSafe())
                .registerTypeAdapter(UUID.class, new UuidAdapter().nullSafe())
                .disableHtmlEscaping()
                .create();
    }

    String encode(Object value) {
        return gson.toJson(value);
    }

    @Nullable CachedSession session(@Nullable String json) {
        return decode(json, CachedSession.class, s -> s.sessionKey() != null && s.source() != null
                && s.resolvedBy() != null && s.resolvedAt() != null);
    }

    @Nullable PresenceInfo presence(@Nullable String json) {
        return decode(json, PresenceInfo.class, p -> p.serverName() != null && p.instanceId() != null
                && p.since() != null);
    }

    @Nullable ActivityStamp activity(@Nullable String json) {
        return decode(json, ActivityStamp.class, a -> a.lastInput() != null && a.serverName() != null);
    }

    @Nullable InstanceInfo instance(@Nullable String json) {
        return decode(json, InstanceInfo.class, i -> i.instanceId() != null && i.serverName() != null
                && i.pluginVersion() != null && i.startedAt() != null && i.lastBeat() != null);
    }

    @Nullable HandoffRequest handoff(@Nullable String json) {
        return decode(json, HandoffRequest.class, h -> h.replayName() != null && h.targetServer() != null
                && h.requestedOn() != null && h.createdAt() != null);
    }

    @Nullable SharedEvent event(@Nullable String json) {
        return decode(json, SharedEvent.class, e -> e.type() != null && e.originInstance() != null);
    }

    private <T> @Nullable T decode(@Nullable String json, Class<T> type, Predicate<T> complete) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            T value = gson.fromJson(json, type);
            if (value != null && complete.test(value)) {
                return value;
            }
            warn(type, "Pflichtfelder fehlen", null);
        } catch (RuntimeException e) {
            // JsonParseException, aber auch Fehler aus kompakten Record-Konstruktoren (z. B. Map.copyOf(null))
            warn(type, e.getClass().getSimpleName(), e);
        }
        return null;
    }

    private void warn(Class<?> type, String reason, @Nullable Throwable error) {
        long now = System.nanoTime();
        if (now - lastWarning >= WARN_INTERVAL_NANOS) {
            lastWarning = now;
            logger.log(Level.WARNING, "Ungültiger " + type.getSimpleName() + "-Eintrag in Redis wird ignoriert ("
                    + reason + ")", error);
        }
    }

    /** ISO-8601, z. B. {@code 2026-10-29T18:00:00Z}. */
    private static final class InstantAdapter extends TypeAdapter<Instant> {
        @Override
        public void write(JsonWriter out, Instant value) throws IOException {
            out.value(value.toString());
        }

        @Override
        public Instant read(JsonReader in) throws IOException {
            return Instant.parse(in.nextString());
        }
    }

    private static final class UuidAdapter extends TypeAdapter<UUID> {
        @Override
        public void write(JsonWriter out, UUID value) throws IOException {
            out.value(value.toString());
        }

        @Override
        public UUID read(JsonReader in) throws IOException {
            return UUID.fromString(in.nextString());
        }
    }
}
