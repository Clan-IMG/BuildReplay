package net.techniqstone.buildReplay.recording.advancedreplay;

import static net.techniqstone.buildReplay.recording.advancedreplay.TestReplayData.STEVE;
import static net.techniqstone.buildReplay.recording.advancedreplay.TestReplayData.actions;
import static net.techniqstone.buildReplay.recording.advancedreplay.TestReplayData.item;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.comphenix.protocol.wrappers.EnumWrappers;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import me.jumper251.replay.filesystem.saving.IReplaySaver;
import me.jumper251.replay.replaysystem.Replay;
import me.jumper251.replay.replaysystem.data.ActionData;
import me.jumper251.replay.replaysystem.data.ActionType;
import me.jumper251.replay.replaysystem.data.ReplayData;
import me.jumper251.replay.replaysystem.data.types.BlockChangeData;
import me.jumper251.replay.replaysystem.data.types.ChatData;
import me.jumper251.replay.replaysystem.data.types.EntityActionData;
import me.jumper251.replay.replaysystem.data.types.InvData;
import me.jumper251.replay.replaysystem.data.types.LocationData;
import me.jumper251.replay.replaysystem.data.types.MovingData;
import me.jumper251.replay.replaysystem.data.types.SpawnData;
import me.jumper251.replay.utils.fetcher.Consumer;
import net.techniqstone.buildReplay.recording.ReplayEngineException;
import net.techniqstone.buildReplay.recording.SavedSegment;
import net.techniqstone.buildReplay.recording.SegmentCut;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("deprecation")
class SegmentWriterTest {

    /** Schreibt wie AR's {@code DefaultReplaySaver}: GZIP + Java-Serialisierung von {@code ReplayData}. */
    private static final class FileSaver implements IReplaySaver {
        private final File directory;
        private final List<Replay> saved = new ArrayList<>();
        private final Set<String> silentlyFailing = new HashSet<>();
        private final Set<String> throwing = new HashSet<>();

        private FileSaver(File directory) {
            this.directory = directory;
        }

        @Override
        public void saveReplay(Replay replay) {
            if (throwing.contains(replay.getId())) {
                throw new IllegalStateException("Speicher voll");
            }
            saved.add(replay);
            if (silentlyFailing.contains(replay.getId())) {
                return;
            }
            try (ObjectOutputStream out = new ObjectOutputStream(new GZIPOutputStream(
                    new FileOutputStream(new File(directory, replay.getId() + ".replay"))))) {
                out.writeObject(replay.getData());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void loadReplay(String replayName, Consumer<Replay> consumer) {
            consumer.accept(null);
        }

        @Override
        public boolean replayExists(String replayName) {
            return new File(directory, replayName + ".replay").isFile();
        }

        @Override
        public void deleteReplay(String replayName) {
        }

        @Override
        public List<String> getReplays() {
            return List.of();
        }
    }

    private final Logger logger = Logger.getAnonymousLogger();
    private File directory;
    private FileSaver saver;
    private SegmentWriter writer;

    @BeforeEach
    void setUp() throws IOException {
        logger.setLevel(Level.OFF);
        Path base = Path.of(System.getProperty("buildreplay.test.tmp", System.getProperty("java.io.tmpdir")));
        Files.createDirectories(base);
        directory = Files.createTempDirectory(base, "segments").toFile();
        saver = new FileSaver(directory);
        writer = new SegmentWriter(TestReplayData::create, new ReplaySlicer(TestReplayData::create), logger);
    }

    private static RawSnapshot raw() {
        InvData inventory = new InvData();
        inventory.setMainHand(item("OAK_STAIRS"));
        ReplayData data = actions()
                .spawn(0, 0, 64, 0, "build")
                .packet(0, inventory)
                .packet(50, new MovingData(4, 65, 4, 10f, 80f))
                .packet(60, new EntityActionData(EnumWrappers.PlayerAction.START_SNEAKING))
                .packet(120, new BlockChangeData(new LocationData(5, 64, 5, "build"), item("AIR"), item("OAK_STAIRS")))
                .add(121, ActionType.CUSTOM, "Steve", new ChatData(FidelityPayload.encode(List.of(new FidelityChange(
                        new BlockKey("build", 5, 64, 5), "minecraft:air", "minecraft:oak_stairs[facing=west]")), 8)
                        .getFirst()))
                .packet(400, new MovingData(6, 65, 6, 0f, 0f))
                .toData(600, "Steve");
        return RawSnapshot.of("brraw-test", data, null);
    }

    private static ReplayData read(File file) throws IOException, ClassNotFoundException {
        try (ObjectInputStream in = new ObjectInputStream(new GZIPInputStream(new FileInputStream(file)))) {
            return (ReplayData) in.readObject();
        }
    }

    @Test
    void eachCutIsSavedAsItsOwnReplayThatAdvancedReplayCanDeserialize() throws Exception {
        List<SavedSegment> saved = writer.saveSegments(saver, directory, raw(), List.of(
                new SegmentCut("br-first", 100, 300), new SegmentCut("br-second", 350, 1_000)));

        assertEquals(2, saved.size());
        SavedSegment first = saved.getFirst();
        assertTrue(first.ok(), first.error());
        assertEquals(200, first.durationTicks());
        assertEquals("build", first.worldName());
        assertNotNull(first.sizeBytes());
        assertTrue(first.sizeBytes() > 0);
        assertEquals(250, saved.get(1).durationTicks(), "auf das Ende der Rohaufnahme begrenzt");

        ReplayData data = read(new File(directory, "br-first.replay"));
        List<ActionData> zero = data.getActions().get(0);
        assertEquals(ActionType.SPAWN, zero.getFirst().getType());
        SpawnData spawn = (SpawnData) zero.getFirst().getPacketData();
        assertEquals(STEVE, spawn.getUuid());
        assertEquals(80f, spawn.getLocation().getYaw());
        assertInstanceOf(InvData.class, zero.get(1).getPacketData());
        assertInstanceOf(EntityActionData.class, zero.get(2).getPacketData());
        assertEquals(EnumWrappers.PlayerAction.START_SNEAKING,
                ((EntityActionData) zero.get(2).getPacketData()).getAction());
        assertInstanceOf(BlockChangeData.class, data.getActions().get(20).getFirst().getPacketData());
        assertEquals(ActionType.CUSTOM, data.getActions().get(21).getFirst().getType());
        assertEquals(200, data.getDuration());
        assertEquals("Steve", data.getCreator());
        assertFalse(BlockTimeline.fromActions(data.getActions()).isEmpty());
    }

    @Test
    void replayInfoCarriesCreatorAndDurationForTheDatabaseSaver() {
        writer.saveSegments(saver, directory, raw(), List.of(new SegmentCut("br-info", 100, 300)));

        Replay replay = saver.saved.getFirst();
        assertEquals("br-info", replay.getId());
        assertEquals("br-info", replay.getReplayInfo().getID());
        assertEquals("Steve", replay.getReplayInfo().getCreator());
        assertEquals(200, replay.getReplayInfo().getDuration());
        assertNotNull(replay.getReplayInfo().getTime());
    }

    @Test
    void aSilentlyFailingFileSaverIsReportedAsNotSaved() {
        saver.silentlyFailing.add("br-lost");

        SavedSegment result = writer.saveSegments(saver, directory, raw(), List.of(new SegmentCut("br-lost", 0, 50)))
                .getFirst();

        assertFalse(result.ok());
        assertEquals("AdvancedReplay hat keine Datei geschrieben", result.error());
        assertNull(result.sizeBytes());
    }

    @Test
    void otherBackendsAreVerifiedThroughReplayExists() {
        saver.silentlyFailing.add("br-missing");

        List<SavedSegment> saved = writer.saveSegments(saver, null, raw(), List.of(
                new SegmentCut("br-present", 0, 50), new SegmentCut("br-missing", 0, 50)));

        assertTrue(saved.getFirst().ok());
        assertNull(saved.getFirst().sizeBytes(), "Größe nur beim Datei-Saver bekannt");
        assertFalse(saved.get(1).ok());
    }

    @Test
    void invalidAndRawSegmentNamesAreRejectedWithoutSaving() {
        List<SavedSegment> saved = writer.saveSegments(saver, directory, raw(), List.of(
                new SegmentCut("../evil", 0, 50), new SegmentCut("brraw-again", 0, 50)));

        assertTrue(saved.stream().noneMatch(SavedSegment::ok));
        assertTrue(saver.saved.isEmpty());
    }

    @Test
    void aFailingCutDoesNotStopTheFollowingCuts() {
        saver.throwing.add("br-broken");

        List<SavedSegment> saved = writer.saveSegments(saver, directory, raw(), List.of(
                new SegmentCut("br-broken", 0, 50), new SegmentCut("br-fine", 50, 100)));

        assertFalse(saved.getFirst().ok());
        assertTrue(saved.getFirst().error().contains("Speicher voll"));
        assertTrue(saved.get(1).ok());
    }

    @Test
    void rawRecordingsAreSavedUnchanged() throws Exception {
        RawSnapshot raw = raw();

        writer.saveRaw(saver, directory, raw);

        ReplayData data = read(new File(directory, "brraw-test.replay"));
        assertEquals(raw.actions().keySet(), data.getActions().keySet());
        assertEquals(600, data.getDuration());
        assertEquals("Steve", data.getCreator());
        assertEquals(raw.actionCount(), data.getActions().values().stream().mapToInt(List::size).sum());
    }

    @Test
    void savingARawRecordingThatAdvancedReplayDidNotWriteThrows() {
        saver.silentlyFailing.add("brraw-test");

        assertThrows(ReplayEngineException.class, () -> writer.saveRaw(saver, directory, raw()));
    }
}
