package net.techniqstone.buildReplay.activity.impl;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.techniqstone.buildReplay.activity.ActivityObserver;
import net.techniqstone.buildReplay.activity.ActivitySnapshot;
import net.techniqstone.buildReplay.activity.ActivityTracking;
import net.techniqstone.buildReplay.activity.ActivityType;
import net.techniqstone.buildReplay.config.PluginConfig;
import net.techniqstone.buildReplay.core.TaskRunner;

/** Handgemachte Test-Doubles und Einstellungs-Fabriken für die Aktivitätstests. */
final class TestSupport {

    private TestSupport() {
    }

    static final PluginConfig DEFAULTS = PluginConfig.defaults();

    static PluginConfig.ActivitySettings activity() {
        return DEFAULTS.activity();
    }

    static PluginConfig.RecordingSettings recording() {
        return DEFAULTS.recording();
    }

    static PluginConfig.ActivitySettings activity(Duration afkTimeout, Duration relevantIdle,
            Duration maxIndicatorExtension) {
        PluginConfig.ActivitySettings d = activity();
        return new PluginConfig.ActivitySettings(afkTimeout, relevantIdle, maxIndicatorExtension, d.relevantTypes(),
                d.indicatorTypes(), d.editCommandPrefixes(), d.editCommands(), d.movementThreshold(),
                d.lookThresholdDegrees());
    }

    static PluginConfig.ActivitySettings activityWithTypes(Set<ActivityType> relevant, Set<ActivityType> indicators) {
        PluginConfig.ActivitySettings d = activity();
        return new PluginConfig.ActivitySettings(d.afkTimeout(), d.relevantIdleTimeout(), d.maxIndicatorExtension(),
                relevant.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(relevant)),
                indicators.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(indicators)),
                d.editCommandPrefixes(), d.editCommands(), d.movementThreshold(), d.lookThresholdDegrees());
    }

    static PluginConfig.RecordingSettings recording(Duration preRoll, Duration postRoll, int minRelevantActions,
            Duration minSegment, Duration maxSegment, Duration mergeGap) {
        PluginConfig.RecordingSettings d = recording();
        return new PluginConfig.RecordingSettings(d.enabled(), d.worlds(), d.excludedWorlds(), d.excludedGameModes(),
                preRoll, postRoll, minRelevantActions, minSegment, maxSegment, mergeGap, d.rotationInterval(),
                d.stopWhileAfk(), d.blockFidelity());
    }

    /** Uhr, die nur auf Zuruf weiterläuft. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        void set(Instant instant) {
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

    /** Merkt sich alle Beobachter-Aufrufe in Reihenfolge als lesbare Zeichenketten. */
    static final class RecordingObserver implements ActivityObserver {
        final List<String> calls = new ArrayList<>();
        private final Instant base;

        RecordingObserver(Instant base) {
            this.base = base;
        }

        private String offset(Instant at) {
            return Duration.between(base, at).toMillis() + "ms";
        }

        @Override
        public void onRelevant(UUID player, ActivityType type, Instant at) {
            calls.add("relevant " + type + " @" + offset(at));
        }

        @Override
        public void onIndicator(UUID player, ActivityType type, Instant at) {
            calls.add("indicator " + type + " @" + offset(at));
        }

        @Override
        public void onAfk(UUID player, Instant lastInputAt) {
            calls.add("afk since @" + offset(lastInputAt));
        }

        @Override
        public void onActive(UUID player, Instant at) {
            calls.add("active @" + offset(at));
        }
    }

    /** Tracker-Double für Listener-Tests: speichert nur die gemeldeten Ereignisse. */
    static final class RecordingTracker implements ActivityTracking {
        record Recorded(UUID player, ActivityType type, Instant at) {
        }

        final List<Recorded> recorded = new ArrayList<>();

        @Override
        public void addObserver(ActivityObserver observer) {
        }

        @Override
        public void record(UUID player, ActivityType type, Instant at) {
            recorded.add(new Recorded(player, type, at));
        }

        @Override
        public void start(UUID player, Instant at) {
        }

        @Override
        public void forget(UUID player) {
        }

        @Override
        public Optional<ActivitySnapshot> snapshot(UUID player) {
            return Optional.empty();
        }

        List<ActivityType> types() {
            return recorded.stream().map(Recorded::type).toList();
        }
    }

    /** Synchroner TaskRunner: ist „nicht Hauptthread“ eingestellt, landen Hauptthread-Aufgaben in einer Warteschlange. */
    static final class FakeTaskRunner implements TaskRunner {
        boolean mainThread = true;
        final Deque<Runnable> queued = new ArrayDeque<>();

        @Override
        public void runOnMain(Runnable task) {
            if (mainThread) {
                task.run();
            } else {
                queued.add(task);
            }
        }

        @Override
        public void runOnMainLater(Runnable task, long delayTicks) {
            queued.add(task);
        }

        @Override
        public boolean isMainThread() {
            return mainThread;
        }

        @Override
        public ExecutorService io() {
            throw new UnsupportedOperationException("kein I/O in Aktivitätstests");
        }

        @Override
        public ScheduledExecutorService scheduler() {
            throw new UnsupportedOperationException("kein Scheduler in Aktivitätstests");
        }

        /** Führt die Warteschlange „auf dem Hauptthread“ aus. */
        void drain() {
            boolean previous = mainThread;
            mainThread = true;
            try {
                while (!queued.isEmpty()) {
                    queued.poll().run();
                }
            } finally {
                mainThread = previous;
            }
        }
    }

    /** Logger, der alle Einträge sammelt. */
    static final class CapturingLogger {
        final Logger logger;
        final List<LogRecord> records = Collections.synchronizedList(new ArrayList<>());

        CapturingLogger(String name) {
            logger = Logger.getAnonymousLogger();
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

        long count(Level level) {
            return records.stream().filter(r -> r.getLevel().equals(level)).count();
        }
    }
}
