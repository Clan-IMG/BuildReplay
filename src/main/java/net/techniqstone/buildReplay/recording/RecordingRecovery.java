package net.techniqstone.buildReplay.recording;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.techniqstone.buildReplay.storage.model.RecordingRow;

/**
 * Nachträgliche Zerlegung von Rohaufnahmen im Status RAW_PENDING (beim Herunterfahren roh gespeichert). Die
 * Zeitachse liegt lokal unter {@code plugins/BuildReplay/pending/<rawName>.json}, die Rohdaten im
 * AdvancedReplay-Speicher dieses Servers.
 */
public interface RecordingRecovery {

    /** Liefert die Anzahl erfolgreich zerlegter Rohaufnahmen. */
    CompletableFuture<Integer> recoverPending(List<RecordingRow> rows);
}
