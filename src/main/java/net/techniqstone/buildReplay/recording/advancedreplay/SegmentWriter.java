package net.techniqstone.buildReplay.recording.advancedreplay;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import me.jumper251.replay.filesystem.saving.IReplaySaver;
import me.jumper251.replay.replaysystem.Replay;
import me.jumper251.replay.replaysystem.data.ReplayData;
import me.jumper251.replay.replaysystem.data.ReplayInfo;
import net.techniqstone.buildReplay.recording.ReplayEngineException;
import net.techniqstone.buildReplay.recording.ReplayNames;
import net.techniqstone.buildReplay.recording.SavedSegment;
import net.techniqstone.buildReplay.recording.SegmentCut;
import org.jspecify.annotations.Nullable;

/**
 * Speichert Abschnitte und Rohaufnahmen über AdvancedReplays <em>ursprünglichen</em> Saver (nie über den
 * Decorator - eine Rohaufnahme würde sonst erneut abgefangen). I/O-Thread: AR's Datei-Saver serialisiert und
 * schreibt synchron auf dem aufrufenden Thread. AR meldet Speicherfehler nur per Stacktrace, daher wird danach
 * geprüft: beim Datei-Saver über die Datei, sonst über {@code replayExists} (beim MySQL-Saver nur der Cache).
 */
final class SegmentWriter {

    private final Supplier<ReplayData> dataFactory;
    private final ReplaySlicer slicer;
    private final Logger logger;

    SegmentWriter(Supplier<ReplayData> dataFactory, ReplaySlicer slicer, Logger logger) {
        this.dataFactory = Objects.requireNonNull(dataFactory, "dataFactory");
        this.slicer = Objects.requireNonNull(slicer, "slicer");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * @param fileDirectory {@code plugins/AdvancedReplay/replays} beim Datei-Saver, sonst {@code null}
     */
    List<SavedSegment> saveSegments(IReplaySaver delegate, @Nullable File fileDirectory, RawSnapshot snapshot,
                                    List<SegmentCut> cuts) {
        List<SavedSegment> result = new ArrayList<>(cuts.size());
        for (SegmentCut cut : cuts) {
            String name = cut.replayName();
            if (!ReplayNames.isValid(name) || ReplayNames.isRaw(name)) {
                result.add(failed(name, "Ungültiger Replay-Name"));
                continue;
            }
            try {
                ReplaySlicer.Slice slice = slicer.slice(snapshot.actions(), snapshot.durationTicks(),
                        snapshot.creatorOrPlayer(), cut.fromTick(), cut.toTick());
                Replay replay = new Replay(name, slice.data());
                replay.setReplayInfo(new ReplayInfo(name, snapshot.creatorOrPlayer(), System.currentTimeMillis(),
                        slice.durationTicks()));
                delegate.saveReplay(replay);
                File file = file(fileDirectory, name);
                String error = verifySaved(delegate, file, name);
                if (error != null) {
                    logger.warning("Abschnitt " + name + " aus " + snapshot.rawName() + ": " + error);
                }
                result.add(new SavedSegment(name, slice.durationTicks(), slice.worldName(), sizeOf(file),
                        error == null, error));
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError fatal) {
                    throw fatal;
                }
                logger.log(Level.WARNING, "Abschnitt " + name + " aus " + snapshot.rawName()
                        + " konnte nicht gespeichert werden", t);
                result.add(failed(name, t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : ": " + t.getMessage())));
            }
        }
        return result;
    }

    /** Rohaufnahme unverändert (alle Ticks, Dauer, Ersteller) speichern; wirft bei Fehlern. */
    void saveRaw(IReplaySaver delegate, @Nullable File fileDirectory, RawSnapshot snapshot) {
        String name = snapshot.rawName();
        if (!ReplayNames.isValid(name)) {
            throw new ReplayEngineException("Ungültiger Name der Rohaufnahme: " + name);
        }
        try {
            ReplayData data = dataFactory.get();
            snapshot.actions().forEach((tick, list) -> data.getActions().put(tick, new ArrayList<>(list)));
            data.setDuration(snapshot.durationTicks());
            data.setCreator(snapshot.creatorOrPlayer());
            Replay replay = new Replay(name, data);
            replay.setReplayInfo(new ReplayInfo(name, snapshot.creatorOrPlayer(), System.currentTimeMillis(),
                    snapshot.durationTicks()));
            delegate.saveReplay(replay);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError fatal) {
                throw fatal;
            }
            throw new ReplayEngineException("Rohaufnahme " + name + " konnte nicht gespeichert werden", t);
        }
        String error = verifySaved(delegate, file(fileDirectory, name), name);
        if (error != null) {
            throw new ReplayEngineException("Rohaufnahme " + name + ": " + error);
        }
    }

    static @Nullable File file(@Nullable File fileDirectory, String replayName) {
        return fileDirectory == null ? null : new File(fileDirectory, replayName + ".replay");
    }

    /** {@code null} = gespeichert, sonst deutsche Fehlerbeschreibung. */
    static @Nullable String verifySaved(IReplaySaver delegate, @Nullable File file, String replayName) {
        if (file != null) {
            if (!file.isFile()) {
                return "AdvancedReplay hat keine Datei geschrieben";
            }
            if (file.length() == 0) {
                return "AdvancedReplay hat eine leere Datei geschrieben";
            }
            return null;
        }
        return delegate.replayExists(replayName) ? null : "AdvancedReplay kennt das Replay nach dem Speichern nicht";
    }

    static @Nullable Long sizeOf(@Nullable File file) {
        return file != null && file.isFile() ? file.length() : null;
    }

    static SavedSegment failed(String name, String error) {
        return new SavedSegment(name, 0, null, null, false, error);
    }
}
