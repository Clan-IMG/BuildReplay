package net.techniqstone.buildReplay.maintenance;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import net.techniqstone.buildReplay.recording.RecordingRecovery;
import net.techniqstone.buildReplay.storage.model.RecordingRow;

/** Zerlegt "erfolgreich" alle übergebenen Rohaufnahmen und merkt sie sich. */
final class FakeRecordingRecovery implements RecordingRecovery {

    final List<List<RecordingRow>> calls = new CopyOnWriteArrayList<>();
    volatile RuntimeException failure;

    @Override
    public CompletableFuture<Integer> recoverPending(List<RecordingRow> rows) {
        calls.add(List.copyOf(rows));
        if (failure != null) {
            return CompletableFuture.failedFuture(failure);
        }
        return CompletableFuture.completedFuture(rows.size());
    }
}
