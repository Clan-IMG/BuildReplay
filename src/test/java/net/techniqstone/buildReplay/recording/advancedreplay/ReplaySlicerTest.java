package net.techniqstone.buildReplay.recording.advancedreplay;

import static net.techniqstone.buildReplay.recording.advancedreplay.TestReplayData.SIGNATURE;
import static net.techniqstone.buildReplay.recording.advancedreplay.TestReplayData.STEVE;
import static net.techniqstone.buildReplay.recording.advancedreplay.TestReplayData.actions;
import static net.techniqstone.buildReplay.recording.advancedreplay.TestReplayData.item;
import static net.techniqstone.buildReplay.recording.advancedreplay.TestReplayData.types;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.comphenix.protocol.wrappers.EnumWrappers;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.jumper251.replay.replaysystem.data.ActionData;
import me.jumper251.replay.replaysystem.data.ActionType;
import me.jumper251.replay.replaysystem.data.types.BlockChangeData;
import me.jumper251.replay.replaysystem.data.types.ChatData;
import me.jumper251.replay.replaysystem.data.types.EntityActionData;
import me.jumper251.replay.replaysystem.data.types.EntityData;
import me.jumper251.replay.replaysystem.data.types.EntityItemData;
import me.jumper251.replay.replaysystem.data.types.EntityMovingData;
import me.jumper251.replay.replaysystem.data.types.InvData;
import me.jumper251.replay.replaysystem.data.types.LocationData;
import me.jumper251.replay.replaysystem.data.types.MetadataUpdate;
import me.jumper251.replay.replaysystem.data.types.MovingData;
import me.jumper251.replay.replaysystem.data.types.SpawnData;
import me.jumper251.replay.replaysystem.data.types.VelocityData;
import me.jumper251.replay.replaysystem.data.types.WorldChangeData;
import org.junit.jupiter.api.Test;

@SuppressWarnings("deprecation")
class ReplaySlicerTest {

    private final ReplaySlicer slicer = new ReplaySlicer(TestReplayData::create);

    @Test
    void rekeysTicksInsideTheCutAsNewActionObjects() {
        MovingData move = new MovingData(1, 64, 1, 0, 0);
        Map<Integer, List<ActionData>> source = actions()
                .spawn(0, 0, 64, 0, "world")
                .packet(100, move)
                .packet(150, new MovingData(2, 64, 2, 0, 0))
                .packet(200, new MovingData(3, 64, 3, 0, 0))
                .map();

        ReplaySlicer.Slice slice = slicer.slice(source, 300, "Steve", 100, 200);

        assertEquals(Set.of(0, 50), slice.data().getActions().keySet());
        ActionData copied = slice.data().getActions().get(0).getLast();
        assertSame(move, copied.getPacketData());
        assertNotSame(source.get(100).getFirst(), copied);
        assertEquals(0, copied.getTickIndex());
        assertEquals(50, slice.data().getActions().get(50).getFirst().getTickIndex());
        assertEquals(100, source.get(100).getFirst().getTickIndex(), "Quelle bleibt unverändert");
    }

    @Test
    void injectsSpawnWithLastPositionYawPitchAndWorldBeforeTheOriginalActions() {
        Map<Integer, List<ActionData>> source = actions()
                .spawn(0, 0, 64, 0, "world")
                .packet(10, new MovingData(5, 70, -3, 10f, 90f))
                .packet(20, new WorldChangeData(new LocationData(1, 2, 3, "build")))
                .packet(30, new MovingData(7.5, 64, 8.25, 20f, 45f))
                .packet(50, new MovingData(8, 64, 9, 21f, 46f))
                .map();

        ReplaySlicer.Slice slice = slicer.slice(source, 100, "Steve", 50, 100);

        List<ActionData> zero = slice.data().getActions().get(0);
        assertEquals(List.of(ActionType.SPAWN, ActionType.PACKET), types(zero));
        SpawnData spawn = assertInstanceOf(SpawnData.class, zero.getFirst().getPacketData());
        assertEquals("Steve", zero.getFirst().getName());
        assertEquals(STEVE, spawn.getUuid());
        assertSame(SIGNATURE, spawn.getSignature());
        LocationData location = spawn.getLocation();
        assertEquals("build", location.getWorld());
        assertEquals(7.5, location.getX());
        assertEquals(64, location.getY());
        assertEquals(8.25, location.getZ());
        assertEquals(45f, location.getYaw());
        assertEquals(20f, location.getPitch());
        assertEquals(1, slice.injectedSpawns());
        assertEquals("build", slice.worldName());
        assertInstanceOf(MovingData.class, zero.get(1).getPacketData(), "Ursprüngliche Aktion folgt dem Spawn");
    }

    @Test
    void injectsTheLastInventoryActiveSneakingAndActiveMetadata() {
        InvData first = new InvData();
        first.setMainHand(item("DIRT"));
        InvData last = new InvData();
        last.setMainHand(item("OAK_STAIRS"));
        MetadataUpdate burning = new MetadataUpdate(true, false, false, false);
        Map<Integer, List<ActionData>> source = actions()
                .spawn(0, 0, 64, 0, "world")
                .packet(0, first)
                .packet(15, last)
                .packet(20, new EntityActionData(EnumWrappers.PlayerAction.START_SNEAKING))
                .packet(25, burning)
                .packet(40, new MovingData(1, 64, 1, 0, 0))
                .map();

        List<ActionData> zero = slicer.slice(source, 100, "Steve", 40, 100).data().getActions().get(0);

        assertEquals(List.of(ActionType.SPAWN, ActionType.PACKET, ActionType.PACKET, ActionType.PACKET,
                ActionType.PACKET), types(zero));
        assertSame(last, zero.get(1).getPacketData());
        EntityActionData sneak = assertInstanceOf(EntityActionData.class, zero.get(2).getPacketData());
        assertEquals(EnumWrappers.PlayerAction.START_SNEAKING, sneak.getAction());
        assertSame(burning, zero.get(3).getPacketData());
        assertInstanceOf(MovingData.class, zero.get(4).getPacketData());
    }

    @Test
    void doesNotInjectStoppedSneakingOrInactiveMetadata() {
        Map<Integer, List<ActionData>> source = actions()
                .spawn(0, 0, 64, 0, "world")
                .packet(10, new EntityActionData(EnumWrappers.PlayerAction.START_SNEAKING))
                .packet(12, new MetadataUpdate(true, false))
                .packet(20, new EntityActionData(EnumWrappers.PlayerAction.STOP_SNEAKING))
                .packet(22, new MetadataUpdate(false, false, false, false))
                .map();

        List<ActionData> zero = slicer.slice(source, 100, "Steve", 40, 100).data().getActions().get(0);

        assertEquals(List.of(ActionType.SPAWN), types(zero));
    }

    @Test
    void doesNotInjectASecondSpawnWhenTheCutTickAlreadyContainsOne() {
        Map<Integer, List<ActionData>> source = actions()
                .spawn(0, 0, 64, 0, "world")
                .packet(10, new MovingData(5, 64, 5, 0, 0))
                .spawn(40, 9, 65, 9, "world")
                .map();

        ReplaySlicer.Slice slice = slicer.slice(source, 100, "Steve", 40, 100);

        List<ActionData> zero = slice.data().getActions().get(0);
        assertEquals(1, zero.stream().filter(a -> a.getType() == ActionType.SPAWN).count());
        SpawnData spawn = (SpawnData) zero.getFirst().getPacketData();
        assertEquals(9, spawn.getLocation().getX());
        assertEquals(0, slice.injectedSpawns());
    }

    @Test
    void doesNotInjectASpawnWhenThePlayerWasDespawnedAtTheCut() {
        Map<Integer, List<ActionData>> source = actions()
                .spawn(0, 0, 64, 0, "world")
                .add(30, ActionType.DESPAWN, "Steve", null)
                .packet(60, new BlockChangeData(new LocationData(1, 64, 1, "world"), item("AIR"), item("STONE")))
                .map();

        ReplaySlicer.Slice slice = slicer.slice(source, 100, "Steve", 50, 100);

        assertFalse(slice.data().getActions().containsKey(0));
        assertEquals(0, slice.injectedSpawns());
        assertNull(slice.worldName());
    }

    @Test
    void doesNotInjectASpawnWhenThePlayerWasDeadAtTheCut() {
        Map<Integer, List<ActionData>> source = actions()
                .spawn(0, 0, 64, 0, "world")
                .add(30, ActionType.DEATH, "Steve", null)
                .map();

        assertEquals(0, slicer.slice(source, 100, "Steve", 50, 100).injectedSpawns());
    }

    @Test
    void injectsTheRespawnLocationAfterDeathAndResetsSneaking() {
        Map<Integer, List<ActionData>> source = actions()
                .spawn(0, 0, 64, 0, "world")
                .packet(10, new EntityActionData(EnumWrappers.PlayerAction.START_SNEAKING))
                .add(30, ActionType.DEATH, "Steve", null)
                .spawn(35, 100, 70, -100, "world")
                .map();

        List<ActionData> zero = slicer.slice(source, 100, "Steve", 50, 100).data().getActions().get(0);

        assertEquals(List.of(ActionType.SPAWN), types(zero));
        SpawnData spawn = (SpawnData) zero.getFirst().getPacketData();
        assertEquals(100, spawn.getLocation().getX());
        assertEquals(-100, spawn.getLocation().getZ());
    }

    @Test
    void dropsEntityActionsWhoseEntityWasSpawnedBeforeTheCut() {
        LocationData here = new LocationData(1, 64, 1, "world");
        Map<Integer, List<ActionData>> source = actions()
                .spawn(0, 0, 64, 0, "world")
                .packet(10, new EntityData(0, 7, here, "ZOMBIE"))
                .packet(55, new EntityItemData(0, 9, item("DIRT"), here, here))
                .packet(60, new EntityMovingData(7, 2, 64, 2, 0, 0))
                .packet(61, new VelocityData(7, 0, 1, 0))
                .packet(65, new EntityItemData(1, 9, null, null, null))
                .packet(70, new EntityData(1, 7, here, "ZOMBIE"))
                .map();

        ReplaySlicer.Slice slice = slicer.slice(source, 100, "Steve", 50, 100);

        Map<Integer, List<ActionData>> out = slice.data().getActions();
        assertEquals(3, slice.droppedOrphans());
        assertFalse(out.containsKey(10));
        assertFalse(out.containsKey(11));
        assertFalse(out.containsKey(20));
        assertInstanceOf(EntityItemData.class, out.get(5).getFirst().getPacketData());
        assertInstanceOf(EntityItemData.class, out.get(15).getFirst().getPacketData());
    }

    @Test
    void keepsEntityActionsOnceTheEntityIsSpawnedAgainInsideTheCut() {
        LocationData here = new LocationData(1, 64, 1, "world");
        Map<Integer, List<ActionData>> source = actions()
                .spawn(0, 0, 64, 0, "world")
                .packet(10, new EntityData(0, 7, here, "ZOMBIE"))
                .packet(55, new EntityData(0, 7, here, "ZOMBIE"))
                .packet(60, new EntityMovingData(7, 2, 64, 2, 0, 0))
                .map();

        ReplaySlicer.Slice slice = slicer.slice(source, 100, "Steve", 50, 100);

        assertEquals(0, slice.droppedOrphans());
        assertInstanceOf(EntityMovingData.class, slice.data().getActions().get(10).getFirst().getPacketData());
    }

    @Test
    void keepsBlockChangesAndCustomActions() {
        BlockChangeData block = new BlockChangeData(new LocationData(3, 64, 3, "world"), item("AIR"),
                item("OAK_STAIRS"));
        ChatData custom = new ChatData(FidelityPayload.MARKER + "\nW\tworld\n3\t64\t3\tminecraft:air\t"
                + "minecraft:oak_stairs[facing=east]\n");
        Map<Integer, List<ActionData>> source = actions()
                .spawn(0, 0, 64, 0, "world")
                .packet(60, block)
                .add(61, ActionType.CUSTOM, "Steve", custom)
                .map();

        Map<Integer, List<ActionData>> out = slicer.slice(source, 100, "Steve", 50, 100).data().getActions();

        assertSame(block, out.get(10).getFirst().getPacketData());
        ActionData customCopy = out.get(11).getFirst();
        assertEquals(ActionType.CUSTOM, customCopy.getType());
        assertSame(custom, customCopy.getPacketData());
        assertEquals(11, customCopy.getTickIndex());
    }

    @Test
    void durationIsTheCutLengthAndCopiesTheCreator() {
        Map<Integer, List<ActionData>> source = actions().spawn(0, 0, 64, 0, "world").map();

        ReplaySlicer.Slice slice = slicer.slice(source, 1_000, "Steve", 100, 400);

        assertEquals(300, slice.durationTicks());
        assertEquals(300, slice.data().getDuration());
        assertEquals("Steve", slice.data().getCreator());
        assertTrue(slice.data().getWatchers().isEmpty());
    }

    @Test
    void durationIsClampedToTheEndOfTheRawRecording() {
        Map<Integer, List<ActionData>> source = actions()
                .spawn(0, 0, 64, 0, "world")
                .add(300, ActionType.DESPAWN, "Steve", null)
                .map();

        ReplaySlicer.Slice slice = slicer.slice(source, 300, "Steve", 250, 600);

        assertEquals(51, slice.durationTicks(), "Aktion am letzten Tick (Quit) bleibt enthalten");
        assertTrue(slice.data().getActions().containsKey(50));
    }

    @Test
    void durationCoversTheLastKeyEvenForAnInjectedSpawnOnly() {
        Map<Integer, List<ActionData>> source = actions().spawn(0, 0, 64, 0, "world").map();

        ReplaySlicer.Slice slice = slicer.slice(source, 0, "Steve", 10, 20);

        assertEquals(10, slice.durationTicks());
        assertEquals(1, slice.injectedSpawns());
    }

    @Test
    void cutAtTickZeroKeepsTheOriginalSpawnWithoutInjection() {
        Map<Integer, List<ActionData>> source = actions()
                .spawn(0, 0, 64, 0, "world")
                .packet(5, new MovingData(1, 64, 1, 0, 0))
                .map();

        ReplaySlicer.Slice slice = slicer.slice(source, 100, "Steve", 0, 50);

        assertEquals(0, slice.injectedSpawns());
        assertEquals(List.of(ActionType.SPAWN), types(slice.data().getActions().get(0)));
        assertEquals("world", slice.worldName());
        assertEquals(2, slice.actionCount());
    }

    @Test
    void rejectsEmptyOrNegativeRanges() {
        Map<Integer, List<ActionData>> source = actions().spawn(0, 0, 64, 0, "world").map();

        assertThrows(IllegalArgumentException.class, () -> slicer.slice(source, 100, "Steve", 50, 50));
        assertThrows(IllegalArgumentException.class, () -> slicer.slice(source, 100, "Steve", -1, 50));
    }

    @Test
    void entityIdsAreClassifiedAsSpawnsOrReferences() {
        LocationData here = new LocationData(1, 64, 1, "world");

        assertEquals(7, ReplaySlicer.spawnedEntityId(new EntityData(0, 7, here, "ZOMBIE")));
        assertNull(ReplaySlicer.spawnedEntityId(new EntityData(1, 7, here, "ZOMBIE")));
        assertEquals(7, ReplaySlicer.referencedEntityId(new EntityData(1, 7, here, "ZOMBIE")));
        assertEquals(9, ReplaySlicer.referencedEntityId(new EntityItemData(2, 9, null, null, null)));
        assertNull(ReplaySlicer.referencedEntityId(new MovingData(0, 0, 0, 0, 0)));
    }
}
