package net.techniqstone.buildReplay.recording.advancedreplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import net.techniqstone.buildReplay.recording.ReplayNames;
import org.junit.jupiter.api.Test;

class ReplayNamesTest {

    @Test
    void rawNamesAreValidRawAndUnique() {
        Set<String> names = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            String name = ReplayNames.newRawName();
            assertTrue(ReplayNames.isValid(name), name);
            assertTrue(ReplayNames.isRaw(name), name);
            names.add(name);
        }
        assertEquals(1_000, names.size());
    }

    @Test
    void segmentNamesAreValidAndNeverIntercepted() {
        String name = ReplayNames.newSegmentName();

        assertTrue(ReplayNames.isValid(name));
        assertFalse(ReplayNames.isRaw(name));
        assertTrue(name.startsWith(ReplayNames.SEGMENT_PREFIX));
    }

    @Test
    void generatedNamesFitAdvancedReplaysFortyCharacterIdColumn() {
        assertTrue(ReplayNames.newRawName().length() <= 40);
        assertTrue(ReplayNames.newSegmentName().length() <= 40);
    }

    @Test
    void namesAdvancedReplayCannotStoreAreRejected() {
        assertFalse(ReplayNames.isValid(null));
        assertFalse(ReplayNames.isValid(""));
        assertFalse(ReplayNames.isValid("br-with space"));
        assertFalse(ReplayNames.isValid("../../server.properties"));
        assertFalse(ReplayNames.isValid("br-ümlaut"));
        assertFalse(ReplayNames.isValid("x".repeat(41)));
        assertTrue(ReplayNames.isValid("x".repeat(40)));
    }

    @Test
    void onlyThePrefixMarksRawRecordings() {
        assertTrue(ReplayNames.isRaw("brraw-abc"));
        assertFalse(ReplayNames.isRaw("br-brraw-abc"));
        assertFalse(ReplayNames.isRaw(null));
    }
}
