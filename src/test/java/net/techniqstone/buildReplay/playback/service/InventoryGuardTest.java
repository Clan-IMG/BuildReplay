package net.techniqstone.buildReplay.playback.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.LogRecord;
import java.util.stream.Stream;
import net.techniqstone.buildReplay.recording.service.fakes.FakePlayer;
import net.techniqstone.buildReplay.recording.service.fakes.FakeTaskRunner;
import net.techniqstone.buildReplay.recording.service.fakes.TestSupport;
import org.bukkit.GameMode;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InventoryGuardTest {

    private static final Instant START = Instant.parse("2026-10-29T12:00:00Z");

    /** Kodiert nur die Anzahl der Plätze - echte ItemStacks brauchen einen laufenden Server. */
    private static final InventoryGuard.ItemCodec SLOT_CODEC = new InventoryGuard.ItemCodec() {
        @Override
        public String encode(ItemStack[] items) {
            return "slots:" + items.length;
        }

        @Override
        public ItemStack[] decode(String data) {
            if (!data.startsWith("slots:")) {
                throw new IllegalArgumentException("kaputt");
            }
            return new ItemStack[Integer.parseInt(data.substring("slots:".length()))];
        }
    };

    @TempDir
    Path dataFolder;

    private final List<LogRecord> logs = new ArrayList<>();
    private FakeTaskRunner tasks;
    private TestSupport.MutableClock clock;
    private Path directory;
    private InventoryGuard guard;
    private FakePlayer steve;

    @BeforeEach
    void setUp() {
        tasks = new FakeTaskRunner();
        clock = new TestSupport.MutableClock(START);
        directory = dataFolder.resolve("inventory-backups");
        guard = newGuard();
        steve = new FakePlayer("Steve");
        steve.level = 7;
        steve.exp = 0.5f;
    }

    private InventoryGuard newGuard() {
        return new InventoryGuard(directory, tasks, clock, TestSupport.quietLogger(logs), SLOT_CODEC);
    }

    /** So verändert AdvancedReplay den Zuschauer beim Start. */
    private void advancedReplayStartsWatching(FakePlayer viewer) {
        viewer.level = 0;
        viewer.exp = 0f;
        viewer.allowFlight = true;
        viewer.flying = true;
    }

    @Test
    void aBackupIsWrittenToDiskAndRemovedAfterRestoring() throws Exception {
        assertTrue(guard.backup(steve.player()));
        Path file = directory.resolve(steve.uuid + ".yml");
        assertTrue(Files.isRegularFile(file));
        String content = Files.readString(file);
        assertTrue(content.contains("level: 7"), content);
        assertTrue(content.contains("name: Steve"), content);
        assertTrue(guard.hasBackup(steve.uuid));

        advancedReplayStartsWatching(steve);
        assertTrue(guard.restore(steve.player()));

        assertEquals(7, steve.level);
        assertEquals(0.5f, steve.exp);
        assertFalse(steve.allowFlight);
        assertFalse(steve.flying);
        assertEquals(1, steve.inventoryClears);
        assertEquals(1, steve.inventorySets);
        assertFalse(Files.exists(file));
        assertFalse(guard.hasBackup(steve.uuid));
        assertFalse(guard.restore(steve.player()), "Nur einmal wiederherstellen");
    }

    @Test
    void discardingDeletesTheBackupWithoutTouchingThePlayer() {
        guard.backup(steve.player());
        advancedReplayStartsWatching(steve);

        guard.discard(steve.uuid);

        assertEquals(0, steve.level);
        assertEquals(0, steve.inventorySets);
        assertFalse(Files.exists(directory.resolve(steve.uuid + ".yml")));
    }

    @Test
    void aSecondBackupKeepsTheOlderState() {
        guard.backup(steve.player());
        advancedReplayStartsWatching(steve);

        guard.backup(steve.player());
        guard.restore(steve.player());

        assertEquals(7, steve.level);
    }

    @Test
    void anOrphanedBackupOnDiskIsRestoredAfterAServerCrash() throws Exception {
        guard.backup(steve.player());
        advancedReplayStartsWatching(steve);

        InventoryGuard afterRestart = newGuard();
        boolean restored = afterRestart.restoreOrphan(steve.player(), () -> true).get();

        assertTrue(restored);
        assertEquals(7, steve.level);
        assertFalse(steve.allowFlight);
        assertFalse(Files.exists(directory.resolve(steve.uuid + ".yml")));
    }

    @Test
    void anOrphanedBackupIsNotAppliedWhileThePlayerIsWatching() throws Exception {
        guard.backup(steve.player());
        advancedReplayStartsWatching(steve);

        boolean restored = newGuard().restoreOrphan(steve.player(), () -> false).get();

        assertFalse(restored);
        assertEquals(0, steve.level);
        assertTrue(Files.exists(directory.resolve(steve.uuid + ".yml")));
    }

    @Test
    void aBackupLeftInMemoryIsAppliedOnTheNextJoin() throws Exception {
        guard.backup(steve.player());
        advancedReplayStartsWatching(steve);

        assertTrue(guard.restoreOrphan(steve.player(), () -> true).get());
        assertEquals(7, steve.level);
    }

    @Test
    void withoutABackupNothingHappensOnJoin() throws Exception {
        assertFalse(guard.restoreOrphan(steve.player(), () -> true).get());
        assertEquals(7, steve.level);
        assertEquals(0, steve.inventorySets);
    }

    @Test
    void anUnreadableBackupIsMovedAsideInsteadOfBeingAppliedOnEveryJoin() throws Exception {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(steve.uuid + ".yml"), "version: 1\nitems: kaputt\n");

        assertFalse(guard.restoreOrphan(steve.player(), () -> true).get());

        assertFalse(Files.exists(directory.resolve(steve.uuid + ".yml")));
        try (Stream<Path> files = Files.list(directory)) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().startsWith(steve.uuid + ".yml.broken-")));
        }
    }

    @Test
    void flightIsLeftAloneInCreativeMode() {
        steve.gameMode = GameMode.CREATIVE;
        guard.backup(steve.player());
        advancedReplayStartsWatching(steve);

        guard.restore(steve.player());

        assertTrue(steve.allowFlight);
        assertTrue(steve.flying);
        assertEquals(7, steve.level);
    }

    @Test
    void backupFilesAreListedByPlayer() throws Exception {
        FakePlayer alex = new FakePlayer("Alex");
        guard.backup(steve.player());
        guard.backup(alex.player());
        Files.writeString(directory.resolve("notes.yml"), "egal");

        List<UUID> players = guard.files().list();

        assertEquals(2, players.size());
        assertTrue(players.containsAll(List.of(steve.uuid, alex.uuid)));
    }

    @Test
    void encodingRoundTripsLevelExperienceAndFlight() throws Exception {
        InventoryGuard.Snapshot snapshot = new InventoryGuard.Snapshot(new ItemStack[41], 12, 0.25f, true, false,
                START);

        InventoryGuard.Snapshot decoded = guard.decode(guard.encode(steve.uuid, "Steve", snapshot));

        assertEquals(41, decoded.contents().length);
        assertEquals(12, decoded.level());
        assertEquals(0.25f, decoded.exp());
        assertTrue(decoded.allowFlight());
        assertFalse(decoded.flying());
        assertEquals(START, decoded.createdAt());
    }
}
