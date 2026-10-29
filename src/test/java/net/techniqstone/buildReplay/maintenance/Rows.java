package net.techniqstone.buildReplay.maintenance;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import net.techniqstone.buildReplay.storage.model.RecordingRow;
import net.techniqstone.buildReplay.storage.model.RecordingStatus;
import net.techniqstone.buildReplay.storage.model.SegmentRow;
import net.techniqstone.buildReplay.storage.model.SegmentStatus;
import net.techniqstone.buildReplay.storage.model.VisitRow;

/** Testdaten. */
final class Rows {

    static final String SERVER = "dev";
    static final String OLD_INSTANCE = "dev-old-instance";
    static final String CURRENT_INSTANCE = "dev-current-instance";
    static final UUID STEVE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID ALEX = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static long ids = 1;

    private Rows() {
    }

    static VisitRow openVisit(String visitKey, UUID player, String instanceId, Instant joinedAt) {
        return new VisitRow(ids++, visitKey, 7L, player, SERVER, instanceId, joinedAt, null, null);
    }

    static RecordingRow recording(String rawName, UUID player, String instanceId, RecordingStatus status,
                                  Instant startedAt, int relevantActions) {
        return new RecordingRow(ids++, rawName, 7L, player, "Steve", SERVER, instanceId, "world", startedAt, null,
                status, relevantActions, null);
    }

    static SegmentRow segment(String replayName, UUID player, String server, Instant startedAt, Duration length,
                              SegmentStatus status, Instant createdAt) {
        Instant endedAt = startedAt.plus(length);
        return new SegmentRow(ids++, replayName, 7L, null, player, "Steve", server, "world", "FILE",
                "replays/" + replayName + ".replay", startedAt, endedAt, startedAt.plusSeconds(10),
                endedAt.minusSeconds(10), (int) (length.toSeconds() * 20), 10_000, 10_000, 12,
                Map.of("block-place", 12), status, 1024L, null, createdAt);
    }

    static SegmentRow segment(String replayName, UUID player, Instant startedAt, SegmentStatus status) {
        return segment(replayName, player, SERVER, startedAt, Duration.ofMinutes(2), status,
                startedAt.plus(Duration.ofMinutes(2)));
    }

    static SegmentRow withStatus(SegmentRow s, SegmentStatus status, String note) {
        return new SegmentRow(s.id(), s.replayName(), s.sessionId(), s.recordingId(), s.playerUuid(), s.playerName(),
                s.serverName(), s.worldName(), s.storageBackend(), s.storageRef(), s.startedAt(), s.endedAt(),
                s.firstActionAt(), s.lastActionAt(), s.durationTicks(), s.preRollMillis(), s.postRollMillis(),
                s.relevantActions(), s.actionSummary(), status, s.sizeBytes(), note, s.createdAt());
    }

    /** Rohname mit Zeitstempel wie {@code ReplayNames.newRawName()}. */
    static String rawName(Instant createdAt, String suffix) {
        return "brraw-" + Long.toString(createdAt.toEpochMilli(), 36) + "-" + suffix;
    }
}
