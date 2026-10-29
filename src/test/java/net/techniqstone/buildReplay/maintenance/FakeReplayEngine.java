package net.techniqstone.buildReplay.maintenance;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.techniqstone.buildReplay.recording.EngineStatus;
import net.techniqstone.buildReplay.recording.PlaybackOptions;
import net.techniqstone.buildReplay.recording.PlaybackStart;
import net.techniqstone.buildReplay.recording.RawRecording;
import net.techniqstone.buildReplay.recording.RawRecordingSink;
import net.techniqstone.buildReplay.recording.ReplayEngine;
import net.techniqstone.buildReplay.recording.SavedSegment;
import net.techniqstone.buildReplay.recording.SegmentCut;
import org.bukkit.entity.Player;

/**
 * {@link ReplayEngine} ohne AdvancedReplay: ein Satz gespeicherter Replay-Namen. Protokolliert Thread-Verstöße
 * (Zustand/Speicher vom "falschen" Thread gelesen).
 */
final class FakeReplayEngine implements ReplayEngine {

    volatile boolean available = true;
    volatile String backend = "FILE";
    final Set<String> stored = ConcurrentHashMap.newKeySet();
    final Set<String> failingDeletes = ConcurrentHashMap.newKeySet();
    final List<String> deleted = new CopyOnWriteArrayList<>();
    final List<String> activeRaw = new CopyOnWriteArrayList<>();
    final List<String> threadViolations = new CopyOnWriteArrayList<>();

    private void requireMain(String method) {
        if (!SyncTaskRunner.onMainThread()) {
            threadViolations.add(method + " außerhalb des Hauptthreads");
        }
    }

    private void requireIo(String method) {
        if (SyncTaskRunner.onMainThread()) {
            threadViolations.add(method + " auf dem Hauptthread");
        }
    }

    @Override
    public EngineStatus status() {
        requireMain("status");
        return new EngineStatus(available, true, true, null, true, 3600, backend, "1.8.19", activeRaw.size());
    }

    @Override
    public void install(RawRecordingSink sink) {
    }

    @Override
    public void uninstall() {
    }

    @Override
    public void startRecording(String rawName, Player player) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isRecording(String rawName) {
        return activeRaw.contains(rawName);
    }

    @Override
    public int currentTick(String rawName) {
        return -1;
    }

    @Override
    public void stopRecording(String rawName, boolean keep) {
    }

    @Override
    public List<SavedSegment> saveSegments(RawRecording raw, List<SegmentCut> cuts) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void saveRaw(RawRecording raw) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<Optional<RawRecording>> loadRaw(String rawName) {
        return CompletableFuture.completedFuture(Optional.empty());
    }

    @Override
    public boolean exists(String replayName) {
        requireIo("exists");
        return stored.contains(replayName);
    }

    @Override
    public void delete(String replayName) {
        requireIo("delete");
        if (failingDeletes.contains(replayName)) {
            throw new IllegalStateException("Datei gesperrt: " + replayName);
        }
        stored.remove(replayName);
        deleted.add(replayName);
    }

    @Override
    public Long sizeOf(String replayName) {
        return null;
    }

    @Override
    public CompletableFuture<PlaybackStart> play(String replayName, Player viewer, PlaybackOptions options) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isWatching(Player viewer) {
        return false;
    }

    @Override
    public void stopWatching(Player viewer) {
    }

    @Override
    public String storageBackend() {
        requireMain("storageBackend");
        return backend;
    }

    @Override
    public String storageRef(String replayName) {
        return null;
    }

    @Override
    public List<String> activeRawRecordings() {
        requireMain("activeRawRecordings");
        return List.copyOf(activeRaw);
    }

    @Override
    public List<String> storedReplayNames() {
        requireIo("storedReplayNames");
        return List.copyOf(stored);
    }

    @Override
    public void onPlaybackFinished(Consumer<Player> listener) {
    }
}
