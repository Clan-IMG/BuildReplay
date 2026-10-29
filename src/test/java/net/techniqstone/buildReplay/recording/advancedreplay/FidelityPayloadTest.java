package net.techniqstone.buildReplay.recording.advancedreplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FidelityPayloadTest {

    private static final String STAIRS = "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]";

    private static List<FidelityChange> sample() {
        return List.of(
                new FidelityChange(new BlockKey("world", 10, 64, -3), "minecraft:air", STAIRS),
                new FidelityChange(new BlockKey("world", 10, 65, -3), "minecraft:air",
                        "minecraft:oak_door[facing=east,half=upper,hinge=left,open=false,powered=false]"),
                new FidelityChange(new BlockKey("build\tarea 50%", -30_000_000, -64, 29_999_999), STAIRS,
                        "minecraft:air"),
                new FidelityChange(new BlockKey("world", 0, 0, 0), "minecraft:stone", "minecraft:smooth_stone_slab[type=top,waterlogged=true]"),
                new FidelityChange(new BlockKey("world_nether", 1, 2, 3), "minecraft:netherrack", "minecraft:fire[age=0,east=false,north=false,south=false,up=false,west=false]"));
    }

    @Test
    void encodeAndDecodeRoundTripKeepsOrderWorldsAndExactStates() {
        List<FidelityChange> changes = sample();

        List<String> messages = FidelityPayload.encode(changes, 128);

        assertEquals(1, messages.size());
        assertEquals(changes, FidelityPayload.decode(messages.getFirst()));
    }

    @Test
    void encodingSplitsIntoMessagesOfTheMaximumSize() {
        List<FidelityChange> changes = sample();

        List<String> messages = FidelityPayload.encode(changes, 2);

        assertEquals(3, messages.size());
        List<FidelityChange> decoded = new ArrayList<>();
        messages.forEach(message -> decoded.addAll(FidelityPayload.decode(message)));
        assertEquals(changes, decoded);
    }

    @Test
    void everyMessageStartsWithTheMarkerAndIsRecognised() {
        for (String message : FidelityPayload.encode(sample(), 1)) {
            assertTrue(message.startsWith(FidelityPayload.MARKER + "\n"));
            assertTrue(FidelityPayload.isPayload(message));
        }
    }

    @Test
    void ordinaryChatAndNullAreNoPayload() {
        assertFalse(FidelityPayload.isPayload(null));
        assertFalse(FidelityPayload.isPayload("hello world"));
        assertFalse(FidelityPayload.isPayload(FidelityPayload.MARKER));
        assertEquals(List.of(), FidelityPayload.decode("<Steve> BRFID1"));
        assertEquals(List.of(), FidelityPayload.decode(null));
    }

    @Test
    void decodingSkipsMalformedLinesAndEntriesBeforeAWorld() {
        String message = FidelityPayload.MARKER + "\n"
                + "1\t2\t3\tminecraft:air\tminecraft:stone\n"
                + "W\tworld\n"
                + "x\t2\t3\tminecraft:air\tminecraft:stone\n"
                + "1\t2\tminecraft:air\tminecraft:stone\n"
                + "1\t2\t3\t\tminecraft:stone\n"
                + "4\t5\t6\tminecraft:air\tminecraft:stone\n";

        assertEquals(List.of(new FidelityChange(new BlockKey("world", 4, 5, 6), "minecraft:air", "minecraft:stone")),
                FidelityPayload.decode(message));
    }

    @Test
    void emptyInputEncodesToNoMessages() {
        assertEquals(List.of(), FidelityPayload.encode(List.of(), 10));
    }

    @Test
    void escapingRoundTripsSeparatorsAndPercentSigns() {
        String value = "a%b\tc\nd\re%25";

        String escaped = FidelityPayload.escape(value);

        assertFalse(escaped.contains("\t") || escaped.contains("\n") || escaped.contains("\r"));
        assertEquals(value, FidelityPayload.unescape(escaped));
        assertEquals("plain", FidelityPayload.escape("plain"));
    }
}
