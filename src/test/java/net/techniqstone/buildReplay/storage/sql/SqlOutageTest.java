package net.techniqstone.buildReplay.storage.sql;

import static net.techniqstone.buildReplay.storage.sql.TestSupport.T0;
import static net.techniqstone.buildReplay.storage.sql.TestSupport.await;
import static net.techniqstone.buildReplay.storage.sql.TestSupport.cloudSession;
import static net.techniqstone.buildReplay.storage.sql.TestSupport.count;
import static net.techniqstone.buildReplay.storage.sql.TestSupport.segment;
import static net.techniqstone.buildReplay.storage.sql.TestSupport.uuid;
import static net.techniqstone.buildReplay.storage.sql.TestSupport.visit;
import static net.techniqstone.buildReplay.storage.sql.TestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import net.techniqstone.buildReplay.config.PluginConfig;
import net.techniqstone.buildReplay.storage.StorageUnavailableException;
import net.techniqstone.buildReplay.storage.StoreHealth;
import net.techniqstone.buildReplay.storage.model.SegmentStatus;
import net.techniqstone.buildReplay.storage.model.SessionRow;
import net.techniqstone.buildReplay.storage.model.SessionStatus;
import net.techniqstone.buildReplay.storage.model.VisitEndReason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlOutageTest {

    @TempDir
    Path dir;

    private final StoreHarness harness = new StoreHarness();

    @AfterEach
    void closeStores() {
        harness.close();
    }

    /** Einstellungen, die auf einen Port zeigen, auf dem nichts lauscht. */
    private static PluginConfig.DatabaseSettings unreachable(String prefix, String password) {
        PluginConfig.DatabaseSettings base = EmbeddedMariaDb.settings(prefix, EmbeddedMariaDb.freePort());
        return new PluginConfig.DatabaseSettings(true, base.host(), base.port(), base.database(), base.username(),
                password, prefix, base.poolSize(), Duration.ofMillis(500), Map.of());
    }

    @Test
    void mariaDbTemporarilyUnavailable() throws Exception {
        PluginConfig.DatabaseSettings db = EmbeddedMariaDb.newSettings();
        String p = db.tablePrefix();
        SqlReplayStore store = harness.start(db, dir);
        UUID player = uuid("Olga");
        String cloud = UUID.randomUUID().toString();
        store.upsertSession(cloudSession(cloud, player, T0, null, null));
        await(store.flush());
        assertEquals(1, count(p, "SELECT COUNT(*) FROM {p}sessions"));

        try {
            EmbeddedMariaDb.stop();
            store.startVisit(visit("build-1:olga:1", cloud, player, "build", T0.plusSeconds(5)));
            store.upsertSegment(segment("br-outage-1", cloud, player, "build", T0.plusSeconds(10), 60,
                    SegmentStatus.AVAILABLE));
            store.upsertSession(cloudSession(cloud, player, T0, null, null));
            store.endVisit("build-1:olga:1", T0.plusSeconds(890), VisitEndReason.QUIT);
            store.closeSession(cloud, T0.plusSeconds(900), 900L);

            waitUntil("Ausfall erkannt", Duration.ofSeconds(20), () -> !store.health().connected());
            StoreHealth down = store.health();
            assertTrue(down.queuedWrites() > 0, down.toString());
            assertNotNull(down.lastError());
            assertThrows(StorageUnavailableException.class, () -> store.findSessionByKey(cloud));

            await(store.flush()); // während des Ausfalls: abgeschlossen durch Sicherung in der Outbox
            assertTrue(Files.exists(store.outboxFile()));
            assertEquals(store.health().queuedWrites(), store.health().persistedOutboxEntries());
        } finally {
            EmbeddedMariaDb.restart();
        }

        waitUntil("wieder verbunden und abgearbeitet", Duration.ofSeconds(40), () -> {
            store.flush(); // stößt einen sofortigen Verbindungsversuch an
            StoreHealth h = store.health();
            return h.connected() && h.queuedWrites() == 0;
        });
        waitUntil("Outbox-Datei gelöscht", Duration.ofSeconds(10), () -> !Files.exists(store.outboxFile()));

        assertEquals(1, count(p, "SELECT COUNT(*) FROM {p}sessions"));
        assertEquals(1, count(p, "SELECT COUNT(*) FROM {p}server_visits"));
        assertEquals(1, count(p, "SELECT COUNT(*) FROM {p}server_visits WHERE left_at IS NOT NULL"));
        assertEquals(1, count(p, "SELECT COUNT(*) FROM {p}segments"));
        SessionRow row = store.findSessionByKey(cloud).orElseThrow();
        assertEquals(SessionStatus.CLOSED, row.status());
        assertEquals(T0.plusSeconds(900), row.logoutTime());
        StoreHealth up = store.health();
        assertEquals(0, up.droppedWrites(), harness.log.all());
        assertEquals(0, up.persistedOutboxEntries());
        assertEquals(1, harness.log.count(Level.WARNING, "Verbindung zu MariaDB verloren"), harness.log.all());
        assertTrue(harness.log.count(Level.INFO, "wieder erreichbar") >= 1, harness.log.all());
    }

    @Test
    void theOutboxSurvivesAStoreRestartWhileTheDatabaseIsDown() throws IOException {
        String prefix = EmbeddedMariaDb.newPrefix();
        PluginConfig.DatabaseSettings down = unreachable(prefix, "");
        Path data = dir.resolve("server");
        UUID player = uuid("Paul");
        String cloud = UUID.randomUUID().toString();

        SqlReplayStore first = harness.create(down, data, 1_000);
        first.start();
        assertFalse(first.health().connected());
        first.upsertSession(cloudSession(cloud, player, T0, null, null));
        first.startVisit(visit("v1", cloud, player, "build", T0));
        first.upsertSegment(segment("br-journal", cloud, player, "build", T0.plusSeconds(1), 20, SegmentStatus.AVAILABLE));
        await(first.flush());
        assertEquals(3, first.health().persistedOutboxEntries());
        first.close();
        Path outbox = first.outboxFile();
        assertEquals(3, Files.readAllLines(outbox).size());

        SqlReplayStore second = harness.create(down, data, 1_000);
        second.start();
        StoreHealth reloaded = second.health();
        assertEquals(3, reloaded.queuedWrites());
        assertEquals(3, reloaded.persistedOutboxEntries());
        second.endVisit("v1", T0.plusSeconds(60), VisitEndReason.QUIT);
        second.close();
        assertEquals(4, Files.readAllLines(outbox).size());

        SqlReplayStore third = harness.start(EmbeddedMariaDb.settings(prefix), data);
        waitUntil("Outbox nachgeholt", Duration.ofSeconds(20),
                () -> third.health().queuedWrites() == 0 && !Files.exists(outbox));
        assertEquals(1, count(prefix, "SELECT COUNT(*) FROM {p}sessions"));
        assertEquals(1, count(prefix, "SELECT COUNT(*) FROM {p}server_visits WHERE left_at IS NOT NULL"));
        assertEquals(1, count(prefix, "SELECT COUNT(*) FROM {p}segments"));
        assertEquals(0, third.health().persistedOutboxEntries());
        assertEquals(0, third.health().droppedWrites());
    }

    @Test
    void aFullOutboxDropsAndCountsNewWritesWithASingleWarning() {
        PluginConfig.DatabaseSettings down = unreachable(EmbeddedMariaDb.newPrefix(), "");
        SqlReplayStore store = harness.create(down, dir, 5);
        store.start();
        for (int i = 0; i < 12; i++) {
            store.upsertPlayer(uuid("P" + i), "P" + i, T0);
        }
        StoreHealth health = store.health();
        assertEquals(5, health.queuedWrites());
        assertEquals(7, health.droppedWrites());
        assertEquals(1, harness.log.count(Level.WARNING, "Outbox voll"), harness.log.all());
    }

    @Test
    void startReturnsQuicklyAndNeverLogsThePasswordWhenTheDatabaseIsDown() {
        PluginConfig.DatabaseSettings down = unreachable(EmbeddedMariaDb.newPrefix(), "Sup3rGeheim!");
        SqlReplayStore store = harness.create(down, dir, 100);
        long start = System.nanoTime();
        store.start();
        long millis = Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertTrue(millis < 4_000, "start() dauerte " + millis + " ms");

        StoreHealth health = store.health();
        assertTrue(health.enabled());
        assertFalse(health.connected());
        assertEquals(0, health.schemaVersion());
        assertNotNull(health.lastError());
        assertFalse(health.lastError().contains("Sup3rGeheim!"));
        assertEquals(1, harness.log.count(Level.WARNING, "MariaDB nicht erreichbar"), harness.log.all());
        StorageUnavailableException error = assertThrows(StorageUnavailableException.class,
                () -> store.sessionsOfPlayer(uuid("Q"), T0, 5, 0));
        assertFalse(error.getMessage().contains("Sup3rGeheim!"));
        store.close();
        assertFalse(harness.log.all().contains("Sup3rGeheim!"));
    }

    @Test
    void closeExecutesQueuedWritesBeforeClosingThePool() {
        PluginConfig.DatabaseSettings db = EmbeddedMariaDb.newSettings();
        SqlReplayStore store = harness.start(db, dir);
        for (int i = 0; i < 50; i++) {
            store.upsertPlayer(uuid("C" + i), "C" + i, T0);
        }
        store.close();
        assertEquals(50, count(db.tablePrefix(), "SELECT COUNT(*) FROM {p}players"));
        assertFalse(Files.exists(store.outboxFile()));
        assertEquals(0, store.health().queuedWrites());

        store.upsertPlayer(uuid("late"), "late", T0); // nach close: verworfen, nicht in die Outbox
        assertEquals(1, store.health().droppedWrites());
        assertTrue(store.flush().isDone());
        assertThrows(StorageUnavailableException.class, () -> store.findSessionById(1));
    }

    @Test
    void writesQueuedBeforeStartAreKeptWhenTheStoreClosesWithoutEverStarting() throws IOException {
        PluginConfig.DatabaseSettings db = EmbeddedMariaDb.newSettings();
        SqlReplayStore store = harness.create(db, dir, 100);
        store.upsertPlayer(uuid("early"), "early", T0);
        store.close();
        assertEquals(1, Files.readAllLines(store.outboxFile()).size());

        SqlReplayStore next = harness.start(db, dir);
        waitUntil("nachgeholt", Duration.ofSeconds(10), () -> next.health().queuedWrites() == 0);
        assertEquals(1, count(db.tablePrefix(), "SELECT COUNT(*) FROM {p}players"));
    }
    @Test
    void aCorruptOutboxIsBackedUpAndItsReadableEntriesAreReplayed() throws IOException {
        PluginConfig.DatabaseSettings db = EmbeddedMariaDb.newSettings();
        Path outbox = dir.resolve("outbox").resolve("outbox.jsonl");
        Files.createDirectories(outbox.getParent());
        WriteOpCodec codec = new WriteOpCodec();
        Files.writeString(outbox, codec.encode(new WriteOp.UpsertPlayer(uuid("R"), "R", T0)) + "\n{kaputt\n");

        SqlReplayStore store = harness.start(db, dir);
        waitUntil("nachgeholt", Duration.ofSeconds(10),
                () -> store.health().queuedWrites() == 0 && !Files.exists(outbox));
        assertEquals(1, count(db.tablePrefix(), "SELECT COUNT(*) FROM {p}players"));
        assertEquals(1, store.health().droppedWrites());
        try (var files = Files.list(outbox.getParent())) {
            assertEquals(1, files.filter(f -> f.getFileName().toString().startsWith("outbox.jsonl.bad-")).count());
        }
    }

    @Test
    void invalidWritesAreDroppedWithAWarningInsteadOfBeingQueued() {
        PluginConfig.DatabaseSettings down = unreachable(EmbeddedMariaDb.newPrefix(), "");
        SqlReplayStore store = harness.create(down, dir, 100);
        store.start();
        store.closeSession("x".repeat(65), T0, null);
        store.startVisit(new net.techniqstone.buildReplay.storage.model.VisitStart("v", "s", uuid("S"), null,
                "build", "build-1", T0));
        assertEquals(0, store.health().queuedWrites());
        assertEquals(2, store.health().droppedWrites());
        assertEquals(2, harness.log.count(Level.WARNING, "Ungültiger Schreibvorgang verworfen"), harness.log.all());
    }

    @Test
    void aWriteTheDatabaseRejectsIsDroppedAndDoesNotBlockLaterWrites() {
        PluginConfig.DatabaseSettings db = EmbeddedMariaDb.newSettings();
        SqlReplayStore store = harness.start(db, dir);
        store.upsertPlayer(uuid("Zukunft"), "Zukunft", java.time.Instant.parse("+20000-01-01T00:00:00Z"));
        store.upsertPlayer(uuid("Unendlich"), "Unendlich", java.time.Instant.MAX);
        store.upsertPlayer(uuid("Normal"), "Normal", T0);
        await(store.flush());
        assertEquals(List.of("Normal"), TestSupport.strings(db.tablePrefix(), "SELECT last_name FROM {p}players"));
        assertEquals(2, store.health().droppedWrites(), harness.log.all());
        assertTrue(store.health().connected(), "ein Datenfehler ist kein Verbindungsausfall");
    }
}
