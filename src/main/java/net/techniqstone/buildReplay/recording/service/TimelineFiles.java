package net.techniqstone.buildReplay.recording.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.techniqstone.buildReplay.activity.ActivityTimeline;
import net.techniqstone.buildReplay.activity.ActivityType;
import net.techniqstone.buildReplay.activity.ClockSample;
import net.techniqstone.buildReplay.activity.TimelineEvent;
import net.techniqstone.buildReplay.recording.ReplayNames;
import org.jspecify.annotations.Nullable;

/**
 * Zeitachsen von Rohaufnahmen, die beim Herunterfahren nicht mehr zerlegt werden konnten
 * ({@code plugins/BuildReplay/pending/<rawName>.json}). Zusammen mit der roh gespeicherten Aufnahme in
 * AdvancedReplays Speicher reicht das, um die Abschnitte beim nächsten Start nachträglich zu schneiden.
 * Blockierende Datei-I/O - nur auf dem I/O-Pool bzw. im Herunterfahren aufrufen.
 */
final class TimelineFiles {

    static final int FORMAT_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** Inhalt einer Datei. */
    record Pending(RecordingMeta meta, @Nullable String sessionKey, @Nullable Instant endedAt, int relevantActions,
                   ActivityTimeline.Snapshot timeline) {

        Pending {
            Objects.requireNonNull(meta, "meta");
            Objects.requireNonNull(timeline, "timeline");
        }
    }

    private final Path directory;

    TimelineFiles(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    Path directory() {
        return directory;
    }

    Path fileOf(String rawName) {
        if (!ReplayNames.isValid(rawName)) {
            throw new IllegalArgumentException("Ungültiger Rohname '" + rawName + "'");
        }
        return directory.resolve(rawName + ".json");
    }

    void write(Pending pending) throws IOException {
        Path target = fileOf(pending.meta().rawName());
        Files.createDirectories(directory);
        Path temp = Files.createTempFile(directory, pending.meta().rawName() + ".", ".tmp");
        try {
            Files.writeString(temp, GSON.toJson(encode(pending)), StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Leer, wenn die Datei fehlt; {@link IOException}, wenn sie unlesbar ist. */
    Optional<Pending> read(String rawName) throws IOException {
        Path file = fileOf(rawName);
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
        try {
            return Optional.of(decode(JsonParser.parseString(text).getAsJsonObject(), rawName));
        } catch (JsonParseException | IllegalStateException | IllegalArgumentException | NullPointerException e) {
            throw new IOException("Zeitachse " + file.getFileName() + " ist beschädigt: " + e.getMessage(), e);
        }
    }

    boolean exists(String rawName) {
        return Files.isRegularFile(fileOf(rawName));
    }

    /** Löscht die Datei; {@code false}, wenn sie nicht existierte oder nicht gelöscht werden konnte. */
    boolean delete(String rawName) {
        try {
            return Files.deleteIfExists(fileOf(rawName));
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    private static JsonObject encode(Pending pending) {
        RecordingMeta meta = pending.meta();
        JsonObject root = new JsonObject();
        root.addProperty("version", FORMAT_VERSION);
        root.addProperty("rawName", meta.rawName());
        root.addProperty("player", meta.player().toString());
        root.addProperty("playerName", meta.playerName());
        if (meta.worldName() != null) {
            root.addProperty("world", meta.worldName());
        }
        root.addProperty("startedAt", meta.startedAt().toEpochMilli());
        if (pending.sessionKey() != null) {
            root.addProperty("sessionKey", pending.sessionKey());
        }
        if (pending.endedAt() != null) {
            root.addProperty("endedAt", pending.endedAt().toEpochMilli());
        }
        root.addProperty("relevantActions", pending.relevantActions());
        root.addProperty("timelineStartedAt", pending.timeline().startedAt().toEpochMilli());

        JsonArray events = new JsonArray();
        for (TimelineEvent event : pending.timeline().events()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("tick", event.tick());
            entry.addProperty("at", event.at().toEpochMilli());
            entry.addProperty("type", event.type().name());
            entry.addProperty("relevant", event.relevant());
            events.add(entry);
        }
        root.add("events", events);

        JsonArray samples = new JsonArray();
        for (ClockSample sample : pending.timeline().samples()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("tick", sample.tick());
            entry.addProperty("at", sample.at().toEpochMilli());
            samples.add(entry);
        }
        root.add("samples", samples);
        return root;
    }

    private static Pending decode(JsonObject root, String expectedRawName) {
        int version = root.has("version") ? root.get("version").getAsInt() : 0;
        if (version != FORMAT_VERSION) {
            throw new IllegalArgumentException("unbekannte Formatversion " + version);
        }
        String rawName = root.get("rawName").getAsString();
        if (!rawName.equals(expectedRawName)) {
            throw new IllegalArgumentException("Datei gehört zu '" + rawName + "'");
        }
        Instant startedAt = Instant.ofEpochMilli(root.get("startedAt").getAsLong());
        RecordingMeta meta = new RecordingMeta(
                rawName,
                UUID.fromString(root.get("player").getAsString()),
                root.get("playerName").getAsString(),
                optionalString(root, "world"),
                startedAt);
        Instant timelineStart = root.has("timelineStartedAt")
                ? Instant.ofEpochMilli(root.get("timelineStartedAt").getAsLong()) : startedAt;

        List<TimelineEvent> events = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("events")) {
            JsonObject entry = element.getAsJsonObject();
            Optional<ActivityType> type = parseType(entry.get("type").getAsString());
            if (type.isEmpty()) {
                continue;
            }
            events.add(new TimelineEvent(entry.get("tick").getAsInt(), Instant.ofEpochMilli(entry.get("at").getAsLong()),
                    type.get(), entry.get("relevant").getAsBoolean()));
        }
        List<ClockSample> samples = new ArrayList<>();
        JsonArray sampleArray = root.has("samples") ? root.getAsJsonArray("samples") : new JsonArray();
        for (JsonElement element : sampleArray) {
            JsonObject entry = element.getAsJsonObject();
            samples.add(new ClockSample(entry.get("tick").getAsInt(), Instant.ofEpochMilli(entry.get("at").getAsLong())));
        }
        if (samples.isEmpty() || samples.get(0).tick() != 0) {
            samples.add(0, new ClockSample(0, timelineStart));
        }
        Instant endedAt = root.has("endedAt") ? Instant.ofEpochMilli(root.get("endedAt").getAsLong()) : null;
        int relevant = root.has("relevantActions") ? root.get("relevantActions").getAsInt()
                : (int) events.stream().filter(TimelineEvent::relevant).count();
        return new Pending(meta, optionalString(root, "sessionKey"), endedAt, relevant,
                new ActivityTimeline.Snapshot(timelineStart, List.copyOf(events), List.copyOf(samples)));
    }

    private static Optional<ActivityType> parseType(String name) {
        try {
            return Optional.of(ActivityType.valueOf(name));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static @Nullable String optionalString(JsonObject root, String key) {
        JsonElement value = root.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }
}
