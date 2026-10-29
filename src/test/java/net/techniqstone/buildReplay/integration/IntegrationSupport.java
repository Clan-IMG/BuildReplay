package net.techniqstone.buildReplay.integration;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.techniqstone.buildReplay.config.EnvResolver;
import net.techniqstone.buildReplay.config.PluginConfig;
import net.techniqstone.buildReplay.core.TaskRunner;
import net.techniqstone.buildReplay.simplecloud.ClientHealth;
import net.techniqstone.buildReplay.simplecloud.CloudOnlinePlayer;
import net.techniqstone.buildReplay.simplecloud.CloudSession;
import net.techniqstone.buildReplay.simplecloud.CloudSessionPage;
import net.techniqstone.buildReplay.simplecloud.SimpleCloudClient;
import org.bukkit.configuration.MemoryConfiguration;

/** Gemeinsame Bausteine der Integrationstests (echte Module, nur Server und SimpleCloud sind nachgebildet). */
final class IntegrationSupport {

    private static final AtomicInteger REDIS_PREFIXES = new AtomicInteger();

    private IntegrationSupport() {
    }

    /** Standardkonfiguration mit einzelnen Überschreibungen ({@code "pfad", wert, ...}), ohne Umgebungsvariablen. */
    static PluginConfig config(Object... pathValuePairs) {
        MemoryConfiguration yaml = new MemoryConfiguration();
        for (int i = 0; i < pathValuePairs.length; i += 2) {
            yaml.set((String) pathValuePairs[i], pathValuePairs[i + 1]);
        }
        return PluginConfig.from(yaml, new EnvResolver(name -> null), new ArrayList<>());
    }

    /** Redis-Einstellungen für den eingebetteten Server; eigenes Schlüsselpräfix je Aufruf (Tests teilen sich Redis). */
    static PluginConfig.RedisSettings redis(int port) {
        return new PluginConfig.RedisSettings(true, "127.0.0.1", port, "", "", 0, false,
                "brit" + REDIS_PREFIXES.incrementAndGet() + "-" + System.nanoTime() + ":", Duration.ofSeconds(2), 4);
    }

    /** Logger ohne Konsolenausgabe, der WARNING und SEVERE für Prüfungen sammelt. */
    static final class QuietLog {

        private final List<LogRecord> problems = Collections.synchronizedList(new ArrayList<>());
        private final Logger logger;

        QuietLog(String name) {
            logger = Logger.getLogger("BuildReplayIT." + name + "." + System.nanoTime());
            logger.setUseParentHandlers(false);
            logger.setLevel(Level.ALL);
            logger.addHandler(new Handler() {
                @Override
                public void publish(LogRecord record) {
                    if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                        problems.add(record);
                    }
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

        List<String> problems() {
            synchronized (problems) {
                return problems.stream().map(record -> record.getLevel() + " " + record.getMessage()).toList();
            }
        }
    }

    /**
     * {@link TaskRunner} mit echtem "Hauptthread" (ein Einzel-Thread) und echten Pools - so wie auf dem Server
     * blockiert nur der I/O-Pool.
     */
    static final class ServerThreads implements TaskRunner, AutoCloseable {

        private final ExecutorService main;
        private final ExecutorService io = Executors.newFixedThreadPool(4, daemon("it-io"));
        private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, daemon("it-scheduler"));
        private volatile Thread mainThread;

        ServerThreads(String server) {
            main = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "it-main-" + server);
                thread.setDaemon(true);
                mainThread = thread;
                return thread;
            });
        }

        /** Führt {@code action} auf dem Hauptthread aus und wartet auf das Ergebnis. */
        <T> T onMain(Callable<T> action) throws Exception {
            if (isMainThread()) {
                return action.call();
            }
            return main.submit(action).get(10, TimeUnit.SECONDS);
        }

        void onMain(Runnable action) throws Exception {
            onMain(() -> {
                action.run();
                return null;
            });
        }

        @Override
        public void runOnMain(Runnable task) {
            if (isMainThread()) {
                task.run();
            } else {
                main.execute(task);
            }
        }

        @Override
        public void runOnMainLater(Runnable task, long delayTicks) {
            scheduler.schedule(() -> main.execute(task), Math.max(1, delayTicks) * 50L, TimeUnit.MILLISECONDS);
        }

        @Override
        public boolean isMainThread() {
            return Thread.currentThread() == mainThread;
        }

        @Override
        public ExecutorService io() {
            return io;
        }

        @Override
        public ScheduledExecutorService scheduler() {
            return scheduler;
        }

        @Override
        public void close() throws InterruptedException {
            scheduler.shutdownNow();
            io.shutdown();
            io.awaitTermination(5, TimeUnit.SECONDS);
            main.shutdown();
            main.awaitTermination(5, TimeUnit.SECONDS);
        }

        private static java.util.concurrent.ThreadFactory daemon(String prefix) {
            AtomicInteger counter = new AtomicInteger();
            return runnable -> {
                Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            };
        }
    }

    /**
     * SimpleCloud-Controller im Speicher, den sich alle "Server" eines Tests teilen: aktuelle Sitzung je Spieler
     * und deren Historie (neueste zuerst, wie die REST-API).
     */
    static final class NetworkCloud implements SimpleCloudClient {

        private final Map<UUID, List<CloudSession>> history = new ConcurrentHashMap<>();
        private final AtomicLong requests = new AtomicLong();
        private final boolean configured;

        NetworkCloud(boolean configured) {
            this.configured = configured;
        }

        /** Proxy-Login: neue aktive Sitzung. */
        CloudSession login(UUID player, Instant at) {
            CloudSession session = new CloudSession(UUID.randomUUID().toString(), player, at, null, null);
            history.computeIfAbsent(player, key -> Collections.synchronizedList(new ArrayList<>())).addFirst(session);
            return session;
        }

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public CompletableFuture<CloudSessionPage> listSessions(UUID playerId, int limit, int offset) {
            requests.incrementAndGet();
            List<CloudSession> sessions = List.copyOf(history.getOrDefault(playerId, List.of()));
            int from = Math.min(offset, sessions.size());
            int to = Math.min(sessions.size(), from + Math.max(1, limit));
            return CompletableFuture.completedFuture(new CloudSessionPage(sessions.subList(from, to), to - from,
                    sessions.size()));
        }

        @Override
        public CompletableFuture<Optional<CloudSession>> currentSession(UUID playerId) {
            requests.incrementAndGet();
            return CompletableFuture.completedFuture(List.copyOf(history.getOrDefault(playerId, List.of())).stream()
                    .filter(CloudSession::active)
                    .findFirst());
        }

        @Override
        public CompletableFuture<List<CloudOnlinePlayer>> onlinePlayers() {
            requests.incrementAndGet();
            List<CloudOnlinePlayer> online = new ArrayList<>();
            history.forEach((player, sessions) -> List.copyOf(sessions).stream().filter(CloudSession::active).findFirst()
                    .ifPresent(session -> online.add(new CloudOnlinePlayer(player, player.toString().substring(0, 8),
                            session.id(), null, null))));
            return CompletableFuture.completedFuture(online);
        }

        @Override
        public ClientHealth health() {
            return new ClientHealth(configured, false, null, null, null, requests.get(), 0);
        }

        @Override
        public void close() {
            // nichts zu schließen
        }
    }
}
