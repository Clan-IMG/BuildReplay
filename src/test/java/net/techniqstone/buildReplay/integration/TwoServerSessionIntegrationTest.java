package net.techniqstone.buildReplay.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.techniqstone.buildReplay.config.PluginConfig;
import net.techniqstone.buildReplay.integration.IntegrationSupport.NetworkCloud;
import net.techniqstone.buildReplay.integration.IntegrationSupport.QuietLog;
import net.techniqstone.buildReplay.integration.IntegrationSupport.ServerThreads;
import net.techniqstone.buildReplay.server.ServerIdentity;
import net.techniqstone.buildReplay.session.NetworkSession;
import net.techniqstone.buildReplay.session.service.SessionService;
import net.techniqstone.buildReplay.simplecloud.CloudSession;
import net.techniqstone.buildReplay.simplecloud.SimpleCloudClient;
import net.techniqstone.buildReplay.state.SharedState;
import net.techniqstone.buildReplay.state.redis.EmbeddedRedis;
import net.techniqstone.buildReplay.state.redis.SharedStates;
import net.techniqstone.buildReplay.storage.model.SessionSource;
import net.techniqstone.buildReplay.storage.model.VisitEndReason;
import net.techniqstone.buildReplay.storage.sql.EmbeddedMariaDb;
import net.techniqstone.buildReplay.storage.sql.SqlReplayStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Zwei Server, jeder mit eigenem Sitzungsdienst, eigenem {@link SqlReplayStore} und eigener Redis-Anbindung, teilen
 * sich eine echte MariaDB (MariaDB4j) und ein echtes Redis (embedded-redis) - wie zwei Instanzen im Netzwerk. Ein
 * Serverwechsel darf die Netzwerksitzung nicht ändern: genau eine Sitzungszeile, zwei Aufenthalte.
 */
class TwoServerSessionIntegrationTest {

    private static final UUID STEVE = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");
    private static final Duration WAIT = Duration.ofSeconds(20);

    private static EmbeddedRedis redis;

    @TempDir
    Path dataFolders;

    private final Clock clock = Clock.systemUTC();
    private final List<AutoCloseable> resources = new ArrayList<>();
    private final List<QuietLog> logs = new ArrayList<>();

    /** Ein BuildReplay-Server mit den echten Modulen für Sitzungen, Speicher und geteilten Zustand. */
    private record Server(String name, ServerThreads threads, SqlReplayStore store, SharedState shared,
                          SessionService sessions) {
    }

    @BeforeAll
    static void startRedis() {
        redis = EmbeddedRedis.start();
    }

    @AfterAll
    static void stopRedis() {
        if (redis != null) {
            redis.close();
        }
    }

    @AfterEach
    void closeServers() throws Exception {
        for (int i = resources.size() - 1; i >= 0; i--) {
            resources.get(i).close();
        }
    }

    private Server server(String name, PluginConfig config, PluginConfig.DatabaseSettings database,
                          PluginConfig.RedisSettings redisSettings, SimpleCloudClient cloud) throws Exception {
        QuietLog log = new QuietLog(name);
        logs.add(log);
        ServerIdentity identity = new ServerIdentity(name, "instance-" + name + "-" + UUID.randomUUID(), name, "-1",
                true);
        ServerThreads threads = new ServerThreads(name);
        resources.add(threads);
        Path data = Files.createDirectories(dataFolders.resolve(name));
        SqlReplayStore store = new SqlReplayStore(database, config.resilience(), data, clock, log.logger());
        resources.add(store);
        store.start();
        SharedState shared = SharedStates.create(redisSettings, identity.instanceId(), clock, log.logger());
        resources.add(shared);
        SessionService sessions = new SessionService(config, identity, threads, clock, cloud, store, shared,
                log.logger());
        resources.add(sessions);
        return new Server(name, threads, store, shared, sessions);
    }

    private static NetworkSession join(Server server, Instant at) throws Exception {
        server.threads().onMain(() -> server.sessions().handleJoin(STEVE, "Steve", at));
        return server.sessions().resolve(STEVE).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static void quit(Server server, Instant at) throws Exception {
        server.threads().onMain(() -> server.sessions().handleQuit(STEVE, "Steve", at, VisitEndReason.QUIT));
    }

    private static void flush(Server... servers) throws Exception {
        for (Server server : servers) {
            server.store().flush().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            assertTrue(server.store().health().connected(), "MariaDB verbunden: " + server.name());
            assertEquals(0, server.store().health().queuedWrites(), "alles geschrieben: " + server.name());
        }
    }

    /** Wartet, bis der Austritt im geteilten Redis-Zustand angekommen ist (Präsenz offline). */
    private static void awaitLeft(Server observer) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (observer.shared().presence(STEVE).map(presence -> !presence.online()).orElse(false)) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Austritt kam nicht in Redis an");
    }

    private record VisitResult(String server, Instant leftAt, String endReason) {
    }

    private static List<String> sessionKeys(String prefix) throws Exception {
        List<String> keys = new ArrayList<>();
        try (Connection connection = EmbeddedMariaDb.connect();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT session_key FROM " + prefix + "sessions ORDER BY id");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                keys.add(rows.getString(1));
            }
        }
        return keys;
    }

    private static String sourceOf(String prefix, String sessionKey) throws Exception {
        try (Connection connection = EmbeddedMariaDb.connect();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT source, status FROM " + prefix + "sessions WHERE session_key = ?")) {
            statement.setString(1, sessionKey);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getString(1) + "/" + rows.getString(2);
            }
        }
    }

    private static List<VisitResult> visits(String prefix, String sessionKey) throws Exception {
        List<VisitResult> visits = new ArrayList<>();
        try (Connection connection = EmbeddedMariaDb.connect();
             PreparedStatement statement = connection.prepareStatement("SELECT v.server_name, v.left_at, v.end_reason"
                     + " FROM " + prefix + "server_visits v JOIN " + prefix + "sessions s ON s.id = v.session_id"
                     + " WHERE s.session_key = ? ORDER BY v.joined_at")) {
            statement.setString(1, sessionKey);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    java.sql.Timestamp left = rows.getTimestamp(2);
                    visits.add(new VisitResult(rows.getString(1), left == null ? null : left.toInstant(),
                            rows.getString(3)));
                }
            }
        }
        return visits;
    }

    private static int count(String prefix, String table) throws Exception {
        try (Connection connection = EmbeddedMariaDb.connect();
             PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM " + prefix + table);
             ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private void assertNoProblemsLogged() {
        for (QuietLog log : logs) {
            assertTrue(log.problems().isEmpty(), log.problems().toString());
        }
    }

    @Test
    void serverSwitchKeepsTheSimpleCloudSessionWithExactlyOneRowAndTwoVisits() throws Exception {
        PluginConfig config = IntegrationSupport.config();
        PluginConfig.DatabaseSettings database = EmbeddedMariaDb.newSettings();
        PluginConfig.RedisSettings redisSettings = IntegrationSupport.redis(redis.port());
        NetworkCloud cloud = new NetworkCloud(true);
        CloudSession login = cloud.login(STEVE, clock.instant().minusSeconds(3));
        Server build = server("build", config, database, redisSettings, cloud);
        Server dev = server("dev", config, database, redisSettings, cloud);

        NetworkSession onBuild = join(build, clock.instant());
        quit(build, clock.instant());
        NetworkSession onDev = join(dev, clock.instant().plusMillis(300));
        flush(build, dev);

        assertEquals(login.id(), onBuild.sessionKey(), "SimpleCloud-Session-ID ist der Sitzungsschlüssel");
        assertEquals(onBuild.sessionKey(), onDev.sessionKey(), "Serverwechsel behält die Sitzung");
        assertEquals(SessionSource.SIMPLECLOUD, onDev.source());

        String prefix = database.tablePrefix();
        assertEquals(List.of(login.id()), sessionKeys(prefix), "genau eine Sitzungszeile");
        assertEquals("SIMPLECLOUD/ACTIVE", sourceOf(prefix, login.id()));
        List<VisitResult> visits = visits(prefix, login.id());
        assertEquals(2, visits.size(), visits.toString());
        assertEquals("build", visits.get(0).server());
        assertNotNull(visits.get(0).leftAt(), "Aufenthalt auf build ist beendet");
        assertEquals("QUIT", visits.get(0).endReason());
        assertEquals("dev", visits.get(1).server());
        assertNull(visits.get(1).leftAt(), "Aufenthalt auf dev läuft noch");
        assertEquals(1, count(prefix, "players"));
        assertTrue(build.shared().isDistributed() && dev.shared().isDistributed(), "beide Server nutzen Redis");
        assertNoProblemsLogged();
    }

    @Test
    void serverSwitchWithoutSimpleCloudSharesOneProvisionalSessionThroughRedis() throws Exception {
        PluginConfig config = IntegrationSupport.config();
        PluginConfig.DatabaseSettings database = EmbeddedMariaDb.newSettings();
        PluginConfig.RedisSettings redisSettings = IntegrationSupport.redis(redis.port());
        NetworkCloud unreachable = new NetworkCloud(false);
        Server build = server("build", config, database, redisSettings, unreachable);
        Server dev = server("dev", config, database, redisSettings, unreachable);

        NetworkSession onBuild = join(build, clock.instant());
        quit(build, clock.instant());
        awaitLeft(dev);
        NetworkSession onDev = join(dev, clock.instant().plusMillis(300));
        flush(build, dev);

        assertTrue(onBuild.provisional(), "ohne SimpleCloud entsteht eine vorläufige Sitzung");
        assertTrue(onBuild.sessionKey().startsWith(NetworkSession.PROVISIONAL_PREFIX), onBuild.sessionKey());
        assertEquals(onBuild.sessionKey(), onDev.sessionKey(),
                "der zweite Server übernimmt die Sitzung aus Redis (Wechsel innerhalb der Karenzzeit)");

        String prefix = database.tablePrefix();
        assertEquals(List.of(onBuild.sessionKey()), sessionKeys(prefix), "genau eine Sitzungszeile");
        assertEquals("PROVISIONAL/ACTIVE", sourceOf(prefix, onBuild.sessionKey()));
        List<VisitResult> visits = visits(prefix, onBuild.sessionKey());
        assertEquals(List.of("build", "dev"), visits.stream().map(VisitResult::server).toList());
        assertNotNull(visits.get(0).leftAt());
        assertNull(visits.get(1).leftAt());
        assertNoProblemsLogged();
    }
}
