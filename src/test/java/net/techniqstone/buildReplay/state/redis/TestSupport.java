package net.techniqstone.buildReplay.state.redis;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.techniqstone.buildReplay.config.PluginConfig;
import net.techniqstone.buildReplay.state.ActivityStamp;
import net.techniqstone.buildReplay.state.CachedSession;
import net.techniqstone.buildReplay.state.HandoffRequest;
import net.techniqstone.buildReplay.state.InstanceInfo;
import net.techniqstone.buildReplay.state.PresenceInfo;
import net.techniqstone.buildReplay.state.RecordingState;
import net.techniqstone.buildReplay.state.SharedEvent;
import net.techniqstone.buildReplay.storage.model.SessionSource;

/** Kleine Hilfen für die Zustandstests. */
final class TestSupport {

    static final Instant T0 = Instant.parse("2026-10-29T18:00:00Z");

    private TestSupport() {
    }

    static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("Kein freier Port", e);
        }
    }

    static void waitUntil(String what, Duration timeout, BooleanSupplier condition) {
        long end = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < end) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(25);
        }
        if (!condition.getAsBoolean()) {
            fail("Zeitüberschreitung (" + timeout.toMillis() + " ms): " + what);
        }
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("unterbrochen", e);
        }
    }

    static PluginConfig.RedisSettings settings(int port) {
        return settings(port, "", "buildreplay:");
    }

    static PluginConfig.RedisSettings settings(int port, String password, String prefix) {
        return new PluginConfig.RedisSettings(true, "127.0.0.1", port, "", password, 0, false, prefix,
                Duration.ofMillis(1_000), 4);
    }

    static PluginConfig.RedisSettings disabled() {
        return new PluginConfig.RedisSettings(false, "127.0.0.1", 6379, "", "", 0, false, "buildreplay:",
                Duration.ofMillis(1_000), 4);
    }

    // ---- Beispieldaten

    static CachedSession session(String key) {
        return new CachedSession(key, "cloud-" + key, SessionSource.SIMPLECLOUD, T0.minusSeconds(60), "lobby-1",
                T0);
    }

    static PresenceInfo presence(String server, String instance, boolean online) {
        return new PresenceInfo(server, instance, T0, online);
    }

    static RecordingState recording(String raw) {
        return new RecordingState(raw, "build-1", "inst-a", T0);
    }

    static ActivityStamp activity(boolean withRelevant) {
        return new ActivityStamp(withRelevant ? T0.minusSeconds(5) : null, T0, false, "build-1");
    }

    static InstanceInfo instance(String id) {
        return new InstanceInfo(id, "server-" + id, "1.0.0", T0.minusSeconds(3_600), T0);
    }

    static HandoffRequest handoff(long segmentId) {
        return new HandoffRequest(segmentId, "br-" + segmentId, "build-2", "build-1", UUID.randomUUID(),
                "ref-" + segmentId, T0);
    }

    static SharedEvent event(String type, String origin, String value) {
        return new SharedEvent(type, origin, Map.of("value", value));
    }

    // ---- Log-Erfassung

    /** Logger, der alle Meldungen (auch FINE) sammelt und nicht an die Konsole weitergibt. */
    static final class CapturingLogger {
        private final Logger logger;
        private final List<LogRecord> records = new CopyOnWriteArrayList<>();

        CapturingLogger(String name) {
            logger = Logger.getLogger("BuildReplayTest." + name + "." + UUID.randomUUID());
            logger.setUseParentHandlers(false);
            logger.setLevel(Level.ALL);
            logger.addHandler(new Handler() {
                @Override
                public void publish(LogRecord record) {
                    records.add(record);
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            });
        }

        Logger logger() {
            return logger;
        }

        /** Alle Meldungen inklusive Ausnahmetexte, eine pro Zeile. */
        String all() {
            StringBuilder out = new StringBuilder();
            for (LogRecord record : records) {
                out.append(record.getLevel()).append(' ').append(record.getMessage());
                Throwable error = record.getThrown();
                while (error != null) {
                    out.append(" | ").append(error);
                    error = error.getCause();
                }
                out.append('\n');
            }
            return out.toString();
        }

        long count(Level level, String fragment) {
            return records.stream()
                    .filter(r -> r.getLevel().equals(level) && r.getMessage() != null
                            && r.getMessage().contains(fragment))
                    .count();
        }
    }
}
