package net.techniqstone.buildReplay.storage.sql;

import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.techniqstone.buildReplay.config.PluginConfig;
import net.techniqstone.buildReplay.storage.model.SegmentStatus;
import net.techniqstone.buildReplay.storage.model.SegmentUpsert;
import net.techniqstone.buildReplay.storage.model.SessionSource;
import net.techniqstone.buildReplay.storage.model.SessionUpsert;
import net.techniqstone.buildReplay.storage.model.VisitStart;

/** Kleine Helfer für die Tests dieses Pakets. */
final class TestSupport {

    static final Instant T0 = Instant.parse("2026-10-20T12:00:00Z");
    static final SqlReplayStore.Tuning FAST = new SqlReplayStore.Tuning(Duration.ofMillis(100), Duration.ofMillis(800),
            Duration.ofSeconds(5), 3, Duration.ofMinutes(5));

    private TestSupport() {
    }

    static PluginConfig.ResilienceSettings resilience(int outboxMax) {
        return new PluginConfig.ResilienceSettings(outboxMax, Duration.ofMillis(200), Duration.ofSeconds(15), true,
                Duration.ofSeconds(45), Duration.ofSeconds(30), Duration.ofSeconds(15), Duration.ofSeconds(30));
    }

    static UUID uuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("unterbrochen", e);
        } catch (ExecutionException e) {
            throw new AssertionError("Future fehlgeschlagen: " + e.getCause(), e.getCause());
        } catch (TimeoutException e) {
            throw new AssertionError("Future nicht innerhalb von 30 s fertig", e);
        }
    }

    static void waitUntil(String what, Duration timeout, BooleanSupplier condition) {
        long end = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < end) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("unterbrochen beim Warten auf " + what);
            }
        }
        if (!condition.getAsBoolean()) {
            fail("nach " + timeout.toMillis() + " ms immer noch nicht: " + what);
        }
    }

    // ------------------------------------------------------------------ Testdaten

    static SessionUpsert cloudSession(String cloudId, UUID player, Instant login, Instant logout, Long duration) {
        return new SessionUpsert(cloudId, cloudId, player, SessionSource.SIMPLECLOUD, login, logout, duration);
    }

    static VisitStart visit(String visitKey, String sessionKey, UUID player, String server, Instant joinedAt) {
        return new VisitStart(visitKey, sessionKey, player, "Spieler", server, server + "-1", joinedAt);
    }

    static SegmentUpsert segment(String replayName, String sessionKey, UUID player, String server, Instant start,
                                 int seconds, SegmentStatus status) {
        Instant end = start.plusSeconds(seconds);
        return new SegmentUpsert(replayName, sessionKey, null, player, "Spieler", server, "world", "advancedreplay",
                "replays/" + replayName + ".data", start, end, start.plusSeconds(1), end.minusSeconds(1), seconds * 20,
                10_000, 10_000, 5, Map.of("BLOCK_PLACE", 4, "BLOCK_BREAK", 1), status, null);
    }

    // ------------------------------------------------------------------ direkte SQL-Prüfungen

    static long count(String prefix, String sqlWithP, Object... args) {
        try (Connection c = EmbeddedMariaDb.connect();
             PreparedStatement ps = c.prepareStatement(sqlWithP.replace("{p}", prefix))) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new AssertionError("SQL fehlgeschlagen: " + e.getMessage(), e);
        }
    }

    static List<String> strings(String prefix, String sqlWithP, Object... args) {
        try (Connection c = EmbeddedMariaDb.connect();
             PreparedStatement ps = c.prepareStatement(sqlWithP.replace("{p}", prefix))) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<String> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(rs.getString(1));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new AssertionError("SQL fehlgeschlagen: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ Uhr und Log

    /** Uhr, die nur auf Anweisung weiterläuft. */
    static final class MutableClock extends Clock {
        private final AtomicLong millis;

        MutableClock(Instant start) {
            this.millis = new AtomicLong(start.toEpochMilli());
        }

        void advance(Duration duration) {
            millis.addAndGet(duration.toMillis());
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }
    }

    /** Sammelt Logeinträge eines eigenen (anonymen) Loggers. */
    static final class CapturingLog extends Handler {
        private final List<LogRecord> records = new CopyOnWriteArrayList<>();
        private final Logger logger;

        CapturingLog() {
            logger = Logger.getAnonymousLogger();
            logger.setUseParentHandlers(false);
            logger.setLevel(Level.ALL);
            setLevel(Level.ALL);
            logger.addHandler(this);
        }

        Logger logger() {
            return logger;
        }

        long count(Level level, String text) {
            return records.stream()
                    .filter(r -> r.getLevel().intValue() >= level.intValue())
                    .filter(r -> r.getMessage() != null && r.getMessage().contains(text))
                    .count();
        }

        String all() {
            StringBuilder sb = new StringBuilder();
            for (LogRecord r : records) {
                sb.append(r.getLevel()).append(": ").append(r.getMessage()).append('\n');
            }
            return sb.toString();
        }

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
    }
}
