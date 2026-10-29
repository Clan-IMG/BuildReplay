package net.techniqstone.buildReplay.storage.model;

/** Zustand einer fortlaufenden AdvancedReplay-Pufferaufnahme ("Rohaufnahme"). */
public enum RecordingStatus {
    /** Läuft gerade auf der Eigentümer-Instanz. */
    RECORDING,
    /** Gestoppt, Abschnitte werden ausgeschnitten und gespeichert. */
    FINALIZING,
    /** Abschnitte gespeichert (oder keine nötig), Rohdaten verworfen. */
    FINALIZED,
    /** Ohne relevante Aktivität verworfen. */
    DISCARDED,
    /** Rohdaten gespeichert, Zerlegung steht noch aus (z. B. nach dem Herunterfahren). */
    RAW_PENDING,
    /** Instanz ist während der Aufnahme ausgefallen, Daten verloren. */
    INTERRUPTED,
    FAILED
}
