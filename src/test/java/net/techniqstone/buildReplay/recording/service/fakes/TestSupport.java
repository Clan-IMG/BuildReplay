package net.techniqstone.buildReplay.recording.service.fakes;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.techniqstone.buildReplay.config.EnvResolver;
import net.techniqstone.buildReplay.config.PluginConfig;
import net.techniqstone.buildReplay.server.ServerIdentity;
import org.bukkit.configuration.MemoryConfiguration;

/** Gemeinsame Testhilfen: Konfiguration aus Schlüssel/Wert-Paaren, verstellbare Uhr, stummer Logger. */
public final class TestSupport {

    public static final ServerIdentity DEV = new ServerIdentity("dev", "instance-dev-1", "dev", "-1", true);

    private TestSupport() {
    }

    /** Konfiguration mit Standardwerten, überschrieben durch {@code path, value}-Paare. */
    public static PluginConfig config(Object... pathValuePairs) {
        MemoryConfiguration root = new MemoryConfiguration();
        for (int i = 0; i < pathValuePairs.length; i += 2) {
            root.set((String) pathValuePairs[i], pathValuePairs[i + 1]);
        }
        List<String> warnings = new ArrayList<>();
        return PluginConfig.from(root, new EnvResolver(name -> Map.of(
                "SIMPLECLOUD_NETWORK_ID", "net", "SIMPLECLOUD_NETWORK_SECRET", "secret").get(name)), warnings);
    }

    /** Logger, der nichts ausgibt, aber Einträge sammelt. */
    public static Logger quietLogger(List<LogRecord> sink) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                sink.add(record);
            }

            @Override
            public void flush() {
                // nichts
            }

            @Override
            public void close() {
                // nichts
            }
        });
        return logger;
    }

    /** Verstellbare Uhr mit Millisekunden-Genauigkeit. */
    public static final class MutableClock extends Clock {
        private Instant now;

        public MutableClock(Instant start) {
            this.now = start;
        }

        public void advance(Duration duration) {
            now = now.plus(duration);
        }

        public void set(Instant instant) {
            now = instant;
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
            return now;
        }
    }
}
