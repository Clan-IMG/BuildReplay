package net.techniqstone.buildReplay.storage.model;

/** Zustand eines gespeicherten Replay-Abschnitts. */
public enum SegmentStatus {
    SAVING,
    AVAILABLE,
    /** Replay-Datei/-Eintrag existiert nicht (mehr). */
    MISSING,
    /** Replay konnte nicht geladen werden. */
    CORRUPT,
    /** Durch Aufräumen/Aufbewahrungsfrist gelöscht. */
    DELETED,
    FAILED;

    public boolean playable() {
        return this == AVAILABLE;
    }
}
