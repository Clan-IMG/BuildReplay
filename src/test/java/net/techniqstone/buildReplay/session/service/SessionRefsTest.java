package net.techniqstone.buildReplay.session.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.techniqstone.buildReplay.session.NetworkSession;
import org.junit.jupiter.api.Test;

class SessionRefsTest {

    @Test
    void candidatesAreTheShortestDistinctPrefixesWithoutTrailingDash() {
        List<String> refs = SessionRefs.candidates(List.of(
                "59dc83e5-987b-4adc-85d7-2aaeaf7ddb53",
                "59dc83e5-98aa-4adc-85d7-2aaeaf7ddb53"));

        assertEquals(List.of("59dc83e5-987", "59dc83e5-98a"), refs);
    }

    @Test
    void distinctKeysKeepTheirShortReference() {
        String provisional = "prov-1234abcd-0000-0000-0000-000000000000";
        List<String> refs = SessionRefs.candidates(List.of("abcdef01-0000-0000-0000-000000000000", provisional));

        assertEquals(List.of("abcdef01", NetworkSession.shortRef(provisional)), refs);
    }

    @Test
    void onlyHexReferencesCanBeSimpleCloudIds() {
        assertTrue(SessionRefs.isCloudRef("59dc83e5"));
        assertTrue(SessionRefs.isCloudRef("59dc83e5-98"));
        assertFalse(SessionRefs.isCloudRef("p1234abc"));
        assertFalse(SessionRefs.isCloudRef("-"));
        assertFalse(SessionRefs.isCloudRef(""));
    }
}
