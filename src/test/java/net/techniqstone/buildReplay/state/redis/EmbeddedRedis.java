package net.techniqstone.buildReplay.state.redis;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.embedded.RedisServer;
import redis.embedded.core.ExecutableProvider;

/**
 * Eingebetteter redis-server (codemonstur embedded-redis 1.4.3, Redis 6.2) auf einem festen Port, der sich für
 * Ausfalltests stoppen und auf demselben Port neu starten lässt. Kann der Server nicht starten, wird der Test mit
 * Begründung abgebrochen (nicht als Fehler gewertet). Öffentlich für die Integrationstests.
 */
public final class EmbeddedRedis implements AutoCloseable {

    private final int port;
    private final @Nullable String password;
    private final File binaryDirectory;
    private @Nullable RedisServer server;

    private EmbeddedRedis(int port, @Nullable String password) {
        this.port = port;
        this.password = password;
        this.binaryDirectory = binaryDirectory(port);
    }

    public static EmbeddedRedis start() {
        return start(TestSupport.freePort());
    }

    static EmbeddedRedis start(int port) {
        EmbeddedRedis redis = new EmbeddedRedis(port, null);
        redis.startServer();
        return redis;
    }

    static EmbeddedRedis startWithPassword(String password) {
        EmbeddedRedis redis = new EmbeddedRedis(TestSupport.freePort(), password);
        redis.startServer();
        return redis;
    }

    public int port() {
        return port;
    }

    synchronized void startServer() {
        if (server != null) {
            return;
        }
        try {
            var builder = RedisServer.newRedisServer()
                    .executableProvider(ExecutableProvider.newJarResourceProvider(binaryDirectory))
                    .port(port)
                    .bind("127.0.0.1")
                    .setting("save \"\"")
                    .setting("appendonly no");
            if (password != null) {
                builder.setting("requirepass " + password);
            }
            RedisServer created = builder.build();
            created.start();
            server = created;
        } catch (IOException | RuntimeException e) {
            Assumptions.abort("Eingebettetes Redis kann auf diesem Rechner nicht starten: " + e);
        }
    }

    synchronized void stopServer() {
        RedisServer running = server;
        server = null;
        if (running != null) {
            try {
                running.stop();
            } catch (IOException ignored) {
                // bereits beendet
            }
        }
    }

    /** Führt Befehle über eine eigene, ungepoolte Verbindung aus (für Prüfungen im Test). */
    public <T> T withJedis(Function<Jedis, T> action) {
        DefaultJedisClientConfig.Builder config = DefaultJedisClientConfig.builder().timeoutMillis(2_000);
        if (password != null) {
            config.password(password);
        }
        try (Jedis jedis = new Jedis(new HostAndPort("127.0.0.1", port), config.build())) {
            return action.apply(jedis);
        }
    }

    boolean exists(String key) {
        return withJedis(jedis -> jedis.exists(key));
    }

    void flushAll() {
        withJedis(Jedis::flushAll);
    }

    @Override
    public void close() {
        stopServer();
    }

    private static File binaryDirectory(int port) {
        String base = System.getProperty("buildreplay.test.tmp", System.getProperty("java.io.tmpdir"));
        Path dir = Path.of(base, "embedded-redis", port + "-" + System.nanoTime());
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            Assumptions.abort("Testverzeichnis für Redis nicht anlegbar: " + e);
        }
        return dir.toFile();
    }
}
