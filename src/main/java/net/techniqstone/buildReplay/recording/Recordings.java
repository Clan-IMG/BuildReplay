package net.techniqstone.buildReplay.recording;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Lesender Zugriff auf die Aufnahme-Verwaltung (Status, Diagnose). */
public interface Recordings {

    List<ActiveRecordingInfo> activeRecordings();

    Optional<ActiveRecordingInfo> recordingOf(UUID player);

    RecordingStats stats();
}
