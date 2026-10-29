package net.techniqstone.buildReplay.simplecloud.rest;

import static net.techniqstone.buildReplay.simplecloud.rest.TestSupport.PLAYER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.logging.Logger;
import net.techniqstone.buildReplay.simplecloud.CloudOnlinePlayer;
import net.techniqstone.buildReplay.simplecloud.CloudSession;
import net.techniqstone.buildReplay.simplecloud.CloudSessionPage;
import net.techniqstone.buildReplay.simplecloud.rest.SimpleCloudJson.MalformedResponseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SimpleCloudJsonTest {

    private final SimpleCloudJson json = new SimpleCloudJson(Logger.getAnonymousLogger());

    @ParameterizedTest
    @ValueSource(strings = {
            "2026-10-29T18:15:08.04Z", "2026-10-29T18:15:08.040Z", "2026-10-29T18:15:08.04046Z",
            "2026-10-29T15:58:03.154906Z", "2026-10-28T22:46:24.528906913Z", "2026-10-29T18:15:08Z"})
    void parsesRfc3339TimestampsWithTwoToNineFractionDigits(String text) {
        assertEquals(Instant.parse(text), SimpleCloudJson.parseInstant(text));
    }

    @Test
    void parsesTimestampsWithAnExplicitOffset() {
        assertEquals(Instant.parse("2026-10-29T17:15:08.5Z"), SimpleCloudJson.parseInstant("2026-10-29T18:15:08.5+01:00"));
    }

    @Test
    void readsAnActiveSessionWithoutLogoutKeysAndWithoutAddressHash() throws Exception {
        CloudSessionPage page = json.sessionPage("""
                {"sessions":[{"id":"59dc83e5-987b-4adc-85d7-2aaeaf7ddb53",
                  "player_id":"8e167ad3-ad36-45a8-ba56-6a590f79164b","login_time":"2026-10-29T15:58:03.154906Z"}],
                 "count":1,"total":669}
                """, PLAYER, 0);

        CloudSession session = page.sessions().getFirst();
        assertTrue(session.active());
        assertNull(session.durationSeconds());
        assertEquals(669, page.total());
    }

    @Test
    void treatsAnEmptyOrNullLogoutTimeAsActive() throws Exception {
        CloudSessionPage page = json.sessionPage("""
                {"sessions":[
                  {"id":"a","player_id":"8e167ad3-ad36-45a8-ba56-6a590f79164b","login_time":"2026-10-29T15:58:03Z","logout_time":""},
                  {"id":"b","player_id":"8e167ad3-ad36-45a8-ba56-6a590f79164b","login_time":"2026-10-29T15:58:03Z","logout_time":null}
                ],"count":2,"total":2}
                """, PLAYER, 0);

        assertTrue(page.sessions().stream().allMatch(CloudSession::active));
    }

    @Test
    void acceptsAMissingPlayerIdButSkipsSessionsOfAnotherPlayer() throws Exception {
        CloudSessionPage page = json.sessionPage("""
                {"sessions":[
                  {"id":"own","login_time":"2026-10-29T15:58:03Z"},
                  {"id":"foreign","player_id":"899c463e-0000-4000-8000-000000000002","login_time":"2026-10-29T15:58:03Z"}
                ],"count":2,"total":2}
                """, PLAYER, 0);

        assertEquals(List.of("own"), page.sessions().stream().map(CloudSession::id).toList());
        assertEquals(PLAYER, page.sessions().getFirst().playerId());
    }

    @Test
    void fallsBackToTheEntryCountWhenCountAndTotalAreMissing() throws Exception {
        CloudSessionPage page = json.sessionPage("""
                {"sessions":[{"id":"a","login_time":"2026-10-29T15:58:03Z"}]}
                """, PLAYER, 20);

        assertEquals(1, page.count());
        assertEquals(21, page.total());
    }

    @Test
    void readsAnEmptyPageBehindTheEnd() throws Exception {
        CloudSessionPage page = json.sessionPage("{\"sessions\":[],\"count\":0,\"total\":669}", PLAYER, 669);

        assertTrue(page.sessions().isEmpty());
        assertEquals(669, page.total());
    }

    @Test
    void rejectsUnreadableEnvelopes() {
        for (String body : List.of("", "  ", "null", "[]", "\"text\"", "{", "{}", "{\"sessions\":{}}", "<html>")) {
            assertThrows(MalformedResponseException.class, () -> json.sessionPage(body, PLAYER, 0), body);
        }
        assertThrows(MalformedResponseException.class, () -> json.currentSession("{\"settings\":{}}", PLAYER));
        assertThrows(MalformedResponseException.class,
                () -> json.currentSession("{\"session\":{\"id\":\"x\"}}", PLAYER));
        assertThrows(MalformedResponseException.class, () -> json.onlinePlayers("{\"count\":0}"));
    }

    @Test
    void treatsNullPlayersAsAnEmptyNetwork() throws Exception {
        assertEquals(List.of(), json.onlinePlayers("{\"players\":null,\"count\":0}"));
    }

    @Test
    void fallsBackToDisplayNameAndBlankFieldsBecomeNull() throws Exception {
        List<CloudOnlinePlayer> players = json.onlinePlayers("""
                {"players":[{"id":"8e167ad3-ad36-45a8-ba56-6a590f79164b","display_name":"Wrobel",
                  "session_id":"","connected_server_name":" ","connected_proxy_name":"proxy-1"}],"count":1}
                """);

        assertEquals(new CloudOnlinePlayer(PLAYER, "Wrobel", null, null, "proxy-1"), players.getFirst());
    }

    @Test
    void extractsTheErrorMessageOnlyFromJsonErrorBodies() {
        assertEquals("Player is not online", SimpleCloudJson.errorMessage("{\"error\":\"Player is not online\"}"));
        assertNull(SimpleCloudJson.errorMessage("404 page not found"));
        assertNull(SimpleCloudJson.errorMessage("{\"error\":42}"));
        assertNull(SimpleCloudJson.errorMessage(""));
        assertNull(SimpleCloudJson.errorMessage(null));
    }
}
