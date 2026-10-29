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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import net.techniqstone.buildReplay.config.PluginConfig;
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
import net.techniqstone.buildReplay.storage.model.VisitRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlSessionStoreTest {

    @TempDir
    Path dir;

    private final StoreHarness harness = new StoreHarness();

    @AfterEach
    void closeStores() {
        harness.close();
    }

    private static SessionUpsert provisional(String key, UUID player, Instant login) {
        return new SessionUpsert(key, null, player, SessionSource.PROVISIONAL, login, null, null);
    }

    @Test
    void sessionUpsertMergesTimesSourceAndStatus() {
        PluginConfig.DatabaseSettings db = EmbeddedMariaDb.newSettings();
        SqlReplayStore store = harness.start(db, dir);
        UUID player = uuid("Alice");
        String cloud = "59dc83e5-987b-4adc-85d7-2aaeaf7ddb53";

        store.upsertPlayer(player, "Alice", T0);
        store.upsertSession(new SessionUpsert(cloud, null, player, SessionSource.SIMPLECLOUD, T0.plusSeconds(10),
                null, null));
        store.upsertSession(new SessionUpsert(cloud, cloud, player, SessionSource.PROVISIONAL, T0, null, null));
        store.upsertSession(new SessionUpsert(cloud, null, player, SessionSource.SIMPLECLOUD, T0.plusSeconds(20),
                T0.plusSeconds(600), 600L));
        store.upsertSession(new SessionUpsert(cloud, null, player, SessionSource.SIMPLECLOUD, null, null, null));
        await(store.flush());

        SessionRow row = store.findSessionByKey(cloud).orElseThrow();
        assertEquals(cloud, row.cloudSessionId());
        assertEquals(T0, row.loginTime(), "frühester Login");
        assertEquals(T0.plusSeconds(600), row.logoutTime());
        assertEquals(600L, row.durationSeconds());
        assertEquals(SessionSource.SIMPLECLOUD, row.source());
        assertEquals(SessionStatus.CLOSED, row.status());
        assertEquals("Alice", row.playerName());
        assertEquals(T0, row.createdAt());
        assertEquals(1, count(db.tablePrefix(), "SELECT COUNT(*) FROM {p}sessions"));
        assertEquals(row, store.findSessionById(row.id()).orElseThrow());
        assertEquals(0, store.health().droppedWrites());
    }

    @Test
    void closeSessionNeverMovesTheLogoutBackwards() {
        PluginConfig.DatabaseSettings db = EmbeddedMariaDb.newSettings();
        SqlReplayStore store = harness.start(db, dir);
        UUID player = uuid("Bob");
        String key = "prov-" + UUID.randomUUID();
        store.upsertSession(provisional(key, player, T0));
        store.closeSession(key, T0.plusSeconds(500), 500L);
        store.closeSession(key, T0.plusSeconds(100), 100L);
        store.closeSession("prov-" + UUID.randomUUID(), T0, 1L); // unbekannt: nichts anzulegen
        await(store.flush());

        SessionRow row = store.findSessionByKey(key).orElseThrow();
        assertEquals(T0.plusSeconds(500), row.logoutTime());
        assertEquals(500L, row.durationSeconds());
        assertEquals(SessionStatus.CLOSED, row.status());
        assertEquals(SessionSource.PROVISIONAL, row.source());
        assertNull(row.cloudSessionId());
        assertEquals(1, count(db.tablePrefix(), "SELECT COUNT(*) FROM {p}sessions"));
    }

    @Test
    void writesForUnknownSessionsCreatePlaceholderSessions() {
        PluginConfig.DatabaseSettings db = EmbeddedMariaDb.newSettings();
        SqlReplayStore store = harness.start(db, dir);
        UUID player = uuid("Carol");
        String cloud = UUID.randomUUID().toString();
        String prov = "prov-" + UUID.randomUUID();
        store.startVisit(visit("v-cloud", cloud, player, "build", T0));
        store.startVisit(visit("v-prov", prov, player, "lobby", T0));
        await(store.flush());

        SessionRow cloudRow = store.findSessionByKey(cloud).orElseThrow();
        assertEquals(SessionSource.SIMPLECLOUD, cloudRow.source());
        assertEquals(cloud, cloudRow.cloudSessionId());
        assertEquals(SessionStatus.ACTIVE, cloudRow.status());
        assertNull(cloudRow.loginTime());
        assertEquals("Spieler", cloudRow.playerName(), "Platzhalter-Spieler bekommt den Namen aus dem Aufenthalt");

        SessionRow provRow = store.findSessionByKey(prov).orElseThrow();
        assertEquals(SessionSource.PROVISIONAL, provRow.source());
        assertNull(provRow.cloudSessionId());
        assertEquals(1, store.visitsOfSession(provRow.id()).size());
        assertEquals(1, count(db.tablePrefix(), "SELECT COUNT(*) FROM {p}players"));

        // der spätere echte Upsert ergänzt den Platzhalter statt eine zweite Zeile anzulegen
        store.upsertSession(cloudSession(cloud, player, T0.minusSeconds(3), null, null));
        store.upsertPlayer(player, "Carol", T0.plusSeconds(1));
        await(store.flush());
        SessionRow completed = store.findSessionByKey(cloud).orElseThrow();
        assertEquals(cloudRow.id(), completed.id());
        assertEquals(T0.minusSeconds(3), completed.loginTime());
        assertEquals("Carol", completed.playerName());
        assertEquals(2, count(db.tablePrefix(), "SELECT COUNT(*) FROM {p}sessions"));
    }

    @Test
    void twoServersUpdateTheSameSessionConcurrently() throws InterruptedException {
        PluginConfig.DatabaseSettings db = EmbeddedMariaDb.newSettings();
        int servers = 8;
        int rounds = 15;
        List<SqlReplayStore> stores = new ArrayList<>();
        for (int i = 0; i < servers; i++) {
            stores.add(harness.start(db, dir.resolve("server" + i)));
        }
        UUID player = uuid("Dora");
        String cloud = UUID.randomUUID().toString();
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < servers; i++) {
            int server = i;
            SqlReplayStore store = stores.get(i);
            Thread thread = new Thread(() -> {
                try {
                    go.await();
                    for (int n = 0; n < rounds; n++) {
                        Instant login = T0.plusSeconds(server * 7L + n);
                        boolean withLogout = n % 3 == 0;
                        Instant logout = withLogout ? T0.plusSeconds(3_600L + server * 10L + n) : null;
                        Long duration = withLogout ? 1_000L + server * 10L + n : null;
                        SessionSource source = server % 2 == 0 ? SessionSource.SIMPLECLOUD : SessionSource.PROVISIONAL;
                        store.upsertSession(new SessionUpsert(cloud, n % 2 == 0 ? cloud : null, player, source, login,
                                logout, duration));
                        store.startVisit(visit("server" + server + ":" + n, cloud, player, "server" + server, login));
                        if (n == 5) {
                            // Jeder Server hatte zwischenzeitlich eine vorläufige Sitzung, die jetzt zusammengeführt wird.
                            String prov = "prov-" + new UUID(server, n);
                            store.upsertSession(provisional(prov, player, login));
                            store.startVisit(visit("prov" + server, prov, player, "server" + server, login));
                            store.upsertSegment(segment("br-prov" + server, prov, player, "server" + server, login, 20,
                                    SegmentStatus.AVAILABLE));
                            store.mergeProvisionalSession(prov, new SessionUpsert(cloud, cloud, player,
                                    SessionSource.SIMPLECLOUD, null, null, null));
                        }
                    }
                    await(store.flush());
                } catch (Throwable t) {
                    failures.add(t);
                }
            });
            thread.start();
            threads.add(thread);
        }
        go.countDown();
        for (Thread thread : threads) {
            thread.join(60_000);
        }
        assertTrue(failures.isEmpty(), failures.toString());

        String p = db.tablePrefix();
        assertEquals(1, count(p, "SELECT COUNT(*) FROM {p}sessions WHERE status <> 'MERGED'"), "genau eine Sitzungszeile");
        assertEquals(servers, count(p, "SELECT COUNT(*) FROM {p}sessions WHERE status = 'MERGED'"));
        SessionRow row = stores.get(0).findSessionByKey(cloud).orElseThrow();
        assertEquals(T0, row.loginTime());
        assertEquals(T0.plusSeconds(3_600L + 70 + 12), row.logoutTime());
        assertEquals(1_000L + 70 + 12, row.durationSeconds());
        assertEquals(SessionSource.SIMPLECLOUD, row.source());
        assertEquals(SessionStatus.CLOSED, row.status());
        assertEquals(cloud, row.cloudSessionId());
        assertEquals(servers * (rounds + 1L), count(p, "SELECT COUNT(*) FROM {p}server_visits WHERE session_id = ?",
                row.id()), "auch die Aufenthalte der vorläufigen Sitzungen hängen jetzt am Ziel");
        assertEquals(servers, count(p, "SELECT COUNT(*) FROM {p}segments WHERE session_id = ?", row.id()));
        assertEquals(servers, stores.get(0).statsByCloudSessionIds(List.of(cloud)).get(cloud).segments());
        assertEquals(1, count(p, "SELECT COUNT(*) FROM {p}players"));
        for (SqlReplayStore store : stores) {
            assertEquals(0, store.health().droppedWrites(), harness.log.all());
        }
        assertEquals(0, harness.log.count(Level.WARNING, "verworfen"), harness.log.all());
    }

    @Test
    void findSessionsByRefMatchesCloudAndProvisionalPrefixesAndSkipsMergedSessions() {
        PluginConfig.DatabaseSettings db = EmbeddedMariaDb.newSettings();
        SqlReplayStore store = harness.start(db, dir);
        UUID alice = uuid("Alice");
        UUID bob = uuid("Bob");
        String cloud1 = "59dc83e5-987b-4adc-85d7-2aaeaf7ddb53";
        String cloud2 = "59dc83e5-0000-4000-8000-000000000002";
        String cloud3 = "aaaaaaaa-0000-4000-8000-000000000003";
        String bobCloud = "59dc83e5-1111-4000-8000-000000000004";
        String prov1 = "prov-1b2c3d4e-0000-4000-8000-000000000005";
        String prov2 = "prov-1b2c9999-0000-4000-8000-000000000006";
        store.upsertSession(cloudSession(cloud1, alice, T0.minusSeconds(300), null, null));
        store.upsertSession(cloudSession(cloud2, alice, T0.minusSeconds(200), null, null));
        store.upsertSession(cloudSession(cloud3, alice, T0.minusSeconds(100), null, null));
        store.upsertSession(cloudSession(bobCloud, bob, T0, null, null));
        store.upsertSession(provisional(prov1, alice, T0.minusSeconds(50)));
        store.upsertSession(provisional(prov2, alice, T0.minusSeconds(40)));
        store.mergeProvisionalSession(prov2, cloudSession(cloud3, alice, T0.minusSeconds(100), null, null));
        await(store.flush());

        assertEquals(List.of(cloud2, cloud1), keys(store.findSessionsByRef(alice, "59dc83e5", 10)), "neueste zuerst");
        assertEquals(List.of(cloud1), keys(store.findSessionsByRef(alice, "59DC-83E5-987B", 10)),
                "Bindestriche und Großschreibung egal");
        assertEquals(List.of(cloud2), keys(store.findSessionsByRef(alice, "59dc83e5", 1)));
        assertEquals(List.of(prov1), keys(store.findSessionsByRef(alice, "p1b2c", 10)), "MERGED fehlt");
        assertEquals(List.of(), keys(store.findSessionsByRef(alice, "p1b2c9", 10)));
        assertEquals(List.of(prov1), keys(store.findSessionsByRef(alice, "p1b2c3d4", 10)));
        assertEquals(List.of(bobCloud), keys(store.findSessionsByRef(bob, "59dc83e5", 10)), "nur eigene Sitzungen");
        assertEquals(List.of(), keys(store.findSessionsByRef(alice, "zz%", 10)));
        assertEquals(List.of(), keys(store.findSessionsByRef(alice, "  ", 10)));
        assertEquals(List.of(), keys(store.findSessionsByRef(alice, "59dc83e5", 0)));
    }

    @Test
    void sessionsOfPlayerPagesNewestFirstWithinTheWindowAndCounts() {
        PluginConfig.DatabaseSettings db = EmbeddedMariaDb.newSettings();
        SqlReplayStore store = harness.start(db, dir);
        UUID player = uuid("Erik");
        List<String> expected = new ArrayList<>();
        for (int day = 1; day <= 5; day++) {
            String key = "0000000" + day + "-0000-4000-8000-000000000000";
            store.upsertSession(cloudSession(key, player, T0.minus(Duration.ofDays(day)), null, null));
            expected.add(key);
        }
        store.upsertSession(cloudSession(UUID.randomUUID().toString(), player, T0.minus(Duration.ofDays(30)), null,
                null));
        store.upsertSession(cloudSession(UUID.randomUUID().toString(), uuid("Fremd"), T0, null, null));
        String prov = "prov-" + UUID.randomUUID();
        store.upsertSession(provisional(prov, player, T0.minusSeconds(3_600)));
        store.mergeProvisionalSession(prov, cloudSession(expected.get(0), player, T0.minus(Duration.ofDays(1)), null,
                null));
        await(store.flush());

        Instant since = T0.minus(Duration.ofDays(14));
        assertEquals(5, store.countSessionsOfPlayer(player, since));
        assertEquals(expected.subList(0, 2), keys(store.sessionsOfPlayer(player, since, 2, 0)));
        assertEquals(expected.subList(2, 4), keys(store.sessionsOfPlayer(player, since, 2, 2)));
        assertEquals(expected.subList(4, 5), keys(store.sessionsOfPlayer(player, since, 2, 4)));
        assertEquals(List.of(), keys(store.sessionsOfPlayer(player, since, 2, 6)));
        assertEquals(6, store.countSessionsOfPlayer(player, T0.minus(Duration.ofDays(60))));
    }

    @Test
    void mergingAProvisionalSessionMovesChildrenAndRedirectsLaterWrites() {
        PluginConfig.DatabaseSettings db = EmbeddedMariaDb.newSettings();
        SqlReplayStore store = harness.start(db, dir);
        UUID player = uuid("Greta");
        String prov = "prov-" + UUID.randomUUID();
        String cloud = UUID.randomUUID().toString();
        store.upsertSession(provisional(prov, player, T0));
        store.startVisit(visit("v1", prov, player, "build", T0));
        store.upsertRecording(new RecordingUpsert("brraw-1", prov, player, "Greta", "build", "build-1", "world", T0,
                RecordingStatus.RECORDING));
        SegmentUpsert first = segment("br-1", prov, player, "build", T0.plusSeconds(5), 60, SegmentStatus.AVAILABLE);
        store.upsertSegment(new SegmentUpsert(first.replayName(), first.sessionKey(), "brraw-1", first.playerUuid(),
                first.playerName(), first.serverName(), first.worldName(), first.storageBackend(), first.storageRef(),
                first.startedAt(), first.endedAt(), first.firstActionAt(), first.lastActionAt(), first.durationTicks(),
                first.preRollMillis(), first.postRollMillis(), first.relevantActions(), first.actionSummary(),
                first.status(), first.sizeBytes()));
        SessionUpsert target = cloudSession(cloud, player, T0.minusSeconds(5), null, null);
        store.mergeProvisionalSession(prov, target);
        // Ein anderer Server kennt die Zusammenführung noch nicht und schreibt weiter unter dem vorläufigen Schlüssel.
        store.startVisit(visit("v2", prov, player, "lobby", T0.plusSeconds(100)));
        store.upsertSegment(segment("br-2", prov, player, "lobby", T0.plusSeconds(120), 30, SegmentStatus.AVAILABLE));
        store.closeSession(prov, T0.plusSeconds(900), 905L);
        await(store.flush());

        SessionRow provRow = store.findSessionByKey(prov).orElseThrow();
        SessionRow targetRow = store.findSessionByKey(cloud).orElseThrow();
        assertEquals(SessionStatus.MERGED, provRow.status());
        assertEquals(targetRow.id(), provRow.mergedInto());
        assertEquals(SessionSource.SIMPLECLOUD, targetRow.source());
        assertEquals(SessionStatus.CLOSED, targetRow.status(), "Logout unter dem alten Schlüssel landet am Ziel");
        assertEquals(T0.plusSeconds(900), targetRow.logoutTime());
        assertEquals(T0.minusSeconds(5), targetRow.loginTime());

        assertEquals(List.of("v1", "v2"), store.visitsOfSession(targetRow.id()).stream().map(VisitRow::visitKey).toList());
        assertTrue(store.visitsOfSession(provRow.id()).isEmpty());
        List<SegmentRow> segments = store.segmentsOfSession(targetRow.id());
        assertEquals(List.of("br-1", "br-2"), segments.stream().map(SegmentRow::replayName).toList());
        long recordingId = count(db.tablePrefix(), "SELECT id FROM {p}recordings WHERE raw_name = 'brraw-1'");
        assertEquals(recordingId, segments.get(0).recordingId());
        assertEquals(targetRow.id(), count(db.tablePrefix(),
                "SELECT session_id FROM {p}recordings WHERE raw_name = 'brraw-1'"));
        assertFalse(store.provisionalSessions(10).stream().anyMatch(s -> s.sessionKey().equals(prov)));

        Map<Long, SessionStats> stats = store.statsBySessionIds(List.of(targetRow.id(), provRow.id()));
        assertEquals(2, stats.get(targetRow.id()).segments());
        assertEquals(SessionStats.EMPTY, stats.get(provRow.id()));

        // erneute Zusammenführung (z. B. zweiter Leader) ändert nichts
        store.mergeProvisionalSession(prov, target);
        await(store.flush());
        assertEquals(2, count(db.tablePrefix(), "SELECT COUNT(*) FROM {p}sessions"));
        assertEquals(2, store.visitsOfSession(targetRow.id()).size());
        assertEquals(0, store.health().droppedWrites());
    }

    @Test
    void mergingAnUnknownProvisionalKeyStillRedirectsLaterWrites() {
        PluginConfig.DatabaseSettings db = EmbeddedMariaDb.newSettings();
        SqlReplayStore store = harness.start(db, dir);
        UUID player = uuid("Hugo");
        String prov = "prov-" + UUID.randomUUID();
        String cloud = UUID.randomUUID().toString();
        store.mergeProvisionalSession(prov, cloudSession(cloud, player, T0, null, null));
        store.startVisit(visit("late", prov, player, "build", T0.plusSeconds(1)));
        await(store.flush());

        SessionRow targetRow = store.findSessionByKey(cloud).orElseThrow();
        assertEquals(SessionStatus.MERGED, store.findSessionByKey(prov).orElseThrow().status());
        assertEquals(List.of("late"), store.visitsOfSession(targetRow.id()).stream().map(VisitRow::visitKey).toList());
        assertTrue(store.provisionalSessions(10).isEmpty());
    }

    @Test
    void activeAndProvisionalSessionsAreListedOldestUpdateFirst() {
        PluginConfig.DatabaseSettings db = EmbeddedMariaDb.newSettings();
        SqlReplayStore store = harness.start(db, dir);
        UUID player = uuid("Ida");
        String cloudActive = UUID.randomUUID().toString();
        String prov = "prov-" + UUID.randomUUID();
        String cloudClosed = UUID.randomUUID().toString();
        store.upsertSession(cloudSession(cloudActive, player, T0, null, null));
        await(store.flush());
        harness.clock.advance(Duration.ofMinutes(1));
        store.upsertSession(provisional(prov, player, T0));
        await(store.flush());
        harness.clock.advance(Duration.ofMinutes(1));
        store.upsertSession(cloudSession(cloudClosed, player, T0, T0.plusSeconds(60), 60L));
        await(store.flush());

        assertEquals(List.of(cloudActive, prov), keys(store.activeSessions(10)));
        assertEquals(List.of(cloudActive), keys(store.activeSessions(1)));
        assertEquals(List.of(prov), keys(store.provisionalSessions(10)));

        harness.clock.advance(Duration.ofMinutes(1));
        store.upsertSession(cloudSession(cloudActive, player, T0, null, null)); // Abgleich ohne Änderung
        await(store.flush());
        assertEquals(List.of(prov, cloudActive), keys(store.activeSessions(10)));
    }

    @Test
    void playerUpsertKeepsTheNewestNameAndTheFirstSighting() {
        PluginConfig.DatabaseSettings db = EmbeddedMariaDb.newSettings();
        SqlReplayStore store = harness.start(db, dir);
        UUID player = uuid("Jan");
        store.upsertPlayer(player, "Old", T0);
        store.upsertPlayer(player, "New", T0.plusSeconds(10));
        store.upsertPlayer(player, "Older", T0.minusSeconds(10));
        store.upsertPlayer(player, "WayTooLongPlayerName_XYZ", T0.minusSeconds(20));
        await(store.flush());
        String p = db.tablePrefix();
        assertEquals(List.of("New"), TestSupport.strings(p, "SELECT last_name FROM {p}players"));
        assertEquals(1, count(p, "SELECT COUNT(*) FROM {p}players WHERE first_seen = ? AND last_seen = ?",
                SqlValues.toDb(T0.minusSeconds(20)), SqlValues.toDb(T0.plusSeconds(10))));
    }

    private static List<String> keys(List<SessionRow> rows) {
        return rows.stream().map(SessionRow::sessionKey).toList();
    }
}
