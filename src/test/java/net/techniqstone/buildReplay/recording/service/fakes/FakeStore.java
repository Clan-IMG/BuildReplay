package net.techniqstone.buildReplay.recording.service.fakes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.techniqstone.buildReplay.storage.ReplayStore;
import net.techniqstone.buildReplay.storage.StorageUnavailableException;
import net.techniqstone.buildReplay.storage.StoreHealth;
import net.techniqstone.buildReplay.storage.model.RecordingRow;
import net.techniqstone.buildReplay.storage.model.RecordingStatus;
import net.techniqstone.buildReplay.storage.model.RecordingUpsert;
import net.techniqstone.buildReplay.storage.model.SegmentRow;
import net.techniqstone.buildReplay.storage.model.SegmentStatus;
import net.techniqstone.buildReplay.storage.model.SegmentUpsert;
import net.techniqstone.buildReplay.storage.model.SessionRow;
import net.techniqstone.buildReplay.storage.model.SessionStats;
import net.techniqstone.buildReplay.storage.model.SessionUpsert;
import net.techniqstone.buildReplay.storage.model.VisitEndReason;
import net.techniqstone.buildReplay.storage.model.VisitRow;
import net.techniqstone.buildReplay.storage.model.VisitStart;

/** Store im Speicher: protokolliert Schreibvorgänge in Aufrufreihenfolge und liefert vorbereitete Lesedaten. */
public final class FakeStore implements ReplayStore {

    /** {@code finishRecording}-Aufruf. */
    public record Finish(String rawName, RecordingStatus status, Instant endedAt, int relevantActions, String error) {
    }

    /** {@code updateSegmentStatus}-Aufruf. */
    public record StatusUpdate(String replayName, SegmentStatus status, String note) {
    }

    public final List<Object> writes = new ArrayList<>();
    public final List<RecordingUpsert> recordingUpserts = new ArrayList<>();
    public final List<Finish> finishes = new ArrayList<>();
    public final List<SegmentUpsert> segmentUpserts = new ArrayList<>();
    public final List<StatusUpdate> statusUpdates = new ArrayList<>();

    public final Map<Long, SegmentRow> segments = new HashMap<>();
    public final Map<String, List<SessionRow>> sessionsByRef = new HashMap<>();
    public final Map<Long, List<SegmentRow>> segmentsBySession = new HashMap<>();
    public final Map<Long, SessionRow> sessionsById = new HashMap<>();
    public boolean unavailable;

    /** Letzter gemeldeter Status einer Rohaufnahme (Upsert oder Abschluss). */
    public RecordingStatus statusOf(String rawName) {
        RecordingStatus status = null;
        for (Object write : writes) {
            if (write instanceof RecordingUpsert upsert && upsert.rawName().equals(rawName)) {
                status = upsert.status();
            } else if (write instanceof Finish finish && finish.rawName().equals(rawName)) {
                status = finish.status();
            }
        }
        return status;
    }

    public List<RecordingStatus> statusHistory(String rawName) {
        List<RecordingStatus> history = new ArrayList<>();
        for (Object write : writes) {
            if (write instanceof RecordingUpsert upsert && upsert.rawName().equals(rawName)) {
                history.add(upsert.status());
            } else if (write instanceof Finish finish && finish.rawName().equals(rawName)) {
                history.add(finish.status());
            }
        }
        return history;
    }

    public Finish lastFinish(String rawName) {
        Finish last = null;
        for (Finish finish : finishes) {
            if (finish.rawName().equals(rawName)) {
                last = finish;
            }
        }
        return last;
    }

    public static String refKey(UUID owner, String ref) {
        return owner + "/" + ref;
    }

    private void checkAvailable() {
        if (unavailable) {
            throw new StorageUnavailableException("MariaDB nicht erreichbar (Test)");
        }
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public StoreHealth health() {
        return new StoreHealth(true, !unavailable, 0, 0, 0, null, null, 1);
    }

    @Override
    public void upsertPlayer(UUID playerUuid, String name, Instant seenAt) {
        writes.add("player:" + name);
    }

    @Override
    public void upsertSession(SessionUpsert session) {
        writes.add(session);
    }

    @Override
    public void closeSession(String sessionKey, Instant logoutTime, Long durationSeconds) {
        writes.add("close:" + sessionKey);
    }

    @Override
    public void mergeProvisionalSession(String provisionalKey, SessionUpsert target) {
        writes.add("merge:" + provisionalKey);
    }

    @Override
    public void startVisit(VisitStart visit) {
        writes.add(visit);
    }

    @Override
    public void endVisit(String visitKey, Instant leftAt, VisitEndReason reason) {
        writes.add("endVisit:" + visitKey);
    }

    @Override
    public void upsertRecording(RecordingUpsert recording) {
        writes.add(recording);
        recordingUpserts.add(recording);
    }

    @Override
    public void finishRecording(String rawName, RecordingStatus status, Instant endedAt, int relevantActions,
                                String error) {
        Finish finish = new Finish(rawName, status, endedAt, relevantActions, error);
        writes.add(finish);
        finishes.add(finish);
    }

    @Override
    public void upsertSegment(SegmentUpsert segment) {
        writes.add(segment);
        segmentUpserts.add(segment);
    }

    @Override
    public void updateSegmentStatus(String replayName, SegmentStatus status, String note) {
        StatusUpdate update = new StatusUpdate(replayName, status, note);
        writes.add(update);
        statusUpdates.add(update);
    }

    @Override
    public CompletableFuture<Void> flush() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public Optional<SessionRow> findSessionByKey(String sessionKey) {
        checkAvailable();
        return sessionsById.values().stream().filter(row -> row.sessionKey().equals(sessionKey)).findFirst();
    }

    @Override
    public Optional<SessionRow> findSessionById(long sessionId) {
        checkAvailable();
        return Optional.ofNullable(sessionsById.get(sessionId));
    }

    @Override
    public List<SessionRow> findSessionsByRef(UUID playerUuid, String ref, int limit) {
        checkAvailable();
        return sessionsByRef.getOrDefault(refKey(playerUuid, ref), List.of());
    }

    @Override
    public List<SessionRow> sessionsOfPlayer(UUID playerUuid, Instant since, int limit, int offset) {
        checkAvailable();
        return List.of();
    }

    @Override
    public int countSessionsOfPlayer(UUID playerUuid, Instant since) {
        return 0;
    }

    @Override
    public Map<String, SessionStats> statsByCloudSessionIds(Collection<String> cloudSessionIds) {
        return Map.of();
    }

    @Override
    public Map<Long, SessionStats> statsBySessionIds(Collection<Long> sessionIds) {
        return Map.of();
    }

    @Override
    public List<SessionRow> activeSessions(int limit) {
        return List.of();
    }

    @Override
    public List<SessionRow> provisionalSessions(int limit) {
        return List.of();
    }

    @Override
    public List<VisitRow> visitsOfSession(long sessionId) {
        return List.of();
    }

    @Override
    public List<VisitRow> openVisits(String serverName) {
        return List.of();
    }

    @Override
    public List<SegmentRow> segmentsOfSession(long sessionId) {
        checkAvailable();
        return segmentsBySession.getOrDefault(sessionId, List.of());
    }

    @Override
    public Optional<SegmentRow> findSegment(long segmentId) {
        checkAvailable();
        return Optional.ofNullable(segments.get(segmentId));
    }

    @Override
    public Optional<SegmentRow> findSegmentByReplayName(String replayName) {
        checkAvailable();
        return segments.values().stream().filter(row -> row.replayName().equals(replayName)).findFirst();
    }

    @Override
    public List<RecordingRow> recordingsByStatus(String serverName, Set<RecordingStatus> statuses, int limit) {
        return List.of();
    }

    @Override
    public List<SegmentRow> segmentsForRetention(String serverName, Instant olderThan, int limit) {
        return List.of();
    }

    @Override
    public long countSegmentsOfPlayer(UUID playerUuid) {
        return 0;
    }

    @Override
    public List<SegmentRow> oldestSegmentsOfPlayer(UUID playerUuid, String serverName, int limit) {
        return List.of();
    }

    @Override
    public void close() {
        // nichts
    }
}
