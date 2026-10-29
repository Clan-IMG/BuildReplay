package net.techniqstone.buildReplay.storage.sql;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * Eine Outbox-Zeile je Vorgang: {@code {"v":1,"type":"<kürzel>","op":{...}}}. Die Nutzdaten serialisiert Gson
 * über die Record-Komponenten; Zeitpunkte als ISO-8601-Text.
 */
final class WriteOpCodec {

    static final int FORMAT_VERSION = 1;

    private static final Map<String, Class<? extends WriteOp>> TYPES = Map.of(
            "player", WriteOp.UpsertPlayer.class,
            "session", WriteOp.UpsertSession.class,
            "session-close", WriteOp.CloseSession.class,
            "session-merge", WriteOp.MergeProvisional.class,
            "visit-start", WriteOp.StartVisit.class,
            "visit-end", WriteOp.EndVisit.class,
            "recording", WriteOp.UpsertRecording.class,
            "recording-finish", WriteOp.FinishRecording.class,
            "segment", WriteOp.UpsertSegment.class,
            "segment-status", WriteOp.UpdateSegmentStatus.class);

    private final Gson gson;

    WriteOpCodec() {
        this.gson = newGson();
    }

    /** Gson mit Adapter für {@link Instant} (auch für die Aktionszusammenfassung der Abschnitte genutzt). */
    static Gson newGson() {
        return new GsonBuilder()
                .disableHtmlEscaping()
                .registerTypeAdapter(Instant.class, new InstantAdapter().nullSafe())
                .create();
    }

    Gson gson() {
        return gson;
    }

    String encode(WriteOp op) {
        JsonObject line = new JsonObject();
        line.addProperty("v", FORMAT_VERSION);
        line.addProperty("type", op.type());
        line.add("op", gson.toJsonTree(op));
        return gson.toJson(line);
    }

    /** Liest eine Zeile; wirft {@link JsonParseException} bei unbekanntem Typ, kaputtem JSON oder fehlenden Feldern. */
    WriteOp decode(String text) {
        JsonElement parsed = JsonParser.parseString(text);
        if (!parsed.isJsonObject()) {
            throw new JsonParseException("kein JSON-Objekt");
        }
        JsonObject line = parsed.getAsJsonObject();
        JsonElement typeElement = line.get("type");
        JsonElement payload = line.get("op");
        if (typeElement == null || payload == null || !payload.isJsonObject()) {
            throw new JsonParseException("type/op fehlt");
        }
        Class<? extends WriteOp> type = TYPES.get(typeElement.getAsString());
        if (type == null) {
            throw new JsonParseException("unbekannter Vorgangstyp '" + typeElement.getAsString() + "'");
        }
        WriteOp op;
        try {
            op = gson.fromJson(payload, type);
        } catch (RuntimeException e) {
            throw new JsonParseException("Vorgang nicht lesbar: " + e.getMessage(), e);
        }
        if (op == null) {
            throw new JsonParseException("leerer Vorgang");
        }
        String invalid = op.invalidReason();
        if (invalid != null) {
            throw new JsonParseException("ungültiger Vorgang: " + invalid);
        }
        return op;
    }

    private static final class InstantAdapter extends TypeAdapter<Instant> {
        @Override
        public void write(JsonWriter out, Instant value) throws IOException {
            out.value(value.toString());
        }

        @Override
        public Instant read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            String text = in.nextString();
            try {
                return Instant.parse(text);
            } catch (DateTimeParseException e) {
                throw new JsonParseException("ungültiger Zeitpunkt '" + text + "'", e);
            }
        }
    }
}
