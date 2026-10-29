package net.techniqstone.buildReplay.recording.advancedreplay;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import me.jumper251.replay.filesystem.saving.IReplaySaver;
import me.jumper251.replay.replaysystem.Replay;
import net.techniqstone.buildReplay.recording.ReplayNames;
import net.techniqstone.buildReplay.recording.advancedreplay.RawSnapshot.KnownPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Decorator um AdvancedReplays globalen {@code IReplaySaver} - die einzige Stelle, an der AR das Ende einer
 * Aufnahme sichtbar macht (AR kennt keine Speicher-Events). Rohaufnahmen ({@link ReplayNames#isRaw}) werden
 * abgefangen, kopiert und synchron an BuildReplay übergeben; alles andere (auch AR-eigene Befehle) geht
 * unverändert an den ursprünglichen Saver.
 *
 * <p>Thread: {@link #saveReplay} läuft auf dem stoppenden Thread - Hauptthread oder AR-Timer-Thread beim Erreichen
 * von {@code max_length}. Scheitert die Übergabe, wird die Rohaufnahme unverändert über den ursprünglichen Saver
 * gespeichert, damit keine Daten verloren gehen.
 */
final class SaverInterceptor implements IReplaySaver {

    private final IReplaySaver delegate;
    private final Function<String, @Nullable KnownPlayer> knownPlayers;
    private final Logger logger;
    private volatile @Nullable Consumer<RawSnapshot> rawSink;

    SaverInterceptor(IReplaySaver delegate, Function<String, @Nullable KnownPlayer> knownPlayers,
                     Consumer<RawSnapshot> rawSink, Logger logger) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.knownPlayers = Objects.requireNonNull(knownPlayers, "knownPlayers");
        this.rawSink = Objects.requireNonNull(rawSink, "rawSink");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Ursprünglicher AR-Saver (für das Speichern von Abschnitten, Laden, Löschen). */
    IReplaySaver delegate() {
        return delegate;
    }

    /** Nach dem Deinstallieren nichts mehr abfangen (falls ein fremder Saver diesen Decorator weiter umhüllt). */
    void detach() {
        rawSink = null;
    }

    boolean isAttached() {
        return rawSink != null;
    }

    @Override
    public void saveReplay(Replay replay) {
        Consumer<RawSnapshot> sink = rawSink;
        if (sink != null && replay != null && ReplayNames.isRaw(replay.getId()) && replay.getData() != null) {
            String rawName = replay.getId();
            try {
                RawSnapshot snapshot = RawSnapshot.of(rawName, replay.getData(), knownPlayers.apply(rawName));
                sink.accept(snapshot);
                return;
            } catch (Throwable t) {
                logger.log(Level.SEVERE, "Rohaufnahme " + rawName + " konnte nicht an BuildReplay übergeben werden"
                        + " - sie wird unverändert im AdvancedReplay-Speicher abgelegt", t);
            }
        }
        delegate.saveReplay(replay);
    }

    @Override
    public void loadReplay(String replayName, me.jumper251.replay.utils.fetcher.Consumer<Replay> consumer) {
        delegate.loadReplay(replayName, consumer);
    }

    @Override
    public boolean replayExists(String replayName) {
        return delegate.replayExists(replayName);
    }

    @Override
    public void deleteReplay(String replayName) {
        delegate.deleteReplay(replayName);
    }

    @Override
    public List<String> getReplays() {
        return delegate.getReplays();
    }

    @Override
    public String toString() {
        return "BuildReplay-SaverInterceptor[" + delegate.getClass().getName() + "]";
    }
}
