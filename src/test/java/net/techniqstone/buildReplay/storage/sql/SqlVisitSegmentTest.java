package net.techniqstone.buildReplay.storage.sql;

import static net.techniqstone.buildReplay.storage.sql.TestSupport.T0;
import static net.techniqstone.buildReplay.storage.sql.TestSupport.await;
import static net.techniqstone.buildReplay.storage.sql.TestSupport.cloudSession;
import static net.techniqstone.buildReplay.storage.sql.TestSupport.count;
import static net.techniqstone.buildReplay.storage.sql.TestSupport.segment;
import static net.techniqstone.buildReplay.storage.sql.TestSupport.uuid;
import static net.techniqstone.buildReplay.storage.sql.TestSupport.visit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.techniqstone.buildReplay.config.PluginConfig;
import net.techniqstone.buildReplay.storage.model.RecordingRow;
import net.techniqstone.buildReplay.storage.model.RecordingStatus;
import net.techniqstone.buildReplay.storage.model.RecordingUpsert;
import net.techniqstone.buildReplay.storage.model.SegmentRow;
import net.techniqstone.buildReplay.storage.model.SegmentStatus;
import net.techniqstone.buildReplay.storage.model.SegmentUpsert;
import net.techniqstone.buildReplay.storage.model.SessionStats;
import net.techniqstone.buildReplay.storage.model.VisitEndReason;
import net.techniqstone.buildReplay.storage.model.VisitRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlVisitSegmentTest {

    @TempDir
    Path dir;

    private final StoreHarness harness = new StoreHarness();

    @AfterEach
    void closeStores() {
        harness.close();
    }

    private static SegmentUpsert withStatus(SegmentUpsert s, SegmentStatus status, Long size, String rawName) {
        return new SegmentUpsert(s.replayName(), s.sessionKey(), rawName, s.playerUuid(), s.playerName(),
                s.serverName(), s.worldName(), s.storageBackend(), s.storageRef(), s.startedAt(), s.endedAt(),
                s.firstActionAt(), s.lastActionAt(), s.durationTicks(), s.preRollMillis(), s.postRollMillis(),
                s.relevantActions(), s.actionSummary(), status, size);
    }

    @Test
    void visitStartAndEndAreIdempotent() {
        PluginConfig.DatabaseSettings db = EmbeddedMariaDb.newSettings();
        SqlReplayStore store = harness.start(db, dir);
        UUID player = uuid("Kai");
        String cloud = UUID.randomUUID().toString();
        store.upsertSession(cloudSession(cloud, player, T0, null, null));
        store.startVisit(visit("lobby-1:kai:1", cloud, player, "lobby", T0));
        store.startVisit(visit("lobby-1:kai:1", cloud, player, "lobby", T0.plusSeconds(30))); // Wiederholung
        store.endVisit("lobby-1:kai:1", T0.plusSeconds(60), VisitEndReason.QUIT);
        store.endVisit("lobby-1:kai:1", T0.plusSeconds(120), VisitEndReason.SHUTDOWN); // bereits beendet
        store.startVisit(visit("build-1:kai:2", cloud, player, "build", T0.plusSeconds(61)));
        store.endVisit("gibt-es-nicht", T0, VisitEndReason.RECOVERED);
        await(store.flush());

        long sessionId = store.findSessionByKey(cloud).orElseThrow().id();
        List<VisitRow> visits = store.visitsOfSession(sessionId);
        assertEquals(2, visits.size());
        VisitRow lobby = visits.get(0);
        assertEquals("lobby-1:kai:1", lobby.visitKey());
        assertEquals(T0, lobby.joinedAt());
        assertEquals(T0.plusSeconds(60), lobby.leftAt());
        assertEquals(VisitEndReason.QUIT, lobby.endReason());
        assertEquals("lobby-1", lobby.instanceId());
        assertNull(visits.get(1).leftAt());

        assertEquals(List.of("build-1:kai:2"), store.openVisits("build").stream().map(VisitRow::visitKey).toList());
        assertTrue(store.openVisits("lobby").isEmpty());
        assertEquals(2, count(db.tablePrefix(), "SELECT COUNT(*) FROM {p}server_visits"));
    }

    @Test
    void segmentUpsertsAndStatusUpdatesRespectTheirGuards() {
        PluginConfig.DatabaseSettings db = EmbeddedMariaDb.newSettings();
        SqlReplayStore store = harness.start(db, dir);
        UUID player = uuid("Lea");
        String cloud = UUID.randomUUID().toString();
        store.upsertRecording(new RecordingUpsert("brraw-lea", cloud, player, "Lea", "build", "build-1", "world", T0,
                RecordingStatus.RECORDING));
        SegmentUpsert base = segment("br-lea-1", cloud, player, "build", T0.plusSeconds(3), 90, SegmentStatus.SAVING);
        store.upsertSegment(withStatus(base, SegmentStatus.SAVING, null, "brraw-lea"));
        store.upsertSegment(withStatus(base, SegmentStatus.AVAILABLE, 4096L, null));
        store.upsertSegment(withStatus(base, SegmentStatus.SAVING, null, null)); // alter Vorgang erneut eingespielt
        store.updateSegmentStatus("br-lea-1", SegmentStatus.CORRUPT, "lässt sich nicht laden");
        store.updateSegmentStatus("br-lea-1", SegmentStatus.AVAILABLE, null);
        store.updateSegmentStatus("gibt-es-nicht", SegmentStatus.MISSING, null);
        await(store.flush());

        SegmentRow row = store.findSegmentByReplayName("br-lea-1").orElseThrow();
        assertEquals(SegmentStatus.AVAILABLE, row.status());
        assertEquals(4096L, row.sizeBytes());
        assertEquals("lässt sich nicht laden", row.note());
        assertEquals(count(db.tablePrefix(), "SELECT id FROM {p}recordings WHERE raw_name = 'brraw-lea'"),
                row.recordingId(), "Aufnahme bleibt verknüpft, auch wenn spätere Upserts sie nicht nennen");
        assertEquals(Map.of("BLOCK_PLACE", 4, "BLOCK_BREAK", 1), row.actionSummary());
        assertEquals(base.startedAt(), row.startedAt());
        assertEquals(base.endedAt(), row.endedAt());
        assertEquals(base.firstActionAt(), row.firstActionAt());
        assertEquals(base.lastActionAt(), row.lastActionAt());
        assertEquals(90 * 20, row.durationTicks());
        assertEquals(90, row.durationSeconds());
        assertEquals(10_000, row.preRollMillis());
        assertEquals("world", row.worldName());
        assertEquals("advancedreplay", row.storageBackend());
        assertEquals(T0, row.createdAt());
        assertEquals(row, store.findSegment(row.id()).orElseThrow());

        store.updateSegmentStatus("br-lea-1", SegmentStatus.DELETED, "Aufbewahrungsfrist");
        store.upsertSegment(withStatus(base, SegmentStatus.AVAILABLE, 1L, null));
        store.updateSegmentStatus("br-lea-1", SegmentStatus.AVAILABLE, null);
        await(store.flush());
        SegmentRow deleted = store.findSegmentByReplayName("br-lea-1").orElseThrow();
        assertEquals(SegmentStatus.DELETED, deleted.status(), "DELETED ist endgültig");
        assertEquals("Aufbewahrungsfrist", deleted.note());
        long sessionId = store.findSessionByKey(cloud).orElseThrow().id();
        assertTrue(store.segmentsOfSession(sessionId).isEmpty());
        assertEquals(1, count(db.tablePrefix(), "SELECT COUNT(*) FROM {p}segments"));
    }

    @Test
    void statsAggregateNonDeletedSegmentsPerSession() {
        PluginConfig.DatabaseSettings db = EmbeddedMariaDb.newSettings();
        SqlReplayStore store = harness.start(db, dir);
        UUID player = uuid("Mia");
        String withSegments = "c1c1c1c1-0000-4000-8000-000000000001";
        String withoutSegments = "c2c2c2c2-0000-4000-8000-000000000002";
        store.upsertSession(cloudSession(withSegments, player, T0, null, null));
        store.upsertSession(cloudSession(withoutSegments, player, T0, null, null));
        store.upsertSegment(segment("br-s1", withSegments, player, "build", T0.plusSeconds(10), 60, SegmentStatus.AVAILABLE));
        store.upsertSegment(segment("br-s2", withSegments, player, "lobby", T0.plusSeconds(100), 30, SegmentStatus.AVAILABLE));
        store.upsertSegment(segment("br-s3", withSegments, player, "build", T0.plusSeconds(200), 10, SegmentStatus.FAILED));
        store.upsertSegment(segment("br-s4", withSegments, player, "event", T0.plusSeconds(300), 100, SegmentStatus.AVAILABLE));
        store.updateSegmentStatus("br-s4", SegmentStatus.DELETED, null);
        await(store.flush());

        SessionStats expected = new SessionStats(3, 2, (60 + 30 + 10) * 20L, Set.of("build", "lobby"),
                T0.plusSeconds(10), T0.plusSeconds(210));
        String upper = withSegments.toUpperCase();
        Map<String, SessionStats> byCloud = store.statsByCloudSessionIds(List.of(upper, withoutSegments,
                UUID.randomUUID().toString()));
        assertEquals(Map.of(upper, expected, withoutSegments, SessionStats.EMPTY), byCloud);

        long id1 = store.findSessionByKey(withSegments).orElseThrow().id();
        long id2 = store.findSessionByKey(withoutSegments).orElseThrow().id();
        Map<Long, SessionStats> byId = store.statsBySessionIds(List.of(id1, id2, 987_654L));
        assertEquals(Map.of(id1, expected, id2, SessionStats.EMPTY), byId);
        assertEquals(Map.of(), store.statsBySessionIds(List.of()));
    }

    @Test
    void recordingsCanBeFinishedAndListedByStatus() {
        PluginConfig.DatabaseSettings db = EmbeddedMariaDb.newSettings();
        SqlReplayStore store = harness.start(db, dir);
        UUID player = uuid("Nils");
        String cloud = UUID.randomUUID().toString();
        store.upsertRecording(new RecordingUpsert("brraw-1", cloud, player, "Nils", "build", "build-1", "world", T0,
                RecordingStatus.RECORDING));
        store.upsertRecording(new RecordingUpsert("brraw-2", cloud, player, "Nils", "build", "build-1", null,
                T0.plusSeconds(1), RecordingStatus.RECORDING));
        store.upsertRecording(new RecordingUpsert("brraw-3", null, player, "Nils", "lobby", "lobby-1", "spawn", T0,
                RecordingStatus.RECORDING));
        store.finishRecording("brraw-1", RecordingStatus.FINALIZED, T0.plusSeconds(100), 12, null);
        store.finishRecording("brraw-2", RecordingStatus.RAW_PENDING, null, 3, "Server fährt herunter");
        store.upsertRecording(new RecordingUpsert("brraw-1", cloud, player, "Nils", "build", "build-1", "world", T0,
                RecordingStatus.RECORDING)); // Wiederholung darf FINALIZED nicht zurücksetzen
        store.finishRecording("brraw-unbekannt", RecordingStatus.FAILED, null, 0, "x");
        await(store.flush());

        List<RecordingRow> pending = store.recordingsByStatus("build",
                Set.of(RecordingStatus.RAW_PENDING, RecordingStatus.RECORDING), 10);
        assertEquals(List.of("brraw-2"), pending.stream().map(RecordingRow::rawName).toList());
        RecordingRow raw2 = pending.get(0);
        assertEquals("Server fährt herunter", raw2.error());
        assertNull(raw2.endedAt());
        assertNull(raw2.worldName());
        assertEquals(3, raw2.relevantActions());
        assertEquals(store.findSessionByKey(cloud).orElseThrow().id(), raw2.sessionId());

        RecordingRow raw1 = store.recordingsByStatus("build", Set.of(RecordingStatus.FINALIZED), 10).get(0);
        assertEquals("brraw-1", raw1.rawName());
        assertEquals(T0.plusSeconds(100), raw1.endedAt());
        assertEquals(12, raw1.relevantActions());
        assertEquals(RecordingStatus.FINALIZED, raw1.status());

        RecordingRow raw3 = store.recordingsByStatus("lobby", Set.of(RecordingStatus.RECORDING), 10).get(0);
        assertNull(raw3.sessionId());
        assertEquals("lobby-1", raw3.instanceId());
        assertTrue(store.recordingsByStatus("build", Set.of(), 10).isEmpty());
        assertEquals(1, store.recordingsByStatus("build", Set.of(RecordingStatus.values()), 1).size());
    }

    @Test
    void retentionAndPerPlayerLimitQueriesSkipDeletedSegments() {
        PluginConfig.DatabaseSettings db = EmbeddedMariaDb.newSettings();
        SqlReplayStore store = harness.start(db, dir);
        UUID alice = uuid("Alice");
        UUID bob = uuid("Bob");
        String aliceSession = UUID.randomUUID().toString();
        String bobSession = UUID.randomUUID().toString();
        Instant d40 = T0.minus(Duration.ofDays(40));
        store.upsertSegment(segment("br-a1", aliceSession, alice, "build", d40, 60, SegmentStatus.AVAILABLE));
        store.upsertSegment(segment("br-a2", aliceSession, alice, "build", T0.minus(Duration.ofDays(35)), 60,
                SegmentStatus.AVAILABLE));
        store.updateSegmentStatus("br-a2", SegmentStatus.DELETED, null);
        store.upsertSegment(segment("br-a3", aliceSession, alice, "build", T0.minus(Duration.ofDays(1)), 60,
                SegmentStatus.AVAILABLE));
        store.upsertSegment(segment("br-a4", aliceSession, alice, "lobby", T0.minus(Duration.ofDays(50)), 60,
                SegmentStatus.MISSING));
        store.upsertSegment(segment("br-b1", bobSession, bob, "build", T0.minus(Duration.ofDays(45)), 60,
                SegmentStatus.AVAILABLE));
        await(store.flush());

        Instant cutoff = T0.minus(Duration.ofDays(30));
        assertEquals(List.of("br-b1", "br-a1"), names(store.segmentsForRetention("build", cutoff, 10)));
        assertEquals(List.of("br-b1"), names(store.segmentsForRetention("build", cutoff, 1)));
        assertEquals(List.of("br-a4"), names(store.segmentsForRetention("lobby", cutoff, 10)));
        assertEquals(3, store.countSegmentsOfPlayer(alice));
        assertEquals(1, store.countSegmentsOfPlayer(bob));
        assertEquals(List.of("br-a1", "br-a3"), names(store.oldestSegmentsOfPlayer(alice, "build", 10)));
        assertEquals(List.of("br-a1"), names(store.oldestSegmentsOfPlayer(alice, "build", 1)));
        assertFalse(names(store.oldestSegmentsOfPlayer(alice, "build", 10)).contains("br-a2"));
    }

    private static List<String> names(List<SegmentRow> rows) {
        return rows.stream().map(SegmentRow::replayName).toList();
    }
}
