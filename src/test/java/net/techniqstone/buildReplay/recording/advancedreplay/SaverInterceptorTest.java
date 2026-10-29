package net.techniqstone.buildReplay.recording.advancedreplay;

import static net.techniqstone.buildReplay.recording.advancedreplay.TestReplayData.STEVE;
import static net.techniqstone.buildReplay.recording.advancedreplay.TestReplayData.actions;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import me.jumper251.replay.filesystem.saving.IReplaySaver;
import me.jumper251.replay.replaysystem.Replay;
import me.jumper251.replay.replaysystem.data.ActionData;
import me.jumper251.replay.replaysystem.data.ActionType;
import me.jumper251.replay.replaysystem.data.ReplayData;
import me.jumper251.replay.replaysystem.data.types.MovingData;
import me.jumper251.replay.utils.fetcher.Consumer;
import net.techniqstone.buildReplay.recording.advancedreplay.RawSnapshot.KnownPlayer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SaverInterceptorTest {

    /** Aufzeichnender Ersatz für AR's Saver. */
    private static final class RecordingSaver implements IReplaySaver {
        private final List<Replay> saved = new ArrayList<>();
        private final List<String> calls = new ArrayList<>();

        @Override
        public void saveReplay(Replay replay) {
            saved.add(replay);
        }

        @Override
        public void loadReplay(String replayName, Consumer<Replay> consumer) {
            calls.add("load:" + replayName);
            consumer.accept(null);
        }

        @Override
        public boolean replayExists(String replayName) {
            calls.add("exists:" + replayName);
            return true;
        }

        @Override
        public void deleteReplay(String replayName) {
            calls.add("delete:" + replayName);
        }

        @Override
        public List<String> getReplays() {
            return List.of("br-one", "brraw-two");
        }
    }

    private final Logger logger = Logger.getAnonymousLogger();
    private RecordingSaver delegate;
    private List<RawSnapshot> received;

    @BeforeEach
    void setUp() {
        logger.setLevel(Level.OFF);
        delegate = new RecordingSaver();
        received = new ArrayList<>();
    }

    private static Replay rawReplay(String name) {
        ReplayData data = actions()
                .spawn(0, 1, 64, 1, "build")
                .packet(3, new MovingData(2, 64, 2, 0, 0))
                .toData(40, "Steve");
        return new Replay(name, data);
    }

    @Test
    void rawRecordingsAreCopiedAndHandedToTheSinkInsteadOfBeingSaved() {
        SaverInterceptor interceptor = new SaverInterceptor(delegate, name -> null, received::add, logger);
        Replay replay = rawReplay("brraw-abc");

        interceptor.saveReplay(replay);

        assertTrue(delegate.saved.isEmpty());
        assertEquals(1, received.size());
        RawSnapshot snapshot = received.getFirst();
        assertEquals("brraw-abc", snapshot.rawName());
        assertEquals("Steve", snapshot.playerName());
        assertEquals(STEVE, snapshot.playerUuid());
        assertEquals("build", snapshot.worldName());
        assertEquals(40, snapshot.durationTicks());
        assertEquals(2, snapshot.actionCount());
        assertEquals("Steve", snapshot.creatorOrPlayer());
    }

    @Test
    void theSnapshotIsIndependentOfLaterChangesToAdvancedReplaysData() {
        SaverInterceptor interceptor = new SaverInterceptor(delegate, name -> null, received::add, logger);
        Replay replay = rawReplay("brraw-abc");

        interceptor.saveReplay(replay);
        replay.getData().getActions().get(3).add(new ActionData(3, ActionType.PACKET, "Steve",
                new MovingData(9, 9, 9, 0, 0)));
        replay.getData().getActions().put(7, new ArrayList<>());
        replay.getData().getActions().clear();

        RawSnapshot snapshot = received.getFirst();
        assertEquals(2, snapshot.actions().size());
        assertEquals(1, snapshot.actions().get(3).size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.actions().put(9, List.of()));
    }

    @Test
    void theKnownPlayerTakesPrecedenceOverTheSpawnAction() {
        UUID other = UUID.randomUUID();
        SaverInterceptor interceptor = new SaverInterceptor(delegate,
                name -> new KnownPlayer("Alex", other, "lobby"), received::add, logger);

        interceptor.saveReplay(rawReplay("brraw-abc"));

        RawSnapshot snapshot = received.getFirst();
        assertEquals("Alex", snapshot.playerName());
        assertEquals(other, snapshot.playerUuid());
        assertEquals("build", snapshot.worldName(), "Welt der Spawn-Aktion = Welt beim Aufnahmestart");
    }

    @Test
    void otherReplaysArePassedToAdvancedReplayUnchanged() {
        SaverInterceptor interceptor = new SaverInterceptor(delegate, name -> null, received::add, logger);
        Replay segment = rawReplay("br-segment");
        Replay manual = rawReplay("abc123");

        interceptor.saveReplay(segment);
        interceptor.saveReplay(manual);

        assertEquals(List.of(segment, manual), delegate.saved);
        assertTrue(received.isEmpty());
    }

    @Test
    void aFailingSinkFallsBackToSavingTheRawRecordingUnchanged() {
        SaverInterceptor interceptor = new SaverInterceptor(delegate, name -> null, snapshot -> {
            throw new IllegalStateException("Sink kaputt");
        }, logger);
        Replay replay = rawReplay("brraw-abc");

        interceptor.saveReplay(replay);

        assertEquals(List.of(replay), delegate.saved);
    }

    @Test
    void aDetachedInterceptorOnlyForwards() {
        SaverInterceptor interceptor = new SaverInterceptor(delegate, name -> null, received::add, logger);
        interceptor.detach();
        Replay replay = rawReplay("brraw-abc");

        interceptor.saveReplay(replay);

        assertEquals(List.of(replay), delegate.saved);
        assertTrue(received.isEmpty());
    }

    @Test
    void loadExistsDeleteAndListAreDelegated() {
        SaverInterceptor interceptor = new SaverInterceptor(delegate, name -> null, received::add, logger);
        List<Replay> loaded = new ArrayList<>();

        interceptor.loadReplay("br-one", loaded::add);
        boolean exists = interceptor.replayExists("br-one");
        interceptor.deleteReplay("br-one");

        assertTrue(exists);
        assertEquals(1, loaded.size());
        assertNull(loaded.getFirst());
        assertEquals(List.of("load:br-one", "exists:br-one", "delete:br-one"), delegate.calls);
        assertEquals(List.of("br-one", "brraw-two"), interceptor.getReplays());
        assertSame(delegate, interceptor.delegate());
    }

    @Test
    void durationFallsBackToTheLastTickWhenAdvancedReplayDidNotSetIt() {
        ReplayData data = actions().spawn(0, 0, 64, 0, "world").packet(99, new MovingData(1, 1, 1, 0, 0))
                .toData(0, null);

        RawSnapshot snapshot = RawSnapshot.of("brraw-x", data, null);

        assertEquals(100, snapshot.durationTicks());
        assertEquals("Steve", snapshot.creatorOrPlayer());
    }
}
