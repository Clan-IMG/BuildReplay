package net.techniqstone.buildReplay.core;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/** {@link TaskRunner} auf Basis des Bukkit-Schedulers und eigener, begrenzter Thread-Pools. */
public final class BukkitTaskRunner implements TaskRunner {

    private final Plugin plugin;
    private final Logger logger;
    private final ThreadPoolExecutor io;
    private final ScheduledExecutorService scheduler;

    public BukkitTaskRunner(Plugin plugin, int ioThreads, int ioQueueCapacity) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        int threads = Math.max(2, ioThreads);
        this.io = new ThreadPoolExecutor(threads, threads, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(64, ioQueueCapacity)), namedFactory("BuildReplay-IO"),
                (task, executor) -> {
                    throw new RejectedExecutionException("BuildReplay I/O-Warteschlange ist voll");
                });
        this.io.allowCoreThreadTimeOut(true);
        this.scheduler = Executors.newScheduledThreadPool(2, namedFactory("BuildReplay-Scheduler"));
    }

    @Override
    public void runOnMain(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            runSafely(task);
            return;
        }
        if (!plugin.isEnabled()) {
            logger.fine("Hauptthread-Aufgabe verworfen: Plugin ist deaktiviert");
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> runSafely(task));
    }

    @Override
    public void runOnMainLater(Runnable task, long delayTicks) {
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> runSafely(task), Math.max(1L, delayTicks));
    }

    @Override
    public boolean isMainThread() {
        return Bukkit.isPrimaryThread();
    }

    @Override
    public ExecutorService io() {
        return io;
    }

    @Override
    public ScheduledExecutorService scheduler() {
        return scheduler;
    }

    /** Fährt die Pools herunter und wartet höchstens {@code waitMillis} auf laufende Arbeit. */
    public void shutdown(long waitMillis) {
        scheduler.shutdownNow();
        io.shutdown();
        try {
            if (!io.awaitTermination(Math.max(0, waitMillis), TimeUnit.MILLISECONDS)) {
                logger.warning("BuildReplay I/O-Pool nach " + waitMillis + " ms nicht leer - "
                        + io.getQueue().size() + " Aufgaben werden abgebrochen");
                io.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            io.shutdownNow();
        }
    }

    public int queuedIoTasks() {
        return io.getQueue().size();
    }

    private void runSafely(Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            logger.log(Level.SEVERE, "Fehler in einer BuildReplay-Hauptthread-Aufgabe", t);
        }
    }

    private static ThreadFactory namedFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
