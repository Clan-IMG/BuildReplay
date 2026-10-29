package net.techniqstone.buildReplay.maintenance;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
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

/**
 * In-Memory-{@link ReplayStore} für Wartungstests. Schreibvorgänge werden protokolliert und (wie nach einem
 * Flush) sofort auf die Zeilen angewendet; {@code unavailable=true} lässt alle Lesezugriffe scheitern.
 */
final class FakeReplayStore implements ReplayStore {

    record EndedVisit(String visitKey, Instant leftAt, VisitEndReason reason) {
    }

    record FinishedRecording(String rawName, RecordingStatus status, Instant endedAt, int relevantActions,
                             String error) {
    }

    record StatusUpdate(String replayName, SegmentStatus status, String note) {
    }

    volatile boolean enabled = true;
    volatile boolean unavailable;
    final List<VisitRow> visits = new CopyOnWriteArrayList<>();
    final List<RecordingRow> recordings = new CopyOnWriteArrayList<>();
    final List<SegmentRow> segments = new CopyOnWriteArrayList<>();
    final List<EndedVisit> endedVisits = new CopyOnWriteArrayList<>();
    final List<FinishedRecording> finishedRecordings = new CopyOnWriteArrayList<>();
    final List<StatusUpdate> statusUpdates = new CopyOnWriteArrayList<>();
    final List<String> readThreadViolations = new CopyOnWriteArrayList<>();
    volatile int flushes;

    private void read(String method) {
        if (SyncTaskRunner.onMainThread()) {
            readThreadViolations.add(method);
        }
        if (unavailable) {
            throw new StorageUnavailableException("MariaDB nicht erreichbar (Test)");
        }
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public StoreHealth health() {
        return new StoreHealth(enabled, !unavailable, 0, 0, 0, null, null, 1);
    }

    @Override
    public void upsertPlayer(UUID playerUuid, String name, Instant seenAt) {
    }

    @Override
    public void upsertSession(SessionUpsert session) {
    }

    @Override
    public void closeSession(String sessionKey, Instant logoutTime, Long durationSeconds) {
    }

    @Override
    public void mergeProvisionalSession(String provisionalKey, SessionUpsert target) {
    }

    @Override
    public void startVisit(VisitStart visit) {
    }

    @Override
    public void endVisit(String visitKey, Instant leftAt, VisitEndReason reason) {
        endedVisits.add(new EndedVisit(visitKey, leftAt, reason));
        visits.replaceAll(v -> v.visitKey().equals(visitKey)
                ? new VisitRow(v.id(), v.visitKey(), v.sessionId(), v.playerUuid(), v.serverName(), v.instanceId(),
                        v.joinedAt(), leftAt, reason)
                : v);
    }

    @Override
    public void upsertRecording(RecordingUpsert recording) {
    }

    @Override
    public void finishRecording(String rawName, RecordingStatus status, Instant endedAt, int relevantActions,
                                String error) {
        finishedRecordings.add(new FinishedRecording(rawName, status, endedAt, relevantActions, error));
        recordings.replaceAll(r -> r.rawName().equals(rawName)
                ? new RecordingRow(r.id(), r.rawName(), r.sessionId(), r.playerUuid(), r.playerName(), r.serverName(),
                        r.instanceId(), r.worldName(), r.startedAt(), endedAt, status, relevantActions, error)
                : r);
    }

    @Override
    public void upsertSegment(SegmentUpsert segment) {
    }

    @Override
    public void updateSegmentStatus(String replayName, SegmentStatus status, String note) {
        statusUpdates.add(new StatusUpdate(replayName, status, note));
        segments.replaceAll(s -> s.replayName().equals(replayName) ? Rows.withStatus(s, status, note) : s);
    }

    @Override
    public CompletableFuture<Void> flush() {
        flushes++;
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public Optional<SessionRow> findSessionByKey(String sessionKey) {
        return Optional.empty();
    }

    @Override
    public Optional<SessionRow> findSessionById(long sessionId) {
        return Optional.empty();
    }

    @Override
    public List<SessionRow> findSessionsByRef(UUID playerUuid, String ref, int limit) {
        return List.of();
    }

    @Override
    public List<SessionRow> sessionsOfPlayer(UUID playerUuid, Instant since, int limit, int offset) {
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
        read("openVisits");
        return visits.stream().filter(v -> v.serverName().equals(serverName) && v.leftAt() == null).toList();
    }

    @Override
    public List<SegmentRow> segmentsOfSession(long sessionId) {
        return List.of();
    }

    @Override
    public Optional<SegmentRow> findSegment(long segmentId) {
        return Optional.empty();
    }

    @Override
    public Optional<SegmentRow> findSegmentByReplayName(String replayName) {
        return Optional.empty();
    }

    @Override
    public List<RecordingRow> recordingsByStatus(String serverName, Set<RecordingStatus> statuses, int limit) {
        read("recordingsByStatus");
        return recordings.stream()
                .filter(r -> r.serverName().equals(serverName) && statuses.contains(r.status()))
                .limit(limit)
                .toList();
    }

    @Override
    public List<SegmentRow> segmentsForRetention(String serverName, Instant olderThan, int limit) {
        read("segmentsForRetention");
        return segments.stream()
                .filter(s -> s.serverName().equals(serverName) && s.endedAt().isBefore(olderThan)
                        && s.status() != SegmentStatus.DELETED)
                .sorted(Comparator.comparing(SegmentRow::endedAt))
                .limit(limit)
                .toList();
    }

    @Override
    public long countSegmentsOfPlayer(UUID playerUuid) {
        read("countSegmentsOfPlayer");
        return segments.stream()
                .filter(s -> s.playerUuid().equals(playerUuid) && s.status() != SegmentStatus.DELETED)
                .count();
    }

    @Override
    public List<SegmentRow> oldestSegmentsOfPlayer(UUID playerUuid, String serverName, int limit) {
        read("oldestSegmentsOfPlayer");
        return new ArrayList<>(segments.stream()
                .filter(s -> s.playerUuid().equals(playerUuid) && s.serverName().equals(serverName)
                        && s.status() != SegmentStatus.DELETED)
                .sorted(Comparator.comparing(SegmentRow::startedAt))
                .limit(limit)
                .toList());
    }

    @Override
    public void close() {
    }
}
