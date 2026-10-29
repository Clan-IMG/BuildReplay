package net.techniqstone.buildReplay.storage.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.techniqstone.buildReplay.config.PluginConfig;
import net.techniqstone.buildReplay.storage.StorageUnavailableException;
import net.techniqstone.buildReplay.storage.StoreHealth;
import net.techniqstone.buildReplay.storage.model.SegmentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DisabledStoreTest {

    @TempDir
    Path dir;

    @Test
    void aDisabledStoreCountsWritesAsDroppedAndRefusesReads() {
        PluginConfig.DatabaseSettings disabled = new PluginConfig.DatabaseSettings(false, "127.0.0.1", 1, "db", "u",
                "secret", "br_", 2, Duration.ofSeconds(1), Map.of());
        TestSupport.CapturingLog log = new TestSupport.CapturingLog();
        try (SqlReplayStore store = new SqlReplayStore(disabled, TestSupport.resilience(100), dir,
                new TestSupport.MutableClock(TestSupport.T0), log.logger())) {
            store.start();
            assertFalse(store.isEnabled());
            UUID player = TestSupport.uuid("A");
            store.upsertPlayer(player, "A", TestSupport.T0);
            store.updateSegmentStatus("br-x", SegmentStatus.DELETED, null);
            assertTrue(store.flush().isDone());

            StoreHealth health = store.health();
            assertEquals(new StoreHealth(false, false, 0, 0, 2, null, null, 0), health);
            assertThrows(StorageUnavailableException.class, () -> store.findSessionByKey("x"));
            assertThrows(StorageUnavailableException.class, () -> store.sessionsOfPlayer(player, TestSupport.T0, 5, 0));
            assertThrows(StorageUnavailableException.class, () -> store.statsBySessionIds(List.of(1L)));
            assertTrue(log.all().contains("deaktiviert"), log.all());
        }
        assertFalse(Files.exists(dir.resolve("outbox")), "ein deaktivierter Store legt keine Outbox an");
    }
}
