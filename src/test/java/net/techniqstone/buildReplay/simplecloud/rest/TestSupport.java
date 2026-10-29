package net.techniqstone.buildReplay.simplecloud.rest;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import net.techniqstone.buildReplay.config.PluginConfig.SimpleCloudSettings;
import net.techniqstone.buildReplay.simplecloud.SimpleCloudException;

/** Kleine handgemachte Hilfen für die REST-Tests (keine Mocking-Bibliothek). */
final class TestSupport {

    static final String NETWORK_ID = "2be2cf66-80e0-4e47-8d80-b97d6f14aaa7";
    static final String SECRET = "s3cr3t-NETWORK-password-4711";
    static final UUID PLAYER = UUID.fromString("8e167ad3-ad36-45a8-ba56-6a590f79164b");

    private TestSupport() {
    }

    /** Einstellungen mit kurzen Wartezeiten; Abwandlungen über die {@code with*}-Methoden. */
    record Settings(String baseUrl, String networkId, String secret, Duration requestTimeout, int maxRetries,
                    Duration retryBase, Duration retryMax, int threshold, Duration openDuration) {

        static Settings forUrl(String baseUrl) {
            return new Settings(baseUrl, NETWORK_ID, SECRET, Duration.ofSeconds(5), 0, Duration.ofMillis(10),
                    Duration.ofMillis(50), 100, Duration.ofSeconds(30));
        }

        Settings retries(int retries) {
            return new Settings(baseUrl, networkId, secret, requestTimeout, retries, retryBase, retryMax, threshold,
                    openDuration);
        }

        Settings delays(Duration base, Duration max) {
            return new Settings(baseUrl, networkId, secret, requestTimeout, maxRetries, base, max, threshold,
                    openDuration);
        }

        Settings timeout(Duration timeout) {
            return new Settings(baseUrl, networkId, secret, timeout, maxRetries, retryBase, retryMax, threshold,
                    openDuration);
        }

        Settings circuit(int failureThreshold, Duration open) {
            return new Settings(baseUrl, networkId, secret, requestTimeout, maxRetries, retryBase, retryMax,
                    failureThreshold, open);
        }

        Settings credentials(String id, String networkSecret) {
            return new Settings(baseUrl, id, networkSecret, requestTimeout, maxRetries, retryBase, retryMax, threshold,
                    openDuration);
        }

        SimpleCloudSettings build() {
            return new SimpleCloudSettings(baseUrl, networkId, secret, false, Duration.ofSeconds(2), requestTimeout,
                    maxRetries, retryBase, retryMax, threshold, openDuration, Duration.ofSeconds(60),
                    Duration.ofHours(6), Duration.ofSeconds(20), Duration.ofSeconds(30));
        }
    }

    /** Uhr, die nur auf Zuruf weiterläuft. */
    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        MutableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        void advance(Duration duration) {
            now.updateAndGet(current -> current.plus(duration));
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
            return now.get();
        }
    }

    /** Sammelt alle Logeinträge eines eigenen Loggers. */
    static final class CapturedLog extends Handler {
        private final List<LogRecord> records = new CopyOnWriteArrayList<>();
        private final Logger logger;

        CapturedLog() {
            this.logger = Logger.getLogger("BuildReplayTest-" + UUID.randomUUID());
            logger.setUseParentHandlers(false);
            logger.setLevel(Level.ALL);
            setLevel(Level.ALL);
            logger.addHandler(this);
        }

        Logger logger() {
            return logger;
        }

        List<LogRecord> records() {
            return List.copyOf(records);
        }

        List<LogRecord> at(Level level) {
            return records.stream().filter(r -> r.getLevel().equals(level)).toList();
        }

        /** Alle Meldungen inklusive Stacktraces als ein Text. */
        String fullText() {
            SimpleFormatter formatter = new SimpleFormatter();
            StringBuilder out = new StringBuilder();
            for (LogRecord record : records) {
                out.append(formatter.formatMessage(record)).append('\n');
                if (record.getThrown() != null) {
                    StringWriter trace = new StringWriter();
                    record.getThrown().printStackTrace(new PrintWriter(trace));
                    out.append(trace).append('\n');
                }
            }
            return out.toString();
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

    /** Wartet auf das Ergebnis (höchstens 15 s). */
    static <T> T await(CompletableFuture<T> future) throws Exception {
        try {
            return future.get(15, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    /** Wartet auf das Scheitern und liefert die {@link SimpleCloudException}. */
    static SimpleCloudException awaitFailure(CompletableFuture<?> future) throws InterruptedException,
            TimeoutException {
        try {
            Object value = future.get(15, TimeUnit.SECONDS);
            throw new AssertionError("Fehler erwartet, aber Ergebnis erhalten: " + value);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof SimpleCloudException cloud) {
                return cloud;
            }
            throw new AssertionError("SimpleCloudException erwartet, erhalten: " + e.getCause(), e.getCause());
        }
    }
}
