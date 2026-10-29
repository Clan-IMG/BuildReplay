package net.techniqstone.buildReplay.recording;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Kapselt AdvancedReplay (nur diese Schnittstelle kennt AR-Klassen). Ablauf einer Aufnahme:
 * <ol>
 *   <li>{@link #startRecording}: fortlaufende AR-Aufnahme genau eines Spielers unter einem eindeutigen Rohnamen.</li>
 *   <li>{@link #stopRecording} mit {@code keep=true}: AR schließt ab und "speichert"; der installierte
 *       Saver-Decorator fängt Rohaufnahmen (Präfix {@link ReplayNames#RAW_PREFIX}) ab, kopiert die Aktionen und
 *       übergibt sie an die {@link RawRecordingSink}. Andere Replays reicht er unverändert an AR durch.</li>
 *   <li>{@link #saveSegments} (I/O-Thread): schneidet die geplanten Bereiche aus (Ticks neu ab 0, Spawn-/Inventar-/
 *       Zustand am Schnitt nachgebildet) und speichert sie über AdvancedReplays eigenen Saver als normale Replays.</li>
 * </ol>
 * Damit entsteht Vorlauf ohne nachträgliches Einfügen vergangener Ereignisse: die Aufnahme lief bereits.
 */
public interface ReplayEngine {

    EngineStatus status();

    /**
     * Hauptthread, nachdem AdvancedReplay aktiviert ist: Saver-Decorator und Hooks registrieren, Kompatibilität
     * prüfen. Idempotent - erneut aufrufen, wenn AR seinen Saver ersetzt hat (z. B. nach {@code /replay reload}).
     */
    void install(RawRecordingSink sink);

    /** Hauptthread: Decorator und Hooks entfernen (vor dem Deaktivieren von AdvancedReplay). */
    void uninstall();

    /** Hauptthread: startet eine Pufferaufnahme von genau diesem Spieler. */
    void startRecording(String rawName, Player player) throws ReplayEngineException;

    boolean isRecording(String rawName);

    /** Aktueller Tick der Aufnahme oder -1 (beliebiger Thread). */
    int currentTick(String rawName);

    /**
     * Hauptthread. {@code keep=true}: AR beendet die Aufnahme, die Sink erhält die Rohaufnahme noch während dieses
     * Aufrufs. {@code keep=false}: Aufnahme wird verworfen.
     */
    void stopRecording(String rawName, boolean keep);

    /** I/O-Thread: Abschnitte ausschneiden und als eigene AdvancedReplay-Replays speichern. */
    List<SavedSegment> saveSegments(RawRecording raw, List<SegmentCut> cuts);

    /** I/O-Thread: Rohaufnahme unverändert speichern (Fallback, wenn die Zerlegung nicht mehr möglich ist). */
    void saveRaw(RawRecording raw);

    /** Gespeicherte Rohaufnahme laden (zur späteren Zerlegung); leer, wenn nicht vorhanden oder unlesbar. */
    CompletableFuture<Optional<RawRecording>> loadRaw(String rawName);

    /** I/O-Thread: ob das Replay im aktiven AdvancedReplay-Speicher existiert. */
    boolean exists(String replayName);

    /** I/O-Thread. */
    void delete(String replayName);

    /** Größe in Bytes, falls ermittelbar (Datei-Speicher). */
    @Nullable Long sizeOf(String replayName);

    /**
     * Hauptthread: Replay für {@code viewer} abspielen. Prüft vorab Kompatibilität und Welt; das Future schließt
     * auf dem Hauptthread ab, sobald AR gestartet ist oder der Start scheiterte.
     */
    CompletableFuture<PlaybackStart> play(String replayName, Player viewer, PlaybackOptions options);

    boolean isWatching(Player viewer);

    /** Hauptthread: laufende Wiedergabe des Spielers beenden. */
    void stopWatching(Player viewer);

    /** "FILE", "MYSQL", "S3" oder "CUSTOM" - abhängig von AdvancedReplays Konfiguration. */
    String storageBackend();

    /** Informative Ablage-Referenz (z. B. {@code plugins/AdvancedReplay/replays/<name>.replay}), nicht netzwerkweit gültig. */
    @Nullable String storageRef(String replayName);

    /** Gerade laufende Pufferaufnahmen (Rohnamen). */
    List<String> activeRawRecordings();

    /** I/O-Thread: Namen aller im aktiven AdvancedReplay-Speicher abgelegten Replays (für das Aufräumen). */
    List<String> storedReplayNames();

    /**
     * Wird auf dem Hauptthread aufgerufen, wenn eine Wiedergabe endet (natürliches Ende, Verlassen, Quit) und
     * AdvancedReplay den Zuschauer zurückgesetzt hat ({@code ReplaySessionFinishEvent}).
     */
    void onPlaybackFinished(Consumer<Player> listener);
}
