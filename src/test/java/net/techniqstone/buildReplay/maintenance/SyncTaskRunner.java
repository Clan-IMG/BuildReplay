package net.techniqstone.buildReplay.maintenance;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.techniqstone.buildReplay.core.TaskRunner;

/**
 * Synchroner {@link TaskRunner} für Tests: Hauptthread- und I/O-Aufgaben laufen sofort im aufrufenden Thread. Ein
 * Thread-lokales Kennzeichen merkt sich, ob gerade eine Hauptthread-Aufgabe läuft, damit Fakes prüfen können, ob
 * Bukkit-/AR-Aufrufe auf dem "Hauptthread" und I/O auf dem "I/O-Pool" stattfinden. Geplante Aufgaben werden nur
 * gesammelt ({@link ManualScheduler}) und vom Test selbst ausgeführt.
 */
final class SyncTaskRunner implements TaskRunner {

    private static final ThreadLocal<Boolean> MAIN = ThreadLocal.withInitial(() -> false);

    final ManualScheduler scheduler = new ManualScheduler();
    private final ExecutorService io = new AbstractExecutorService() {
        private volatile boolean shutdown;

        @Override
        public void execute(Runnable command) {
            boolean previous = MAIN.get();
            MAIN.set(false);
            try {
                command.run();
            } finally {
                MAIN.set(previous);
            }
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    };

    static boolean onMainThread() {
        return MAIN.get();
    }

    @Override
    public void runOnMain(Runnable task) {
        boolean previous = MAIN.get();
        MAIN.set(true);
        try {
            task.run();
        } finally {
            MAIN.set(previous);
        }
    }

    @Override
    public void runOnMainLater(Runnable task, long delayTicks) {
        runOnMain(task);
    }

    @Override
    public boolean isMainThread() {
        return MAIN.get();
    }

    @Override
    public ExecutorService io() {
        return io;
    }

    @Override
    public ScheduledExecutorService scheduler() {
        return scheduler;
    }

    /** Sammelt geplante Aufgaben, statt sie auszuführen. */
    static final class ManualScheduler extends ScheduledThreadPoolExecutor {

        record Planned(Runnable task, long delayMillis, long periodMillis, FakeFuture future) {
        }

        final List<Planned> planned = new ArrayList<>();

        ManualScheduler() {
            super(1);
        }

        @Override
        public synchronized ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            FakeFuture future = new FakeFuture();
            planned.add(new Planned(command, unit.toMillis(delay), 0, future));
            return future;
        }

        @Override
        public synchronized ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay,
                                                                      TimeUnit unit) {
            FakeFuture future = new FakeFuture();
            planned.add(new Planned(command, unit.toMillis(initialDelay), unit.toMillis(delay), future));
            return future;
        }

        @Override
        public synchronized ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period,
                                                                   TimeUnit unit) {
            return scheduleWithFixedDelay(command, initialDelay, period, unit);
        }

        synchronized List<Planned> active() {
            return planned.stream().filter(p -> !p.future().isCancelled()).toList();
        }

        /** Führt alle nicht abgebrochenen Aufgaben einmal aus (einmalige werden danach entfernt). */
        void runAll() {
            List<Planned> due;
            synchronized (this) {
                due = active();
                planned.removeIf(p -> p.periodMillis() == 0 && due.contains(p));
            }
            due.forEach(p -> p.task().run());
        }
    }

    static final class FakeFuture implements ScheduledFuture<Object> {
        private volatile boolean cancelled;

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return cancelled;
        }

        @Override
        public Object get() {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            return null;
        }
    }
}
