package net.techniqstone.buildReplay.simplecloud.rest;

import static net.techniqstone.buildReplay.simplecloud.rest.RestSimpleCloudClient.CURRENT_SESSION_PATH;
import static net.techniqstone.buildReplay.simplecloud.rest.RestSimpleCloudClient.ONLINE_PATH;
import static net.techniqstone.buildReplay.simplecloud.rest.RestSimpleCloudClient.SESSIONS_PATH;
import static net.techniqstone.buildReplay.simplecloud.rest.TestSupport.NETWORK_ID;
import static net.techniqstone.buildReplay.simplecloud.rest.TestSupport.PLAYER;
import static net.techniqstone.buildReplay.simplecloud.rest.TestSupport.SECRET;
import static net.techniqstone.buildReplay.simplecloud.rest.TestSupport.await;
import static net.techniqstone.buildReplay.simplecloud.rest.TestSupport.awaitFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import net.techniqstone.buildReplay.simplecloud.ClientHealth;
import net.techniqstone.buildReplay.simplecloud.CloudOnlinePlayer;
import net.techniqstone.buildReplay.simplecloud.CloudSession;
import net.techniqstone.buildReplay.simplecloud.CloudSessionPage;
import net.techniqstone.buildReplay.simplecloud.SimpleCloudException;
import net.techniqstone.buildReplay.simplecloud.SimpleCloudException.Reason;
import net.techniqstone.buildReplay.simplecloud.rest.FakeController.Recorded;
import net.techniqstone.buildReplay.simplecloud.rest.FakeController.Reply;
import net.techniqstone.buildReplay.simplecloud.rest.TestSupport.CapturedLog;
import net.techniqstone.buildReplay.simplecloud.rest.TestSupport.MutableClock;
import net.techniqstone.buildReplay.simplecloud.rest.TestSupport.Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RestSimpleCloudClientTest {

    /** Live-Form von {@code /v0/players/sessions}: aktive Sitzung ohne logout_time/duration_seconds, eine ohne address_hash. */
    static final String LIVE_SESSIONS = """
            {"sessions":[
             {"id":"59dc83e5-987b-4adc-85d7-2aaeaf7ddb53","network_id":"2be2cf66-80e0-4e47-8d80-b97d6f14aaa7",
              "player_id":"8e167ad3-ad36-45a8-ba56-6a590f79164b","address_hash":"1533772364","client_version":774,
              "online_mode":true,"login_time":"2026-10-29T15:58:03.154906Z"},
             {"id":"3c8a7573-8fc3-4084-a73c-b2583e618ec4","network_id":"2be2cf66-80e0-4e47-8d80-b97d6f14aaa7",
              "player_id":"8e167ad3-ad36-45a8-ba56-6a590f79164b","client_version":774,"online_mode":true,
              "login_time":"2026-10-28T22:33:30.557846Z","logout_time":"2026-10-28T22:48:01.858864Z",
              "duration_seconds":871},
             {"id":"82a167ed-0000-4000-8000-000000000001","network_id":"2be2cf66-80e0-4e47-8d80-b97d6f14aaa7",
              "player_id":"8e167ad3-ad36-45a8-ba56-6a590f79164b","address_hash":"99","client_version":773,
              "online_mode":true,"login_time":"2026-10-27T18:15:08.04Z","logout_time":"2026-10-27T18:15:09.528906913Z",
              "duration_seconds":0}
            ],"count":3,"total":669}
            """;

    static final String LIVE_CURRENT = """
            {"session":{"id":"59dc83e5-987b-4adc-85d7-2aaeaf7ddb53","network_id":"2be2cf66-80e0-4e47-8d80-b97d6f14aaa7",
              "player_id":"8e167ad3-ad36-45a8-ba56-6a590f79164b","address_hash":"1533772364","client_version":774,
              "online_mode":true,"login_time":"2026-10-29T15:58:03.154906Z"},
             "settings":{"chat_mode":"ENABLED","locale":"","main_hand":"RIGHT","view_distance":0,"chat_colors":true,
              "client_listing_allowed":true,"skin_parts":[]}}
            """;

    static final String LIVE_ONLINE = """
            {"players":[{"id":"8e167ad3-ad36-45a8-ba56-6a590f79164b","name":"WrobelXXL","display_name":"WrobelXXL",
             "first_seen":"2026-03-25T12:09:18.672495Z","last_seen":"2026-10-29T15:58:03.150569Z",
             "online_time_seconds":4236468,"playtime_seconds":4236468,"properties":{"luckperms-primary-group":"core-team"},
             "online":true,"connected_server_name":"plotwelt","connected_proxy_name":"proxy-1",
             "session_id":"59dc83e5-987b-4adc-85d7-2aaeaf7ddb53"}],"count":1}
            """;

    private static final Instant START = Instant.parse("2026-10-29T12:00:00Z");

    private FakeController controller;
    private CapturedLog log;
    private final List<RestSimpleCloudClient> clients = new ArrayList<>();

    @BeforeEach
    void startController() throws Exception {
        controller = FakeController.start();
        log = new CapturedLog();
    }

    @AfterEach
    void stopController() {
        clients.forEach(RestSimpleCloudClient::close);
        controller.close();
    }

    private Settings settings() {
        return Settings.forUrl(controller.baseUrl());
    }

    private RestSimpleCloudClient client(Settings settings) {
        return client(settings, Clock.systemUTC());
    }

    private RestSimpleCloudClient client(Settings settings, Clock clock) {
        RestSimpleCloudClient client = new RestSimpleCloudClient(settings.build(), "1.2.3", clock, log.logger());
        clients.add(client);
        return client;
    }

    @Test
    void sendsNetworkHeadersAndNeverAnAuthorizationHeader() throws Exception {
        controller.on(ONLINE_PATH, Reply.ok(LIVE_ONLINE));
        RestSimpleCloudClient client = client(settings());

        await(client.onlinePlayers());

        Recorded request = controller.requests(ONLINE_PATH).getFirst();
        assertEquals("GET", request.method());
        assertEquals(NETWORK_ID, request.header("X-Network-ID"));
        assertEquals(SECRET, request.header("X-Network-Credential"));
        assertEquals("application/json", request.header("Accept"));
        assertEquals("BuildReplay/1.2.3", request.header("User-Agent"));
        assertFalse(request.hasHeader("Authorization"), "Secret darf nie als Authorization gesendet werden");
    }

    @Test
    void parsesTheLiveSessionShapeIncludingAnActiveSessionWithoutLogoutKeys() throws Exception {
        controller.on(SESSIONS_PATH, Reply.ok(LIVE_SESSIONS));
        RestSimpleCloudClient client = client(settings());

        CloudSessionPage page = await(client.listSessions(PLAYER, 3, 0));

        assertEquals(3, page.count());
        assertEquals(669, page.total());
        assertEquals(3, page.sessions().size());

        CloudSession active = page.sessions().get(0);
        assertEquals("59dc83e5-987b-4adc-85d7-2aaeaf7ddb53", active.id());
        assertEquals(PLAYER, active.playerId());
        assertEquals(Instant.parse("2026-10-29T15:58:03.154906Z"), active.loginTime());
        assertTrue(active.active());
        assertNull(active.logoutTime());
        assertNull(active.durationSeconds());

        CloudSession withoutAddress = page.sessions().get(1);
        assertFalse(withoutAddress.active());
        assertEquals(Instant.parse("2026-10-28T22:48:01.858864Z"), withoutAddress.logoutTime());
        assertEquals(871L, withoutAddress.durationSeconds());

        CloudSession shortOne = page.sessions().get(2);
        assertEquals(Instant.parse("2026-10-27T18:15:08.040Z"), shortOne.loginTime());
        assertEquals(Instant.parse("2026-10-27T18:15:09.528906913Z"), shortOne.logoutTime());
        assertEquals(0L, shortOne.durationSeconds());
    }

    @Test
    void skipsMalformedSessionEntriesButKeepsTheRest() throws Exception {
        controller.on(SESSIONS_PATH, Reply.ok("""
                {"sessions":[
                  {"id":"a","player_id":"8e167ad3-ad36-45a8-ba56-6a590f79164b","login_time":"gestern"},
                  {"player_id":"8e167ad3-ad36-45a8-ba56-6a590f79164b","login_time":"2026-10-29T15:58:03.15Z"},
                  42,
                  {"id":"ok","player_id":"8e167ad3-ad36-45a8-ba56-6a590f79164b","login_time":"2026-10-29T15:58:03.15Z"}
                ],"count":4,"total":4}
                """));
        RestSimpleCloudClient client = client(settings());

        CloudSessionPage page = await(client.listSessions(PLAYER, 10, 0));

        assertEquals(List.of("ok"), page.sessions().stream().map(CloudSession::id).toList());
        assertEquals(4, page.count(), "count bleibt die Serverangabe, damit die Paginierung stimmt");
        assertFalse(log.at(Level.FINE).isEmpty(), "übersprungene Einträge werden auf FINE geloggt");
    }

    @Test
    void clampsLimitToOneHundredAndOffsetToZeroBeforeSending() throws Exception {
        controller.on(SESSIONS_PATH, Reply.ok("{\"sessions\":[],\"count\":0,\"total\":0}"));
        RestSimpleCloudClient client = client(settings());

        await(client.listSessions(PLAYER, 500, -3));
        await(client.listSessions(PLAYER, 0, 7));
        await(client.listSessions(PLAYER, 42, 100));

        List<String> queries = controller.requests(SESSIONS_PATH).stream().map(Recorded::query).toList();
        assertEquals(List.of(
                "player_id=" + PLAYER + "&limit=100&offset=0",
                "player_id=" + PLAYER + "&limit=1&offset=7",
                "player_id=" + PLAYER + "&limit=42&offset=100"), queries);
    }

    @Test
    void returnsTheCurrentSessionOfAnOnlinePlayer() throws Exception {
        controller.on(CURRENT_SESSION_PATH, Reply.ok(LIVE_CURRENT));
        RestSimpleCloudClient client = client(settings());

        Optional<CloudSession> session = await(client.currentSession(PLAYER));

        assertTrue(session.isPresent());
        assertEquals("59dc83e5-987b-4adc-85d7-2aaeaf7ddb53", session.get().id());
        assertTrue(session.get().active());
        assertEquals("player_id=" + PLAYER, controller.requests(CURRENT_SESSION_PATH).getFirst().query());
    }

    @Test
    void returnsAnEmptyCurrentSessionWhenThePlayerIsNotOnline() throws Exception {
        controller.on(CURRENT_SESSION_PATH, Reply.error(404, "Player is not online"));
        RestSimpleCloudClient client = client(settings().retries(3));

        Optional<CloudSession> session = await(client.currentSession(PLAYER));

        assertTrue(session.isEmpty());
        assertEquals(1, controller.count(CURRENT_SESSION_PATH));
        assertEquals(0, client.health().failures());
        assertNotNull(client.health().lastSuccess());
    }

    @Test
    void treatsAnUnknownRouteOnTheCurrentSessionAsNotFoundInsteadOfOffline() throws Exception {
        controller.on(CURRENT_SESSION_PATH, Reply.text(404, "404 page not found"));
        RestSimpleCloudClient client = client(settings());

        SimpleCloudException error = awaitFailure(client.currentSession(PLAYER));

        assertEquals(Reason.NOT_FOUND, error.reason());
        assertEquals(404, error.statusCode());
    }

    @Test
    void parsesOnlinePlayersAndSkipsMalformedEntries() throws Exception {
        controller.on(ONLINE_PATH, Reply.ok("""
                {"players":[
                  {"id":"8e167ad3-ad36-45a8-ba56-6a590f79164b","name":"WrobelXXL","connected_server_name":"plotwelt",
                   "connected_proxy_name":"proxy-1","session_id":"59dc83e5-987b-4adc-85d7-2aaeaf7ddb53","online":true},
                  {"id":"899c463e-0000-4000-8000-000000000002","name":"NullReferenz","online":true},
                  {"id":"not-a-uuid","name":"Kaputt"},
                  {"name":"OhneId"}
                ],"count":4}
                """));
        RestSimpleCloudClient client = client(settings());

        List<CloudOnlinePlayer> players = await(client.onlinePlayers());

        assertEquals(2, players.size());
        assertEquals(new CloudOnlinePlayer(PLAYER, "WrobelXXL", "59dc83e5-987b-4adc-85d7-2aaeaf7ddb53", "plotwelt",
                "proxy-1"), players.get(0));
        assertEquals(new CloudOnlinePlayer(UUID.fromString("899c463e-0000-4000-8000-000000000002"), "NullReferenz",
                null, null, null), players.get(1));
    }

    @Test
    void parsesTheLiveOnlinePlayersShapeAndAnEmptyNetwork() throws Exception {
        controller.on(ONLINE_PATH, Reply.ok(LIVE_ONLINE), Reply.ok("{\"players\":[],\"count\":0}"));
        RestSimpleCloudClient client = client(settings());

        List<CloudOnlinePlayer> live = await(client.onlinePlayers());
        List<CloudOnlinePlayer> empty = await(client.onlinePlayers());

        assertEquals(1, live.size());
        assertEquals("plotwelt", live.getFirst().serverName());
        assertEquals("proxy-1", live.getFirst().proxyName());
        assertEquals("59dc83e5-987b-4adc-85d7-2aaeaf7ddb53", live.getFirst().sessionId());
        assertTrue(empty.isEmpty());
    }

    @Test
    void failsWithInvalidResponseWhenTheEnvelopeIsUnreadable() throws Exception {
        controller.on(SESSIONS_PATH,
                Reply.ok(""),
                Reply.ok("[]"),
                Reply.ok("{}"),
                Reply.ok("{\"sessions\":5,\"count\":1,\"total\":1}"),
                new Reply(200, "<html><body>Cloudflare</body></html>", "text/html", java.util.Map.of(), null));
        controller.on(CURRENT_SESSION_PATH, Reply.ok("{\"session\":null}"));
        controller.on(ONLINE_PATH, Reply.ok("{\"count\":3}"));
        RestSimpleCloudClient client = client(settings().retries(2));

        for (int i = 0; i < 5; i++) {
            assertEquals(Reason.INVALID_RESPONSE, awaitFailure(client.listSessions(PLAYER, 10, 0)).reason());
        }
        assertEquals(Reason.INVALID_RESPONSE, awaitFailure(client.currentSession(PLAYER)).reason());
        assertEquals(Reason.INVALID_RESPONSE, awaitFailure(client.onlinePlayers()).reason());

        assertEquals(5, controller.count(SESSIONS_PATH), "unlesbare Antworten werden nicht wiederholt");
    }

    @Test
    void failsWithNotConfiguredWithoutSendingAnyRequest() throws Exception {
        RestSimpleCloudClient client = client(settings().credentials(NETWORK_ID, ""));

        assertFalse(client.isConfigured());
        assertEquals(Reason.NOT_CONFIGURED, awaitFailure(client.listSessions(PLAYER, 10, 0)).reason());
        assertEquals(Reason.NOT_CONFIGURED, awaitFailure(client.currentSession(PLAYER)).reason());
        assertEquals(Reason.NOT_CONFIGURED, awaitFailure(client.onlinePlayers()).reason());

        assertEquals(0, controller.total());
        ClientHealth health = client.health();
        assertFalse(health.configured());
        assertEquals(0, health.requests());
    }

    @Test
    void treatsAnInvalidBaseUrlAsNotConfigured() throws Exception {
        RestSimpleCloudClient client = client(Settings.forUrl("ftp://controller.example"));

        assertFalse(client.isConfigured());
        assertEquals(Reason.NOT_CONFIGURED, awaitFailure(client.onlinePlayers()).reason());
        assertFalse(log.at(Level.WARNING).isEmpty());
    }

    @Test
    void reportsRequestAndFailureCountersInHealth() throws Exception {
        controller.on(ONLINE_PATH, Reply.ok(LIVE_ONLINE));
        controller.on(SESSIONS_PATH, Reply.error(500, "Failed to get player sessions"));
        Instant now = Instant.parse("2026-10-29T18:00:00Z");
        RestSimpleCloudClient client = client(settings().retries(1), new MutableClock(now));

        await(client.onlinePlayers());
        awaitFailure(client.listSessions(PLAYER, 10, 0));

        ClientHealth health = client.health();
        assertTrue(health.configured());
        assertFalse(health.circuitOpen());
        assertEquals(3, health.requests());
        assertEquals(2, health.failures());
        assertEquals(now, health.lastSuccess());
        assertEquals(now, health.lastFailure());
        assertNotNull(health.lastError());
        assertTrue(health.lastError().contains("HTTP 500"), health.lastError());
        assertTrue(health.lastError().contains("Failed to get player sessions"), health.lastError());
    }

    @Test
    void neverExposesTheSecretInLogsErrorsOrHealth() throws Exception {
        controller.on(ONLINE_PATH, Reply.error(401, "invalid credentials"));
        controller.on(SESSIONS_PATH, Reply.error(500, "echo " + SECRET));
        controller.on(CURRENT_SESSION_PATH, Reply.text(502, "bad gateway for " + SECRET));
        RestSimpleCloudClient client = client(settings().retries(1));

        List<SimpleCloudException> errors = List.of(
                awaitFailure(client.onlinePlayers()),
                awaitFailure(client.listSessions(PLAYER, 10, 0)),
                awaitFailure(client.currentSession(PLAYER)));

        for (SimpleCloudException error : errors) {
            assertFalse(error.getMessage().contains(SECRET), error.getMessage());
        }
        assertFalse(log.fullText().contains(SECRET), "Secret im Log");
        assertFalse(String.valueOf(client.health()).contains(SECRET), "Secret im Health-Zustand");
        assertFalse(String.valueOf(client).contains(SECRET), "Secret in toString");
    }

    @Test
    void failsPendingCallsAndRejectsNewOnesAfterClose() throws Exception {
        CountDownLatch never = new CountDownLatch(1);
        controller.on(ONLINE_PATH, Reply.hang(never));
        RestSimpleCloudClient client = client(settings());

        CompletableFuture<List<CloudOnlinePlayer>> pending = client.onlinePlayers();
        assertTrue(controller.awaitRequests(ONLINE_PATH, 1, Duration.ofSeconds(5)));
        client.close();
        client.close();

        assertEquals(Reason.UNAVAILABLE, awaitFailure(pending).reason());
        assertEquals(Reason.UNAVAILABLE, awaitFailure(client.onlinePlayers()).reason());
        assertEquals(1, controller.count(ONLINE_PATH));
    }

    @Nested
    @DisplayName("SimpleCloud API returns an error")
    class SimpleCloudApiReturnsAnError {

        @ParameterizedTest(name = "HTTP {0} -> {1}")
        @CsvSource({
                "400, INVALID_REQUEST", "401, UNAUTHORIZED", "403, UNAUTHORIZED", "404, NOT_FOUND",
                "405, INVALID_REQUEST", "408, UNAVAILABLE", "422, INVALID_REQUEST", "429, RATE_LIMITED",
                "500, UNKNOWN", "501, UNKNOWN", "502, UNAVAILABLE", "503, UNAVAILABLE", "504, UNAVAILABLE"})
        void mapsTheStatusCodeToTheMatchingReason(int status, Reason expected) throws Exception {
            controller.on(SESSIONS_PATH, Reply.error(status, "kaputt " + status));
            RestSimpleCloudClient client = client(settings());

            SimpleCloudException error = awaitFailure(client.listSessions(PLAYER, 10, 0));

            assertEquals(expected, error.reason());
            assertEquals(status, error.statusCode());
            assertTrue(error.getMessage().contains("kaputt " + status), error.getMessage());
        }

        @Test
        void failsWithUnauthorizedWithoutRetryingAndLogsTheHintOnce() throws Exception {
            controller.on(ONLINE_PATH, Reply.error(401, "invalid credentials"));
            RestSimpleCloudClient client = client(settings().retries(3));

            assertEquals(Reason.UNAUTHORIZED, awaitFailure(client.onlinePlayers()).reason());
            assertEquals(Reason.UNAUTHORIZED, awaitFailure(client.onlinePlayers()).reason());

            assertEquals(2, controller.count(ONLINE_PATH), "kein Retry bei 401");
            assertEquals(1, log.at(Level.SEVERE).size(), "401 wird nur einmal als SEVERE gemeldet");
            String hint = log.at(Level.SEVERE).getFirst().getMessage();
            assertTrue(hint.contains("SIMPLECLOUD_NETWORK_ID") && hint.contains("SIMPLECLOUD_NETWORK_SECRET"), hint);
            assertTrue(log.fullText().contains("x-request-id: req-"), "x-request-id auf FINE");
        }

        @Test
        void doesNotRetryAnInvalidRequest() throws Exception {
            controller.on(SESSIONS_PATH, Reply.error(400, "limit must be at least 1"));
            RestSimpleCloudClient client = client(settings().retries(3));

            assertEquals(Reason.INVALID_REQUEST, awaitFailure(client.listSessions(PLAYER, 1, 0)).reason());
            assertEquals(1, controller.count(SESSIONS_PATH));
        }

        @Test
        void succeedsAfterTwoServerErrors() throws Exception {
            controller.on(SESSIONS_PATH,
                    Reply.error(500, "Failed to get player sessions"),
                    Reply.error(500, "Failed to get player sessions"),
                    Reply.ok(LIVE_SESSIONS));
            RestSimpleCloudClient client = client(settings().retries(3));

            CloudSessionPage page = await(client.listSessions(PLAYER, 3, 0));

            assertEquals(3, page.sessions().size());
            assertEquals(3, controller.count(SESSIONS_PATH));
            assertEquals(3, client.health().requests());
            assertEquals(2, client.health().failures());
        }

        @Test
        void honoursRetryAfterOnRateLimiting() throws Exception {
            controller.on(ONLINE_PATH, Reply.error(429, "slow down").withHeader("Retry-After", "1"), Reply.ok(LIVE_ONLINE));
            RestSimpleCloudClient client = client(settings().retries(2).delays(Duration.ofMillis(10), Duration.ofSeconds(5)));

            await(client.onlinePlayers());

            List<Recorded> requests = controller.requests(ONLINE_PATH);
            assertEquals(2, requests.size());
            long gapMillis = (requests.get(1).receivedNanos() - requests.get(0).receivedNanos()) / 1_000_000;
            assertTrue(gapMillis >= 900, "Retry-After: 1 abgewartet, war " + gapMillis + " ms");
        }

        @Test
        void capsRetryAfterAtTheMaximumDelay() throws Exception {
            controller.on(ONLINE_PATH, Reply.error(503, "maintenance").withHeader("Retry-After", "120"),
                    Reply.ok(LIVE_ONLINE));
            RestSimpleCloudClient client = client(settings().retries(1).delays(Duration.ofMillis(10), Duration.ofMillis(200)));

            long started = System.nanoTime();
            await(client.onlinePlayers());
            long tookMillis = (System.nanoTime() - started) / 1_000_000;

            assertEquals(2, controller.count(ONLINE_PATH));
            assertTrue(tookMillis < 5_000, "Retry-After durch retryMaxDelay begrenzt, dauerte " + tookMillis + " ms");
        }

        @Test
        void reportsRateLimitedWhenTheRetriesAreExhausted() throws Exception {
            controller.on(ONLINE_PATH, Reply.error(429, "slow down"));
            RestSimpleCloudClient client = client(settings().retries(2));

            assertEquals(Reason.RATE_LIMITED, awaitFailure(client.onlinePlayers()).reason());
            assertEquals(3, controller.count(ONLINE_PATH));
        }

        @Test
        void failsWithUnavailableAfterRetriesWhenTheControllerTimesOut() throws Exception {
            CountDownLatch never = new CountDownLatch(1);
            controller.on(ONLINE_PATH, Reply.hang(never));
            RestSimpleCloudClient client = client(settings().retries(2).timeout(Duration.ofMillis(300)));

            SimpleCloudException error = awaitFailure(client.onlinePlayers());

            assertEquals(Reason.UNAVAILABLE, error.reason());
            assertEquals(-1, error.statusCode());
            assertEquals(3, controller.count(ONLINE_PATH));
        }

        @Test
        void failsWithUnavailableWhenNothingListens() throws Exception {
            int port;
            try (ServerSocket socket = new ServerSocket(0)) {
                port = socket.getLocalPort();
            }
            RestSimpleCloudClient client = client(Settings.forUrl("http://127.0.0.1:" + port).retries(1));

            assertEquals(Reason.UNAVAILABLE, awaitFailure(client.onlinePlayers()).reason());
            assertEquals(2, client.health().requests());
        }
    }

    @Nested
    class CircuitBreaking {

        @Test
        void opensAfterTheThresholdAndFailsFastWithoutARequest() throws Exception {
            controller.on(ONLINE_PATH, Reply.error(503, "unavailable"));
            RestSimpleCloudClient client = client(settings().circuit(2, Duration.ofSeconds(30)), new MutableClock(START));

            assertEquals(Reason.UNAVAILABLE, awaitFailure(client.onlinePlayers()).reason());
            assertFalse(client.health().circuitOpen());
            assertEquals(Reason.UNAVAILABLE, awaitFailure(client.onlinePlayers()).reason());
            assertTrue(client.health().circuitOpen());

            SimpleCloudException fast = awaitFailure(client.onlinePlayers());
            assertEquals(Reason.CIRCUIT_OPEN, fast.reason());
            assertEquals(Reason.CIRCUIT_OPEN, awaitFailure(client.listSessions(PLAYER, 10, 0)).reason());
            assertEquals(2, controller.total(), "kein Request bei offenem Circuit");
            assertEquals(1, log.at(Level.WARNING).size(), "Öffnen wird einmal gewarnt");
        }

        @Test
        void halfOpensAfterTheOpenDurationAndClosesOnASuccessfulTrial() throws Exception {
            controller.on(ONLINE_PATH, Reply.error(503, "unavailable"));
            MutableClock clock = new MutableClock(START);
            RestSimpleCloudClient client = client(settings().circuit(1, Duration.ofSeconds(30)), clock);

            awaitFailure(client.onlinePlayers());
            assertEquals(Reason.CIRCUIT_OPEN, awaitFailure(client.onlinePlayers()).reason());

            clock.advance(Duration.ofSeconds(29));
            assertEquals(Reason.CIRCUIT_OPEN, awaitFailure(client.onlinePlayers()).reason());

            clock.advance(Duration.ofSeconds(1));
            controller.on(ONLINE_PATH, Reply.ok(LIVE_ONLINE));
            assertEquals(1, await(client.onlinePlayers()).size());
            assertFalse(client.health().circuitOpen());
            assertEquals(1, await(client.onlinePlayers()).size());
            assertTrue(log.at(Level.INFO).stream().anyMatch(r -> r.getMessage().contains("wieder erreichbar")));
        }

        @Test
        void reopensWhenTheTrialFailsAndTheTrialIsNotRetried() throws Exception {
            controller.on(ONLINE_PATH, Reply.error(503, "unavailable"));
            MutableClock clock = new MutableClock(START);
            RestSimpleCloudClient client = client(settings().retries(3).circuit(1, Duration.ofSeconds(30)), clock);

            awaitFailure(client.onlinePlayers());
            assertEquals(4, controller.count(ONLINE_PATH), "normaler Aufruf mit 3 Wiederholungen");

            clock.advance(Duration.ofSeconds(30));
            assertEquals(Reason.UNAVAILABLE, awaitFailure(client.onlinePlayers()).reason());
            assertEquals(5, controller.count(ONLINE_PATH), "Probeaufruf ohne Wiederholungen");
            assertTrue(client.health().circuitOpen());
            assertEquals(Reason.CIRCUIT_OPEN, awaitFailure(client.onlinePlayers()).reason());
            assertEquals(5, controller.count(ONLINE_PATH));
        }

        @Test
        void allowsOnlyOneTrialRequestWhileHalfOpen() throws Exception {
            controller.on(ONLINE_PATH, Reply.error(503, "unavailable"));
            MutableClock clock = new MutableClock(START);
            RestSimpleCloudClient client = client(settings().circuit(1, Duration.ofSeconds(30)), clock);
            awaitFailure(client.onlinePlayers());

            clock.advance(Duration.ofSeconds(31));
            CountDownLatch release = new CountDownLatch(1);
            controller.on(ONLINE_PATH, Reply.ok(LIVE_ONLINE).heldBy(release));
            CompletableFuture<List<CloudOnlinePlayer>> trial = client.onlinePlayers();
            assertTrue(controller.awaitRequests(ONLINE_PATH, 2, Duration.ofSeconds(5)));

            assertEquals(Reason.CIRCUIT_OPEN, awaitFailure(client.onlinePlayers()).reason());
            release.countDown();
            assertEquals(1, await(trial).size());
            assertEquals(1, await(client.onlinePlayers()).size());
            assertEquals(3, controller.count(ONLINE_PATH));
        }

        @Test
        void doesNotCountClientErrorsTowardsTheThreshold() throws Exception {
            controller.on(SESSIONS_PATH, Reply.error(404, "Player not found"));
            RestSimpleCloudClient client = client(settings().circuit(2, Duration.ofSeconds(30)), new MutableClock(START));

            for (int i = 0; i < 4; i++) {
                assertEquals(Reason.NOT_FOUND, awaitFailure(client.listSessions(PLAYER, 10, 0)).reason());
            }
            assertFalse(client.health().circuitOpen());
            assertEquals(4, controller.count(SESSIONS_PATH));
        }
    }
}
