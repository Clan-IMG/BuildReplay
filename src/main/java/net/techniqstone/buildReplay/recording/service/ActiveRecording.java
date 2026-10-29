package net.techniqstone.buildReplay.recording.service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import net.techniqstone.buildReplay.activity.ActivityTimeline;
import net.techniqstone.buildReplay.activity.ActivityType;
import net.techniqstone.buildReplay.activity.TimelineEvent;
import net.techniqstone.buildReplay.recording.RawRecording;
import org.jspecify.annotations.Nullable;

/**
 * Zustand einer Pufferaufnahme vom Start bis zum Abschluss. Angelegt und befüllt wird sie auf dem Hauptthread; die
 * Phasenwechsel sind synchronisiert, weil AdvancedReplay die Rohaufnahme auch von seinem Timer-Thread übergeben kann
 * (max_length) und die Zerlegung auf dem I/O-Pool läuft. Zusammengesetzte Schritte (Phasenwechsel plus
 * Store-Schreibvorgang) synchronisiert der {@link RecordingService} auf diesem Objekt, damit die Store-Aufrufe in
 * der richtigen Reihenfolge eingereiht werden.
 */
final class ActiveRecording {

    enum Phase {
        /** AR nimmt auf. */
        RECORDING,
        /** Gestoppt, Rohaufnahme wird (gleich) zerlegt. */
        FINALIZING,
        /** Endstatus ist im Store eingereiht. */
        DONE
    }

    private final RecordingMeta meta;
    private final ActivityTimeline timeline;

    private volatile @Nullable String sessionKey;
    private volatile @Nullable Instant lastRelevantAt;
    private volatile Instant lastSampleAt;
    private volatile boolean afk;
    private volatile @Nullable String currentWorld;
    private volatile boolean inlineProcessing;

    private Phase phase = Phase.RECORDING;
    private @Nullable RawRecording raw;
    private @Nullable Instant endedAt;

    ActiveRecording(RecordingMeta meta, @Nullable String sessionKey) {
        this.meta = meta;
        this.timeline = new ActivityTimeline(meta.startedAt());
        this.sessionKey = sessionKey;
        this.lastSampleAt = meta.startedAt();
        this.currentWorld = meta.worldName();
    }

    RecordingMeta meta() {
        return meta;
    }

    String rawName() {
        return meta.rawName();
    }

    UUID player() {
        return meta.player();
    }

    String playerName() {
        return meta.playerName();
    }

    Instant startedAt() {
        return meta.startedAt();
    }

    ActivityTimeline timeline() {
        return timeline;
    }

    @Nullable String sessionKey() {
        return sessionKey;
    }

    void sessionKey(String key) {
        this.sessionKey = key;
    }

    @Nullable Instant lastRelevantAt() {
        return lastRelevantAt;
    }

    boolean afk() {
        return afk;
    }

    void afk(boolean afk) {
        this.afk = afk;
    }

    @Nullable String currentWorld() {
        return currentWorld;
    }

    void currentWorld(@Nullable String world) {
        if (world != null) {
            this.currentWorld = world;
        }
    }

    boolean inlineProcessing() {
        return inlineProcessing;
    }

    void inlineProcessing(boolean inline) {
        this.inlineProcessing = inline;
    }

    /** Aktivität auf der Zeitachse vermerken; ohne gültigen Tick (Aufnahme unbekannt) wird nichts eingetragen. */
    void record(int tick, Instant at, ActivityType type, boolean relevant) {
        if (tick < 0) {
            return;
        }
        timeline.append(new TimelineEvent(tick, at, type, relevant));
        if (relevant) {
            lastRelevantAt = at;
        }
    }

    /** Tick/Uhrzeit-Probe, höchstens einmal je {@code interval}. */
    void sampleIfDue(int tick, Instant now, Duration interval) {
        if (Duration.between(lastSampleAt, now).compareTo(interval) >= 0) {
            sample(tick, now);
        }
    }

    void sample(int tick, Instant now) {
        if (tick >= 0) {
            timeline.sample(tick, now);
            lastSampleAt = now;
        }
    }

    synchronized Phase phase() {
        return phase;
    }

    /** RECORDING → FINALIZING; {@code false}, wenn die Aufnahme schon übergeben oder abgeschlossen ist. */
    synchronized boolean beginFinalizing(Instant at) {
        if (phase != Phase.RECORDING) {
            return false;
        }
        phase = Phase.FINALIZING;
        endedAt = at;
        return true;
    }

    /** Rohaufnahme übernehmen; {@code false}, wenn bereits eine vorliegt oder die Aufnahme abgeschlossen ist. */
    synchronized boolean attachRaw(RawRecording recording) {
        if (raw != null || phase == Phase.DONE) {
            return false;
        }
        raw = recording;
        return true;
    }

    synchronized boolean hasRaw() {
        return raw != null;
    }

    synchronized @Nullable RawRecording raw() {
        return raw;
    }

    synchronized Instant endedAt(Instant fallback) {
        return endedAt != null ? endedAt : fallback;
    }

    /** → DONE; liefert die vorherige Phase ({@link Phase#DONE}: war bereits abgeschlossen). */
    synchronized Phase complete() {
        Phase previous = phase;
        phase = Phase.DONE;
        return previous;
    }
}
