package net.techniqstone.buildReplay.recording.service.fakes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import net.techniqstone.buildReplay.recording.EngineStatus;
import net.techniqstone.buildReplay.recording.PlaybackOptions;
import net.techniqstone.buildReplay.recording.PlaybackStart;
import net.techniqstone.buildReplay.recording.RawRecording;
import net.techniqstone.buildReplay.recording.RawRecordingSink;
import net.techniqstone.buildReplay.recording.ReplayEngine;
import net.techniqstone.buildReplay.recording.ReplayEngineException;
import net.techniqstone.buildReplay.recording.SavedSegment;
import net.techniqstone.buildReplay.recording.SegmentCut;
import org.bukkit.entity.Player;

/**
 * Engine ohne AdvancedReplay: merkt sich alle Aufrufe, zählt Ticks nur auf Anweisung des Tests hoch und ruft die
 * Senke beim Stoppen mit Speichern - wie der echte Saver-Decorator - synchron auf.
 */
public final class FakeReplayEngine implements ReplayEngine {

    /** Abgeschlossene Rohaufnahme. */
    public record FakeRaw(String rawName, String playerName, UUID playerUuid, int durationTicks, int actionCount,
                          String worldName) implements RawRecording {
    }

    /** Zurückgehaltener Wiedergabestart (AR lädt noch). */
    public record Deferred(Player viewer, CompletableFuture<PlaybackStart> future) {
    }

    /** Laufende Aufnahme. */
    public static final class Running {
        public final String rawName;
        public final Player player;
        public final String worldName;
        public int tick;
        public int actions;

        Running(String rawName, Player player, String worldName, int actions) {
            this.rawName = rawName;
            this.player = player;
            this.worldName = worldName;
            this.actions = actions;
        }
    }

    public final Map<String, Running> running = new LinkedHashMap<>();
    public final List<String> started = new ArrayList<>();
    public final List<String> stoppedKept = new ArrayList<>();
    public final List<String> stoppedDiscarded = new ArrayList<>();
    public final List<List<SegmentCut>> savedCuts = new ArrayList<>();
    public final Map<String, FakeRaw> savedRaw = new HashMap<>();
    public final Set<String> stored = new HashSet<>();
    public final List<String> deleted = new ArrayList<>();
    public final List<String> played = new ArrayList<>();
    public final List<PlaybackOptions> playOptions = new ArrayList<>();
    public final Set<UUID> watching = new HashSet<>();
    public final List<UUID> stopWatchingCalls = new ArrayList<>();
    public final Map<String, PlaybackStart> playResults = new HashMap<>();

    public RawRecordingSink sink;
    public boolean available = true;
    public boolean playbackCompatible = true;
    public String incompatibilityReason;
    public int maxLengthSeconds = 3600;
    public int defaultActions = 25;
    public RuntimeException startFailure;
    public RuntimeException stopWatchingFailure;
    public boolean finishOnStopWatching = true;
    public boolean deferPlay;
    public PlaybackStart defaultPlayResult = PlaybackStart.ok(400);
    public Function<SegmentCut, SavedSegment> segmentResult =
            cut -> new SavedSegment(cut.replayName(), cut.toTick() - cut.fromTick(), "world", 2048L, true, null);
    public final List<Deferred> deferred = new ArrayList<>();
    private final List<Consumer<Player>> finishListeners = new ArrayList<>();

    // ------------------------------------------------------------------ Teststeuerung

    /** Lässt alle laufenden Aufnahmen um {@code ticks} Ticks fortschreiten. */
    public void advance(int ticks) {
        running.values().forEach(recording -> recording.tick += ticks);
    }

    public Running only() {
        if (running.size() != 1) {
            throw new IllegalStateException("Erwartet genau eine laufende Aufnahme, gefunden: " + running.keySet());
        }
        return running.values().iterator().next();
    }

    /** AdvancedReplay verwirft die Aufnahme selbst (max_length mit save_on_stop=false). */
    public void discardSilently(String rawName) {
        running.remove(rawName);
    }

    /** AdvancedReplay speichert die Aufnahme selbst (max_length mit save_on_stop=true) - Senke vom "Timer-Thread". */
    public void saveByAdvancedReplay(String rawName) {
        Running recording = running.remove(rawName);
        sink.accept(toRaw(recording));
    }

    /** Wiedergabe endet natürlich - AR hat zurückgesetzt und feuert ReplaySessionFinishEvent. */
    public void finish(Player viewer) {
        watching.remove(viewer.getUniqueId());
        List.copyOf(finishListeners).forEach(listener -> listener.accept(viewer));
    }

    /** Wiedergabe verschwindet ohne Ende-Ereignis (Fehler in AR). */
    public void vanish(Player viewer) {
        watching.remove(viewer.getUniqueId());
    }

    /** Ältesten zurückgehaltenen Start abschließen. */
    public void completeDeferred(PlaybackStart result) {
        Deferred next = deferred.remove(0);
        if (result.started()) {
            watching.add(next.viewer().getUniqueId());
        }
        next.future().complete(result);
    }

    public int finishListenerCount() {
        return finishListeners.size();
    }

    // ------------------------------------------------------------------ ReplayEngine

    @Override
    public EngineStatus status() {
        return new EngineStatus(available, true, playbackCompatible, incompatibilityReason, true, maxLengthSeconds,
                "FILE", "1.8.19", running.size());
    }

    @Override
    public void install(RawRecordingSink sink) {
        this.sink = sink;
    }

    @Override
    public void uninstall() {
        this.sink = null;
    }

    @Override
    public void startRecording(String rawName, Player player) throws ReplayEngineException {
        if (startFailure != null) {
            throw startFailure;
        }
        String world = player.getWorld() == null ? null : player.getWorld().getName();
        running.put(rawName, new Running(rawName, player, world, defaultActions));
        started.add(rawName);
    }

    @Override
    public boolean isRecording(String rawName) {
        return running.containsKey(rawName);
    }

    @Override
    public int currentTick(String rawName) {
        Running recording = running.get(rawName);
        return recording == null ? -1 : recording.tick;
    }

    @Override
    public void stopRecording(String rawName, boolean keep) {
        Running recording = running.remove(rawName);
        if (recording == null) {
            return;
        }
        if (!keep) {
            stoppedDiscarded.add(rawName);
            return;
        }
        stoppedKept.add(rawName);
        if (recording.actions > 0 && sink != null) {
            sink.accept(toRaw(recording));
        }
    }

    @Override
    public List<SavedSegment> saveSegments(RawRecording raw, List<SegmentCut> cuts) {
        savedCuts.add(List.copyOf(cuts));
        List<SavedSegment> result = new ArrayList<>();
        for (SegmentCut cut : cuts) {
            SavedSegment saved = segmentResult.apply(cut);
            if (saved.ok()) {
                stored.add(cut.replayName());
            }
            result.add(saved);
        }
        return result;
    }

    @Override
    public void saveRaw(RawRecording raw) {
        FakeRaw copy = new FakeRaw(raw.rawName(), raw.playerName(), raw.playerUuid(), raw.durationTicks(),
                raw.actionCount(), raw.worldName());
        savedRaw.put(raw.rawName(), copy);
        stored.add(raw.rawName());
    }

    @Override
    public CompletableFuture<Optional<RawRecording>> loadRaw(String rawName) {
        return CompletableFuture.completedFuture(Optional.ofNullable(savedRaw.get(rawName)));
    }

    @Override
    public boolean exists(String replayName) {
        return stored.contains(replayName);
    }

    @Override
    public void delete(String replayName) {
        deleted.add(replayName);
        stored.remove(replayName);
        savedRaw.remove(replayName);
    }

    @Override
    public Long sizeOf(String replayName) {
        return stored.contains(replayName) ? 2048L : null;
    }

    @Override
    public CompletableFuture<PlaybackStart> play(String replayName, Player viewer, PlaybackOptions options) {
        played.add(replayName);
        playOptions.add(options);
        if (deferPlay) {
            CompletableFuture<PlaybackStart> future = new CompletableFuture<>();
            deferred.add(new Deferred(viewer, future));
            return future;
        }
        PlaybackStart result = playResults.getOrDefault(replayName, defaultPlayResult);
        if (result.started()) {
            watching.add(viewer.getUniqueId());
        }
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public boolean isWatching(Player viewer) {
        return watching.contains(viewer.getUniqueId());
    }

    @Override
    public void stopWatching(Player viewer) {
        stopWatchingCalls.add(viewer.getUniqueId());
        if (stopWatchingFailure != null) {
            throw stopWatchingFailure;
        }
        if (finishOnStopWatching && watching.contains(viewer.getUniqueId())) {
            finish(viewer);
        } else {
            watching.remove(viewer.getUniqueId());
        }
    }

    @Override
    public String storageBackend() {
        return "FILE";
    }

    @Override
    public String storageRef(String replayName) {
        return "plugins/AdvancedReplay/replays/" + replayName + ".replay";
    }

    @Override
    public List<String> activeRawRecordings() {
        return List.copyOf(running.keySet());
    }

    @Override
    public List<String> storedReplayNames() {
        return List.copyOf(stored);
    }

    @Override
    public void onPlaybackFinished(Consumer<Player> listener) {
        finishListeners.add(listener);
    }

    private static FakeRaw toRaw(Running recording) {
        return new FakeRaw(recording.rawName, recording.player.getName(), recording.player.getUniqueId(),
                recording.tick, recording.actions, recording.worldName);
    }
}
