package net.techniqstone.buildReplay.recording.advancedreplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CompatibilityProbeTest {

    @Test
    void theProbeNeverThrowsAndReportsAnIncompatibleEnvironment() {
        // Ohne laufenden Server scheitern AR's VersionUtil bzw. ProtocolLib beim Erzeugen des NPC-Pakets.
        CompatibilityProbe.Result result = CompatibilityProbe.run("1.8.19", "5.5.0-SNAPSHOT", "Leaf 1.21.11");

        assertFalse(result.compatible());
        assertNotNull(result.reason());
        assertTrue(result.reason().contains("AdvancedReplay 1.8.19"), result.reason());
        assertTrue(result.reason().contains("ProtocolLib 5.5.0-SNAPSHOT"), result.reason());
        assertTrue(result.reason().contains("Leaf 1.21.11"), result.reason());
    }

    @Test
    void theReasonNamesTheErrorAndTruncatesLongMessages() {
        String reason = CompatibilityProbe.reason("1.8.19", "5.5.0-SNAPSHOT", "Leaf 1.21.11",
                new IllegalStateException("Field index 1 is out of bounds for length 1\n" + "x".repeat(500)));

        assertTrue(reason.contains("IllegalStateException: Field index 1 is out of bounds for length 1"), reason);
        assertFalse(reason.contains("\n"));
        assertTrue(reason.length() < 500, "Begründung bleibt kurz: " + reason.length());
        assertTrue(reason.endsWith("installiert ist."));
    }

    @Test
    void unknownAndUnavailableResultsAreNeverCompatible() {
        assertFalse(CompatibilityProbe.Result.unknown().compatible());
        assertEquals("AdvancedReplay ist nicht aktiv", CompatibilityProbe.Result.unavailable().reason());
    }
}
