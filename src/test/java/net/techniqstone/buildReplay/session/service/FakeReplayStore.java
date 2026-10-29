package net.techniqstone.buildReplay.session.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
import net.techniqstone.buildReplay.storage.model.SessionSource;
import net.techniqstone.buildReplay.storage.model.SessionStats;
import net.techniqstone.buildReplay.storage.model.SessionStatus;
import net.techniqstone.buildReplay.storage.model.SessionUpsert;
import net.techniqstone.buildReplay.storage.model.VisitEndReason;
import net.techniqstone.buildReplay.storage.model.VisitRow;
import net.techniqstone.buildReplay.storage.model.VisitStart;
import org.jspecify.annotations.Nullable;

/**
 * MariaDB im Speicher mit den im Vertrag beschriebenen Regeln für Sitzungen und Aufenthalte (idempotent über
 * natürliche Schlüssel, frühester Login, Logout nie mit früherem überschrieben, Zusammenführung). Schreibvorgänge
 * werden auch "bei Ausfall" angenommen (Outbox); Lesezugriffe werfen dann {@link StorageUnavailableException}.
 */
final class FakeReplayStore implements ReplayStore {

    private final Clock clock;
    private final AtomicLong ids = new AtomicLong();
    private final Map<UUID, String> players = new HashMap<>();
    private final Map<String, SessionRow> sessions = new LinkedHashMap<>();
    private final Map<String, VisitRow> visits = new LinkedHashMap<>();
    private final List<SegmentRow> segments = new ArrayList<>();
    final AtomicInteger merges = new AtomicInteger();
    final AtomicInteger closes = new AtomicInteger();
    private volatile boolean available = true;

    FakeReplayStore(Clock clock) {
        this.clock = clock;
    }

    void setAvailable(boolean available) {
        this.available = available;
    }

    // ------------------------------------------------------------------ Test-Zugriff (ohne Ausfallprüfung)

    synchronized Optional<SessionRow> session(String key) {
        return Optional.ofNullable(sessions.get(key));
    }

    synchronized List<SessionRow> allSessions() {
        return List.copyOf(sessions.values());
    }

    synchronized List<VisitRow> allVisits() {
        return List.copyOf(visits.values());
    }

    synchronized Optional<String> playerName(UUID player) {
        return Optional.ofNullable(players.get(player));
    }

    /** Legt eine Sitzungszeile direkt an (Ausgangszustand für Tests). */
    synchronized SessionRow putSession(String key, @Nullable String cloudId, UUID player, SessionSource source,
                                       SessionStatus status, Instant login, @Nullable Instant logout) {
        Instant now = clock.instant();
        Long duration = logout != null ? Math.max(0, logout.getEpochSecond() - login.getEpochSecond()) : null;
        SessionRow row = new SessionRow(ids.incrementAndGet(), key, cloudId, player, players.get(player), source, status,
                login, logout, duration, null, now, now);
        sessions.put(key, row);
        return row;
    }

    synchronized SegmentRow addSegment(String sessionKey, String server, SegmentStatus status) {
        SessionRow session = sessions.get(sessionKey);
        if (session == null) {
            throw new IllegalArgumentException("unbekannte Sitzung " + sessionKey);
        }
        Instant start = session.loginTime() != null ? session.loginTime() : clock.instant();
        SegmentRow row = new SegmentRow(ids.incrementAndGet(), "br-" + ids.get(), session.id(), null,
                session.playerUuid(), "Spieler", server, "world", "file", null, start, start.plusSeconds(60),
                start.plusSeconds(10), start.plusSeconds(50), 1_200, 10_000, 10_000, 12, Map.of("block_place", 12),
                status, 1024L, null, clock.instant());
        segments.add(row);
        return row;
    }

    // ------------------------------------------------------------------ Schreiben

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public StoreHealth health() {
        return new StoreHealth(true, available, 0, 0, 0, null, null, 1);
    }

    @Override
    public synchronized void upsertPlayer(UUID playerUuid, String name, Instant seenAt) {
        players.put(playerUuid, name);
    }

    @Override
    public synchronized void upsertSession(SessionUpsert upsert) {
        Instant now = clock.instant();
        SessionRow existing = sessions.get(upsert.sessionKey());
        if (existing == null) {
            SessionStatus status = upsert.logoutTime() != null ? SessionStatus.CLOSED : SessionStatus.ACTIVE;
            sessions.put(upsert.sessionKey(), new SessionRow(ids.incrementAndGet(), upsert.sessionKey(),
                    upsert.cloudSessionId(), upsert.playerUuid(), players.get(upsert.playerUuid()), upsert.source(),
                    status, upsert.loginTime(), upsert.logoutTime(), upsert.durationSeconds(), null, now, now));
            return;
        }
        Instant login = earliest(existing.loginTime(), upsert.loginTime());
        Instant logout = upsert.logoutTime() != null ? upsert.logoutTime() : existing.logoutTime();
        Long duration = upsert.durationSeconds() != null ? upsert.durationSeconds() : existing.durationSeconds();
        SessionStatus status = existing.status() == SessionStatus.MERGED ? SessionStatus.MERGED
                : logout != null ? SessionStatus.CLOSED : existing.status();
        SessionSource source = upsert.source() == SessionSource.SIMPLECLOUD ? SessionSource.SIMPLECLOUD
                : existing.source();
        sessions.put(upsert.sessionKey(), new SessionRow(existing.id(), existing.sessionKey(),
                upsert.cloudSessionId() != null ? upsert.cloudSessionId() : existing.cloudSessionId(),
                existing.playerUuid(), existing.playerName(), source, status, login, logout, duration,
                existing.mergedInto(), existing.createdAt(), now));
    }

    @Override
    public synchronized void closeSession(String sessionKey, Instant logoutTime, @Nullable Long durationSeconds) {
        closes.incrementAndGet();
        SessionRow existing = sessions.get(sessionKey);
        if (existing == null || existing.status() == SessionStatus.MERGED) {
            return;
        }
        boolean keepKnown = existing.logoutTime() != null && logoutTime.isBefore(existing.logoutTime());
        sessions.put(sessionKey, new SessionRow(existing.id(), existing.sessionKey(), existing.cloudSessionId(),
                existing.playerUuid(), existing.playerName(), existing.source(), SessionStatus.CLOSED,
                existing.loginTime(), keepKnown ? existing.logoutTime() : logoutTime,
                keepKnown ? existing.durationSeconds() : durationSeconds, existing.mergedInto(),
                existing.createdAt(), clock.instant()));
    }

    @Override
    public synchronized void mergeProvisionalSession(String provisionalKey, SessionUpsert target) {
        merges.incrementAndGet();
        upsertSession(target);
        SessionRow merged = sessions.get(target.sessionKey());
        SessionRow provisional = sessions.get(provisionalKey);
        if (provisional == null || provisional.status() == SessionStatus.MERGED) {
            return;
        }
        sessions.put(provisionalKey, new SessionRow(provisional.id(), provisional.sessionKey(), null,
                provisional.playerUuid(), provisional.playerName(), provisional.source(), SessionStatus.MERGED,
                provisional.loginTime(), provisional.logoutTime(), provisional.durationSeconds(), merged.id(),
                provisional.createdAt(), clock.instant()));
        visits.replaceAll((key, visit) -> visit.sessionId() != provisional.id() ? visit
                : new VisitRow(visit.id(), visit.visitKey(), merged.id(), visit.playerUuid(), visit.serverName(),
                visit.instanceId(), visit.joinedAt(), visit.leftAt(), visit.endReason()));
    }

    @Override
    public synchronized void startVisit(VisitStart visit) {
        if (visits.containsKey(visit.visitKey())) {
            return;
        }
        SessionRow session = sessions.get(visit.sessionKey());
        if (session == null) {
            upsertSession(new SessionUpsert(visit.sessionKey(), null, visit.playerUuid(), SessionSource.PROVISIONAL,
                    visit.joinedAt(), null, null));
            session = sessions.get(visit.sessionKey());
        }
        visits.put(visit.visitKey(), new VisitRow(ids.incrementAndGet(), visit.visitKey(), session.id(),
                visit.playerUuid(), visit.serverName(), visit.instanceId(), visit.joinedAt(), null, null));
    }

    @Override
    public synchronized void endVisit(String visitKey, Instant leftAt, VisitEndReason reason) {
        VisitRow visit = visits.get(visitKey);
        if (visit == null || visit.leftAt() != null) {
            return;
        }
        visits.put(visitKey, new VisitRow(visit.id(), visitKey, visit.sessionId(), visit.playerUuid(),
                visit.serverName(), visit.instanceId(), visit.joinedAt(), leftAt, reason));
    }

    @Override
    public void upsertRecording(RecordingUpsert recording) {
        // für Sitzungstests ohne Bedeutung
    }

    @Override
    public void finishRecording(String rawName, RecordingStatus status, @Nullable Instant endedAt, int relevantActions,
                                @Nullable String error) {
        // für Sitzungstests ohne Bedeutung
    }

    @Override
    public void upsertSegment(SegmentUpsert segment) {
        // für Sitzungstests ohne Bedeutung
    }

    @Override
    public void updateSegmentStatus(String replayName, SegmentStatus status, @Nullable String note) {
        // für Sitzungstests ohne Bedeutung
    }

    @Override
    public CompletableFuture<Void> flush() {
        return CompletableFuture.completedFuture(null);
    }

    // ------------------------------------------------------------------ Lesen

    @Override
    public synchronized Optional<SessionRow> findSessionByKey(String sessionKey) {
        checkAvailable();
        return Optional.ofNullable(sessions.get(sessionKey));
    }

    @Override
    public synchronized Optional<SessionRow> findSessionById(long sessionId) {
        checkAvailable();
        return sessions.values().stream().filter(row -> row.id() == sessionId).findFirst();
    }

    @Override
    public synchronized List<SessionRow> findSessionsByRef(UUID playerUuid, String ref, int limit) {
        checkAvailable();
        String wanted = ref.toLowerCase(Locale.ROOT).replace("-", "");
        return sessions.values().stream()
                .filter(row -> row.playerUuid().equals(playerUuid) && row.status() != SessionStatus.MERGED)
                .filter(row -> {
                    String key = row.sessionKey().toLowerCase(Locale.ROOT);
                    if (key.startsWith("prov-")) {
                        return wanted.startsWith("p") && key.substring(5).replace("-", "")
                                .startsWith(wanted.substring(1));
                    }
                    return key.replace("-", "").startsWith(wanted);
                })
                .sorted(newestFirst())
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized List<SessionRow> sessionsOfPlayer(UUID playerUuid, Instant since, int limit, int offset) {
        checkAvailable();
        return ofPlayerSince(playerUuid, since).stream().skip(offset).limit(limit).toList();
    }

    @Override
    public synchronized int countSessionsOfPlayer(UUID playerUuid, Instant since) {
        checkAvailable();
        return ofPlayerSince(playerUuid, since).size();
    }

    @Override
    public synchronized Map<String, SessionStats> statsByCloudSessionIds(Collection<String> cloudSessionIds) {
        checkAvailable();
        Map<String, SessionStats> result = new HashMap<>();
        for (SessionRow row : sessions.values()) {
            if (row.cloudSessionId() != null && cloudSessionIds.contains(row.cloudSessionId())) {
                result.put(row.cloudSessionId(), statsOf(row.id()));
            }
        }
        return result;
    }

    @Override
    public synchronized Map<Long, SessionStats> statsBySessionIds(Collection<Long> sessionIds) {
        checkAvailable();
        Map<Long, SessionStats> result = new HashMap<>();
        for (Long id : sessionIds) {
            result.put(id, statsOf(id));
        }
        return result;
    }

    @Override
    public synchronized List<SessionRow> activeSessions(int limit) {
        checkAvailable();
        return sessions.values().stream().filter(row -> row.status() == SessionStatus.ACTIVE)
                .sorted(Comparator.comparing(SessionRow::updatedAt)).limit(limit).toList();
    }

    @Override
    public synchronized List<SessionRow> provisionalSessions(int limit) {
        checkAvailable();
        return sessions.values().stream()
                .filter(row -> row.source() == SessionSource.PROVISIONAL && row.status() != SessionStatus.MERGED)
                .limit(limit).toList();
    }

    @Override
    public synchronized List<VisitRow> visitsOfSession(long sessionId) {
        checkAvailable();
        return visits.values().stream().filter(visit -> visit.sessionId() == sessionId)
                .sorted(Comparator.comparing(VisitRow::joinedAt)).toList();
    }

    @Override
    public synchronized List<VisitRow> openVisits(String serverName) {
        checkAvailable();
        return visits.values().stream().filter(visit -> visit.serverName().equals(serverName) && visit.leftAt() == null)
                .toList();
    }

    @Override
    public synchronized List<SegmentRow> segmentsOfSession(long sessionId) {
        checkAvailable();
        return segments.stream().filter(segment -> segment.sessionId() == sessionId
                && segment.status() != SegmentStatus.DELETED).toList();
    }

    @Override
    public synchronized Optional<SegmentRow> findSegment(long segmentId) {
        checkAvailable();
        return segments.stream().filter(segment -> segment.id() == segmentId).findFirst();
    }

    @Override
    public synchronized Optional<SegmentRow> findSegmentByReplayName(String replayName) {
        checkAvailable();
        return segments.stream().filter(segment -> segment.replayName().equals(replayName)).findFirst();
    }

    @Override
    public List<RecordingRow> recordingsByStatus(String serverName, Set<RecordingStatus> statuses, int limit) {
        checkAvailable();
        return List.of();
    }

    @Override
    public List<SegmentRow> segmentsForRetention(String serverName, Instant olderThan, int limit) {
        checkAvailable();
        return List.of();
    }

    @Override
    public synchronized long countSegmentsOfPlayer(UUID playerUuid) {
        checkAvailable();
        return segments.stream().filter(segment -> segment.playerUuid().equals(playerUuid)).count();
    }

    @Override
    public List<SegmentRow> oldestSegmentsOfPlayer(UUID playerUuid, String serverName, int limit) {
        checkAvailable();
        return List.of();
    }

    @Override
    public void close() {
        // nichts zu schließen
    }

    // ------------------------------------------------------------------ Hilfen

    private void checkAvailable() {
        if (!available) {
            throw new StorageUnavailableException("Test: MariaDB nicht erreichbar");
        }
    }

    private List<SessionRow> ofPlayerSince(UUID player, Instant since) {
        return sessions.values().stream()
                .filter(row -> row.playerUuid().equals(player) && row.status() != SessionStatus.MERGED)
                .filter(row -> row.status() == SessionStatus.ACTIVE
                        || (row.logoutTime() != null && !row.logoutTime().isBefore(since))
                        || (row.loginTime() != null && !row.loginTime().isBefore(since)))
                .sorted(newestFirst())
                .toList();
    }

    private SessionStats statsOf(long sessionId) {
        List<SegmentRow> rows = segments.stream()
                .filter(segment -> segment.sessionId() == sessionId && segment.status() != SegmentStatus.DELETED)
                .toList();
        Set<String> servers = new TreeSet<>();
        long ticks = 0;
        int available = 0;
        for (SegmentRow row : rows) {
            servers.add(row.serverName());
            ticks += row.durationTicks();
            if (row.status().playable()) {
                available++;
            }
        }
        return new SessionStats(rows.size(), available, ticks, servers, null, null);
    }

    private static Comparator<SessionRow> newestFirst() {
        return Comparator.comparing((SessionRow row) -> row.loginTime() != null ? row.loginTime() : row.createdAt())
                .reversed();
    }

    private static @Nullable Instant earliest(@Nullable Instant a, @Nullable Instant b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isBefore(b) ? a : b;
    }
}
