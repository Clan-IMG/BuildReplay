package net.techniqstone.buildReplay.recording.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.techniqstone.buildReplay.activity.ActivityTimeline;
import net.techniqstone.buildReplay.activity.ActivityType;
import net.techniqstone.buildReplay.activity.SegmentPlan;
import net.techniqstone.buildReplay.activity.SegmentPlanning;
import net.techniqstone.buildReplay.recording.RawRecording;
import net.techniqstone.buildReplay.recording.ReplayEngine;
import net.techniqstone.buildReplay.recording.ReplayNames;
import net.techniqstone.buildReplay.recording.SavedSegment;
import net.techniqstone.buildReplay.recording.SegmentCut;
import net.techniqstone.buildReplay.server.ServerIdentity;
import net.techniqstone.buildReplay.state.SharedEvent;
import net.techniqstone.buildReplay.storage.ReplayStore;
import net.techniqstone.buildReplay.storage.model.RecordingStatus;
import net.techniqstone.buildReplay.storage.model.SegmentStatus;
import net.techniqstone.buildReplay.storage.model.SegmentUpsert;
import org.jspecify.annotations.Nullable;

/**
 * Zerlegt eine abgeschlossene Rohaufnahme: Abschnitte planen, über die Engine ausschneiden und als eigene
 * AdvancedReplay-Replays speichern, je gespeichertem Abschnitt eine Store-Zeile anlegen und die anderen Instanzen
 * benachrichtigen. Läuft auf dem I/O-Pool (beim Herunterfahren direkt auf dem Hauptthread).
 */
final class SegmentFinalizer {

    static final int MAX_ERROR_LENGTH = 500;
    private static final long MILLIS_PER_TICK = 50L;

    /** Ergebnis: Endstatus der Rohaufnahme, Zahl gespeicherter Abschnitte, Fehlertext. */
    record Outcome(RecordingStatus status, int savedSegments, @Nullable String error) {
    }

    private record Planned(SegmentPlan plan, SegmentCut cut) {
    }

    private final SegmentPlanning planner;
    private final ReplayEngine engine;
    private final ReplayStore store;
    private final ServerIdentity identity;
    private final Consumer<SharedEvent> publisher;
    private final Logger logger;

    SegmentFinalizer(SegmentPlanning planner, ReplayEngine engine, ReplayStore store, ServerIdentity identity,
                     Consumer<SharedEvent> publisher, Logger logger) {
        this.planner = planner;
        this.engine = engine;
        this.store = store;
        this.identity = identity;
        this.publisher = publisher;
        this.logger = logger;
    }

    /**
     * @param sessionKey wird nur abgefragt, wenn es tatsächlich Abschnitte gibt (kann blockierend warten)
     */
    Outcome finalizeRaw(RecordingMeta meta, RawRecording raw, ActivityTimeline.Snapshot timeline,
                        Supplier<String> sessionKey) {
        int endTick = Math.max(0, raw.durationTicks());
        List<SegmentPlan> plans = planner.plan(timeline.events(), endTick);
        List<Planned> planned = new ArrayList<>();
        for (SegmentPlan plan : plans) {
            int from = Math.max(0, plan.fromTick());
            int to = endTick > 0 ? Math.min(plan.toTick(), endTick) : plan.toTick();
            if (to <= from) {
                logger.fine("Leerer Abschnitt " + plan.fromTick() + ".." + plan.toTick() + " in " + meta.rawName()
                        + " übersprungen");
                continue;
            }
            planned.add(new Planned(plan, new SegmentCut(ReplayNames.newSegmentName(), from, to)));
        }
        if (planned.isEmpty()) {
            return new Outcome(RecordingStatus.DISCARDED, 0, null);
        }

        String key = sessionKey.get();
        List<SavedSegment> results = engine.saveSegments(raw, planned.stream().map(Planned::cut).toList());
        Map<String, SavedSegment> byName = new HashMap<>();
        if (results != null) {
            for (SavedSegment result : results) {
                byName.put(result.replayName(), result);
            }
        }
        String backend = engine.storageBackend();
        int saved = 0;
        List<String> errors = new ArrayList<>();
        for (Planned entry : planned) {
            String name = entry.cut().replayName();
            SavedSegment result = byName.get(name);
            if (result == null || !result.ok()) {
                String reason = result == null ? "nicht gespeichert" : String.valueOf(result.error());
                errors.add(name + ": " + reason);
                logger.warning("Abschnitt " + name + " aus " + meta.rawName() + " konnte nicht gespeichert werden: "
                        + reason);
                continue;
            }
            SegmentUpsert upsert = toUpsert(meta, raw, timeline, entry, result, key, backend);
            store.upsertSegment(upsert);
            publish(upsert);
            saved++;
        }
        if (saved == 0) {
            return new Outcome(RecordingStatus.FAILED, 0, truncate("Kein Abschnitt gespeichert: " + String.join("; ", errors)));
        }
        String error = errors.isEmpty() ? null
                : truncate("Teilweise fehlgeschlagen: " + String.join("; ", errors));
        return new Outcome(RecordingStatus.FINALIZED, saved, error);
    }

    private SegmentUpsert toUpsert(RecordingMeta meta, RawRecording raw, ActivityTimeline.Snapshot timeline,
                                   Planned entry, SavedSegment result, String sessionKey, String backend) {
        SegmentPlan plan = entry.plan();
        SegmentCut cut = entry.cut();
        int firstAction = clamp(plan.firstActionTick(), cut.fromTick(), cut.toTick());
        int lastAction = clamp(plan.lastActionTick(), firstAction, cut.toTick());
        String world = firstNonNull(result.worldName(), raw.worldName(), meta.worldName());
        Long size = result.sizeBytes() != null ? result.sizeBytes() : safeSize(cut.replayName());
        int duration = result.durationTicks() > 0 ? result.durationTicks() : cut.toTick() - cut.fromTick();
        Instant startedAt = timeline.timeOfTick(cut.fromTick());
        Instant endedAt = timeline.timeOfTick(cut.toTick());
        return new SegmentUpsert(
                cut.replayName(),
                sessionKey,
                meta.rawName(),
                meta.player(),
                meta.playerName(),
                identity.serverName(),
                world,
                backend,
                safeStorageRef(cut.replayName()),
                startedAt,
                endedAt,
                timeline.timeOfTick(firstAction),
                timeline.timeOfTick(lastAction),
                duration,
                toMillis(firstAction - cut.fromTick()),
                toMillis(cut.toTick() - lastAction),
                plan.relevantActions(),
                summary(plan.counts()),
                SegmentStatus.AVAILABLE,
                size);
    }

    private void publish(SegmentUpsert upsert) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("replayName", upsert.replayName());
        data.put("sessionKey", upsert.sessionKey());
        data.put("player", upsert.playerUuid().toString());
        data.put("server", upsert.serverName());
        data.put("durationTicks", Integer.toString(upsert.durationTicks()));
        try {
            publisher.accept(new SharedEvent(SharedEvent.SEGMENT_SAVED, identity.instanceId(), data));
        } catch (RuntimeException e) {
            logger.log(Level.FINE, "Benachrichtigung über " + upsert.replayName() + " fehlgeschlagen", e);
        }
    }

    private @Nullable String safeStorageRef(String replayName) {
        try {
            return engine.storageRef(replayName);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private @Nullable Long safeSize(String replayName) {
        try {
            return engine.sizeOf(replayName);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static Map<String, Integer> summary(Map<ActivityType, Integer> counts) {
        Map<String, Integer> result = new LinkedHashMap<>();
        counts.forEach((type, count) -> {
            if (type != null && count != null && count > 0) {
                result.put(type.configKey(), count);
            }
        });
        return result;
    }

    static String truncate(String text) {
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH - 3) + "...";
    }

    private static int toMillis(int ticks) {
        long millis = Math.max(0L, ticks) * MILLIS_PER_TICK;
        return (int) Math.min(Integer.MAX_VALUE, millis);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @SafeVarargs
    private static <T> @Nullable T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
