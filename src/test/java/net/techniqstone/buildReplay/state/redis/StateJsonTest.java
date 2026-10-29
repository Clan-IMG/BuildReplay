package net.techniqstone.buildReplay.state.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import net.techniqstone.buildReplay.state.ActivityStamp;
import net.techniqstone.buildReplay.state.CachedSession;
import net.techniqstone.buildReplay.state.HandoffRequest;
import net.techniqstone.buildReplay.state.InstanceInfo;
import net.techniqstone.buildReplay.state.PresenceInfo;
import net.techniqstone.buildReplay.state.SharedEvent;
import org.junit.jupiter.api.Test;

class StateJsonTest {

    private final TestSupport.CapturingLogger log = new TestSupport.CapturingLogger("json");
    private final StateJson json = new StateJson(log.logger());

    @Test
    void allRecordsRoundTripWithIsoInstantsAndUuidStrings() {
        CachedSession session = TestSupport.session("s1");
        PresenceInfo presence = TestSupport.presence("build-1", "inst-a", true);
        ActivityStamp activity = TestSupport.activity(true);
        InstanceInfo instance = TestSupport.instance("a");
        HandoffRequest handoff = TestSupport.handoff(9_007_199_254_740_993L);
        SharedEvent event = TestSupport.event(SharedEvent.SEGMENT_SAVED, "inst-a", "ä \"quoted\" <tag>");

        assertEquals(session, json.session(json.encode(session)));
        assertEquals(presence, json.presence(json.encode(presence)));
        assertEquals(activity, json.activity(json.encode(activity)));
        assertEquals(instance, json.instance(json.encode(instance)));
        assertEquals(handoff, json.handoff(json.encode(handoff)), "lange IDs verlieren keine Stellen");
        assertEquals(event, json.event(json.encode(event)));

        String encoded = json.encode(handoff);
        assertTrue(encoded.contains("\"createdAt\":\"2026-10-29T18:00:00Z\""), encoded);
        assertTrue(encoded.contains("\"sessionOwner\":\"" + handoff.sessionOwner() + "\""), encoded);
        assertTrue(json.encode(session).contains("\"source\":\"SIMPLECLOUD\""));
    }

    @Test
    void nullableFieldsMayBeMissing() {
        ActivityStamp withoutRelevant = TestSupport.activity(false);
        assertEquals(withoutRelevant, json.activity(json.encode(withoutRelevant)));
        CachedSession provisional = new CachedSession("prov-1", null,
                net.techniqstone.buildReplay.storage.model.SessionSource.PROVISIONAL, null, "build-1",
                TestSupport.T0);
        assertEquals(provisional, json.session(json.encode(provisional)));
    }

    @Test
    void malformedOrIncompleteEntriesAreTreatedAsAbsent() {
        assertNull(json.session(null));
        assertNull(json.session(""));
        assertNull(json.session("kein json"));
        assertNull(json.session("{}"), "Pflichtfelder fehlen");
        assertNull(json.presence("{\"serverName\":\"a\",\"instanceId\":\"b\",\"since\":\"gestern\"}"));
        assertNull(json.event("{\"type\":\"x\",\"originInstance\":\"y\"}"), "data fehlt (Map.copyOf(null))");
        assertNull(json.handoff("[1,2,3]"));
        assertTrue(log.all().contains("wird ignoriert"), "Problem wird gemeldet");
    }

    @Test
    void eventDataSurvivesTheRoundTrip() {
        SharedEvent event = new SharedEvent(SharedEvent.SESSION_CLOSED, "inst-b",
                Map.of("player", "8b0c6a59-0000-0000-0000-000000000001", "session", "abc"));
        assertEquals(event.data(), json.event(json.encode(event)).data());
    }
}
