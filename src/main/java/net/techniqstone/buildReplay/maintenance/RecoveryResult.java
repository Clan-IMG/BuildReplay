package net.techniqstone.buildReplay.maintenance;

/**
 * Ergebnis der Wiederherstellung nach einem Neustart/Absturz dieser Instanz.
 *
 * @param visitsClosed          offene Aufenthalte, die mit {@code RECOVERED} geschlossen wurden
 * @param recordingsInterrupted Aufnahmen in RECORDING/FINALIZING, die als INTERRUPTED markiert wurden
 * @param pendingFound          Rohaufnahmen im Status RAW_PENDING
 * @param pendingRecovered      davon erfolgreich in Abschnitte zerlegt
 * @param savingAvailable       hängende SAVING-Abschnitte, deren Replay existiert (→ AVAILABLE)
 * @param savingFailed          hängende SAVING-Abschnitte ohne Replay (→ FAILED)
 * @param skipped               nichts getan (MariaDB deaktiviert)
 */
public record RecoveryResult(int visitsClosed, int recordingsInterrupted, int pendingFound, int pendingRecovered,
                             int savingAvailable, int savingFailed, boolean skipped) {

    public static RecoveryResult skippedResult() {
        return new RecoveryResult(0, 0, 0, 0, 0, 0, true);
    }

    public boolean changedAnything() {
        return visitsClosed + recordingsInterrupted + pendingRecovered + savingAvailable + savingFailed > 0;
    }
}
