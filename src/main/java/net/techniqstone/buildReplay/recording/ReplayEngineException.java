package net.techniqstone.buildReplay.recording;

/** Fehler in der AdvancedReplay-Anbindung (z. B. Aufnahme konnte nicht gestartet werden). */
public final class ReplayEngineException extends RuntimeException {

    public ReplayEngineException(String message) {
        super(message);
    }

    public ReplayEngineException(String message, Throwable cause) {
        super(message, cause);
    }
}
