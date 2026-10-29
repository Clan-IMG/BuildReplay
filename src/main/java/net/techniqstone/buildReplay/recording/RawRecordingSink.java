package net.techniqstone.buildReplay.recording;

/**
 * Empfängt Rohaufnahmen, die AdvancedReplay beim Stoppen mit Speichern abgeschlossen hat. Wird synchron auf dem
 * stoppenden Thread aufgerufen (Hauptthread, oder AdvancedReplays Timer-Thread beim Erreichen von max_length) und
 * darf nur kurz arbeiten (Weitergabe an den I/O-Pool).
 */
@FunctionalInterface
public interface RawRecordingSink {

    void accept(RawRecording raw);
}
