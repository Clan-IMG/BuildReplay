package net.techniqstone.buildReplay.storage.sql;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.techniqstone.buildReplay.config.PluginConfig;
import org.junit.jupiter.api.Assumptions;

/**
 * Eine eingebettete MariaDB (MariaDB4j) je Test-JVM. Jeder Aufruf von {@link #newSettings()} bekommt ein eigenes
 * Tabellenpräfix in derselben Testdatenbank. Lässt sich der Server auf diesem Rechner nicht starten, wird der Test
 * mit Begründung abgebrochen (als übersprungen gemeldet). {@link #stop()}/{@link #restart()} simulieren einen
 * Ausfall auf demselben Port und Datenverzeichnis. Öffentlich, damit auch die Integrationstests dieselbe Instanz
 * nutzen (ein MariaDB-Prozess je Test-JVM).
 */
public final class EmbeddedMariaDb {

    public static final String DATABASE = "buildreplay_test";
    private static final AtomicInteger PREFIXES = new AtomicInteger();
    private static DB db;
    private static int port;
    private static String failure;
    private static boolean running;

    private EmbeddedMariaDb() {
    }

    private static synchronized void ensureStarted() {
        if (db != null || failure != null) {
            return;
        }
        try {
            String tmp = System.getProperty("buildreplay.test.tmp", System.getProperty("java.io.tmpdir"));
            Path base = Path.of(tmp, "mariadb4j-sql");
            port = freePort();
            Path tmpDir = base.resolve("tmp");
            Files.createDirectories(tmpDir);
            DBConfigurationBuilder builder = DBConfigurationBuilder.newBuilder();
            builder.setPort(port);
            builder.setBaseDir(base.resolve("base").toFile());
            builder.setDataDir(base.resolve("data-" + port).toFile());
            builder.setTmpDir(tmpDir.toString());
            builder.setDeletingTemporaryBaseAndDataDirsOnShutdown(true);
            DB started = DB.newEmbeddedDB(builder.build());
            started.start();
            started.createDB(DATABASE);
            db = started;
            running = true;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    started.stop();
                } catch (Exception ignored) {
                    // JVM endet
                }
            }));
        } catch (Throwable t) {
            failure = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /** Neues Tabellenpräfix; bricht den Test ab, wenn MariaDB hier nicht läuft. */
    public static String newPrefix() {
        ensureStarted();
        Assumptions.assumeTrue(failure == null, () -> "eingebettete MariaDB nicht verfügbar: " + failure);
        return "t" + PREFIXES.incrementAndGet() + "_";
    }

    public static PluginConfig.DatabaseSettings newSettings() {
        return settings(newPrefix());
    }

    public static PluginConfig.DatabaseSettings settings(String prefix) {
        return settings(prefix, port);
    }

    /** Einstellungen für einen beliebigen Port (z. B. einen, auf dem nichts lauscht). */
    static PluginConfig.DatabaseSettings settings(String prefix, int targetPort) {
        return new PluginConfig.DatabaseSettings(true, "127.0.0.1", targetPort, DATABASE, "root", "", prefix, 2,
                Duration.ofMillis(1_000), Map.of());
    }

    static int port() {
        return port;
    }

    static synchronized void stop() throws Exception {
        if (running) {
            db.stop();
            running = false;
        }
    }

    static synchronized void restart() throws Exception {
        if (!running) {
            db.start();
            running = true;
        }
    }

    public static Connection connect() throws SQLException {
        return DriverManager.getConnection("jdbc:mariadb://127.0.0.1:" + port + "/" + DATABASE, "root", "");
    }

    /** Ein freier lokaler Port, auf dem danach nichts lauscht. */
    static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new AssertionError("kein freier Port", e);
        }
    }
}
