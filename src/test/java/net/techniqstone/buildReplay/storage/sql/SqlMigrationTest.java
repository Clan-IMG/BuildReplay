package net.techniqstone.buildReplay.storage.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import net.techniqstone.buildReplay.config.PluginConfig;
import net.techniqstone.buildReplay.storage.StoreHealth;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlMigrationTest {

    @TempDir
    Path dir;

    private final StoreHarness harness = new StoreHarness();

    @AfterEach
    void closeStores() {
        harness.close();
    }

    @Test
    void migrationCreatesTheSchemaOnceAndASecondRunIsANoOp() {
        PluginConfig.DatabaseSettings db = EmbeddedMariaDb.newSettings();
        String p = db.tablePrefix();
        SqlReplayStore first = harness.start(db, dir.resolve("a"));
        StoreHealth health = first.health();
        assertTrue(health.connected(), harness.log.all());
        assertEquals(1, health.schemaVersion());
        assertEquals(6, TestSupport.count(p, "SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = DATABASE() AND table_name LIKE ?", p.replace("_", "\\_") + "%"));
        first.close();

        long appliedBefore = harness.log.count(Level.INFO, "Datenbankschema auf Version 1 aktualisiert");
        SqlReplayStore second = harness.start(db, dir.resolve("b"));
        assertEquals(1, second.health().schemaVersion());
        assertEquals(1, appliedBefore);
        assertEquals(appliedBefore, harness.log.count(Level.INFO, "Datenbankschema auf Version 1 aktualisiert"),
                "zweiter Lauf wendet nichts an");
        assertEquals(1, TestSupport.count(p, "SELECT COUNT(*) FROM {p}schema_version"));
        assertEquals("baseline", TestSupport.strings(p, "SELECT description FROM {p}schema_version").get(0));
    }

    @Test
    void severalServersMigratingConcurrentlyApplyEachVersionExactlyOnce() throws InterruptedException {
        PluginConfig.DatabaseSettings db = EmbeddedMariaDb.newSettings();
        int servers = 4;
        List<SqlReplayStore> stores = new ArrayList<>();
        for (int i = 0; i < servers; i++) {
            stores.add(harness.create(db, dir.resolve("s" + i), 1_000));
        }
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (SqlReplayStore store : stores) {
            Thread thread = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                store.start();
            });
            thread.start();
            threads.add(thread);
        }
        go.countDown();
        for (Thread thread : threads) {
            thread.join(30_000);
        }
        for (SqlReplayStore store : stores) {
            TestSupport.waitUntil("alle Server verbunden", java.time.Duration.ofSeconds(20),
                    () -> store.health().connected());
            assertEquals(1, store.health().schemaVersion());
        }
        assertEquals(1, TestSupport.count(db.tablePrefix(), "SELECT COUNT(*) FROM {p}schema_version"));
        assertEquals(1, harness.log.count(Level.INFO, "Datenbankschema auf Version 1 aktualisiert"), harness.log.all());
    }
}
