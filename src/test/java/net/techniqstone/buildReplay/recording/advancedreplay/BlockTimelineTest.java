package net.techniqstone.buildReplay.recording.advancedreplay;

import static net.techniqstone.buildReplay.recording.advancedreplay.TestReplayData.actions;
import static net.techniqstone.buildReplay.recording.advancedreplay.TestReplayData.item;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import me.jumper251.replay.replaysystem.data.ActionData;
import me.jumper251.replay.replaysystem.data.ActionType;
import me.jumper251.replay.replaysystem.data.types.BlockChangeData;
import me.jumper251.replay.replaysystem.data.types.ChatData;
import me.jumper251.replay.replaysystem.data.types.ItemData;
import me.jumper251.replay.replaysystem.data.types.LocationData;
import org.junit.jupiter.api.Test;

class BlockTimelineTest {

    private static final BlockKey A = new BlockKey("world", 1, 64, 1);
    private static final BlockKey B = new BlockKey("world", 2, 64, 1);
    private static final BlockKey C = new BlockKey("world", 40, 64, -20);
    private static final String NORTH = "minecraft:oak_stairs[facing=north]";
    private static final String SOUTH = "minecraft:oak_stairs[facing=south]";

    private static ChatData payload(FidelityChange... changes) {
        return new ChatData(FidelityPayload.encode(List.of(changes), 128).getFirst());
    }

    private static BlockChangeData block(BlockKey key, String before, String after) {
        return new BlockChangeData(new LocationData(key.x(), key.y(), key.z(), key.world()), item(before), item(after));
    }

    @Test
    void initialStateIsTheFirstBeforePerPosition() {
        Map<Integer, List<ActionData>> source = actions()
                .add(20, ActionType.CUSTOM, "Steve", payload(new FidelityChange(A, NORTH, SOUTH)))
                .add(10, ActionType.CUSTOM, "Steve", payload(new FidelityChange(A, "minecraft:air", NORTH)))
                .add(15, ActionType.CUSTOM, "Steve", payload(
                        new FidelityChange(B, "minecraft:grass_block[snowy=false]", "minecraft:dirt"),
                        new FidelityChange(B, "minecraft:dirt", "minecraft:air")))
                .map();

        BlockTimeline timeline = BlockTimeline.fromActions(source);

        assertEquals(Map.of(A, "minecraft:air", B, "minecraft:grass_block[snowy=false]"), timeline.initialStates());
    }

    @Test
    void exactPayloadsTakePrecedenceOverMaterialOnlyChanges() {
        Map<Integer, List<ActionData>> source = actions()
                .packet(9, block(A, "AIR", "OAK_STAIRS"))
                .add(10, ActionType.CUSTOM, "Steve", payload(new FidelityChange(A, "minecraft:air", NORTH)))
                .map();

        BlockTimeline timeline = BlockTimeline.fromActions(source);

        assertEquals(1, timeline.changesAt(A).size());
        assertEquals(NORTH, timeline.stateAt(A, 11));
    }

    @Test
    void materialOnlyPositionsUseTheDefaultStateOfTheMaterial() {
        Map<Integer, List<ActionData>> source = actions()
                .packet(10, block(C, "AIR", "WATER"))
                .map();

        BlockTimeline timeline = BlockTimeline.fromActions(source);

        assertEquals(Map.of(C, "minecraft:air"), timeline.initialStates());
        assertEquals("minecraft:water", timeline.stateAt(C, 11));
    }

    @Test
    void stateAtFollowsThePlaybackPositionInBothDirections() {
        Map<Integer, List<ActionData>> source = actions()
                .add(10, ActionType.CUSTOM, "Steve", payload(new FidelityChange(A, "minecraft:air", NORTH)))
                .add(20, ActionType.CUSTOM, "Steve", payload(new FidelityChange(A, NORTH, SOUTH)))
                .map();

        BlockTimeline timeline = BlockTimeline.fromActions(source);

        assertEquals("minecraft:air", timeline.stateAt(A, 0));
        assertEquals("minecraft:air", timeline.stateAt(A, 10), "Tick 10 ist bei currentTicks=10 noch nicht gelaufen");
        assertEquals(NORTH, timeline.stateAt(A, 11));
        assertEquals(NORTH, timeline.stateAt(A, 20));
        assertEquals(SOUTH, timeline.stateAt(A, 21));
        assertEquals(NORTH, timeline.stateAt(A, 15), "nach dem Zurückspulen auf 15");
        assertNull(timeline.stateAt(B, 15));
    }

    @Test
    void chunkIndexFindsPositionsPerWorldAndChunk() {
        Map<Integer, List<ActionData>> source = actions()
                .add(1, ActionType.CUSTOM, "Steve", payload(
                        new FidelityChange(A, "minecraft:air", NORTH),
                        new FidelityChange(C, "minecraft:air", NORTH),
                        new FidelityChange(new BlockKey("other", 1, 64, 1), "minecraft:air", NORTH)))
                .map();

        BlockTimeline timeline = BlockTimeline.fromActions(source);

        assertEquals(List.of(A), timeline.keysInChunk("world", 0, 0));
        assertEquals(List.of(C), timeline.keysInChunk("world", 2, -2));
        assertEquals(1, timeline.keysInChunk("other", 0, 0).size());
        assertTrue(timeline.keysInChunk("world", 5, 5).isEmpty());
        assertEquals(Set.of(A, C, new BlockKey("other", 1, 64, 1)), timeline.keys());
    }

    @Test
    void ordinaryChatAndOtherActionsAreIgnored() {
        Map<Integer, List<ActionData>> source = actions()
                .packet(1, new ChatData("hello"))
                .add(2, ActionType.CUSTOM, "Steve", new ChatData("fremder Hook"))
                .spawn(0, 0, 64, 0, "world")
                .map();

        assertTrue(BlockTimeline.fromActions(source).isEmpty());
    }

    @Test
    void materialStateIgnoresLegacyNumericIds() {
        assertNull(BlockTimeline.materialState(new ItemData(1, 0)));
        assertNull(BlockTimeline.materialState(null));
        assertEquals("minecraft:oak_stairs", BlockTimeline.materialState(item("OAK_STAIRS")));
    }

    @Test
    void reverseJumpRuleShowsAfterOnlyForAlreadyExecutedTicks() {
        assertTrue(BlockTimeline.showsAfter(0, 0), "Tick 0 läuft genau einmal beim Start");
        assertTrue(BlockTimeline.showsAfter(5, 6), "Abspielen: während des Aufrufs gilt currentTicks = tick + 1");
        assertTrue(BlockTimeline.showsAfter(5, 400), "Vorspulen bis 400");
        assertFalse(BlockTimeline.showsAfter(500, 400), "Zurückspulen auf 400");
        assertFalse(BlockTimeline.showsAfter(400, 400), "Ziel des Zurückspulens wird erneut abgespielt");
    }

    @Test
    void blockKeysFloorAdvancedReplayLocations() {
        BlockKey key = BlockKey.of(new LocationData(-1.0, 64.0, 15.0, "world"));

        assertEquals(new BlockKey("world", -1, 64, 15), key);
        assertEquals(-1, key.chunkX());
        assertEquals(0, key.chunkZ());
        assertTrue(key.inChunk("world", -1, 0));
        assertFalse(key.inChunk("other", -1, 0));
    }
}
