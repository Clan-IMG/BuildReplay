package net.techniqstone.buildReplay.session.service;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.techniqstone.buildReplay.core.TaskRunner;

/**
 * {@link TaskRunner} für Tests. {@link #deterministic} führt I/O direkt im aufrufenden Thread aus und verzögerte
 * Arbeit erst über {@link ManualScheduler#advance}; {@link #threaded} verwendet echte Pools für
 * Nebenläufigkeitstests. Der "Hauptthread" ist jeweils der Testthread.
 */
final class TestTasks implements TaskRunner {

    private final ExecutorService io;
    private final ScheduledExecutorService scheduler;

    private TestTasks(ExecutorService io, ScheduledExecutorService scheduler) {
        this.io = io;
        this.scheduler = scheduler;
    }

    static TestTasks deterministic(ManualScheduler scheduler) {
        return new TestTasks(new DirectExecutorService(), scheduler);
    }

    static TestTasks threaded() {
        return new TestTasks(Executors.newFixedThreadPool(4), Executors.newScheduledThreadPool(2));
    }

    @Override
    public void runOnMain(Runnable task) {
        task.run();
    }

    @Override
    public void runOnMainLater(Runnable task, long delayTicks) {
        task.run();
    }

    @Override
    public boolean isMainThread() {
        return true;
    }

    @Override
    public ExecutorService io() {
        return io;
    }

    @Override
    public ScheduledExecutorService scheduler() {
        return scheduler;
    }

    void shutdown() throws InterruptedException {
        scheduler.shutdownNow();
        io.shutdown();
        io.awaitTermination(5, TimeUnit.SECONDS);
    }

    /** Führt jede Aufgabe sofort im aufrufenden Thread aus. */
    private static final class DirectExecutorService extends AbstractExecutorService {

        private volatile boolean shutdown;

        @Override
        public void execute(Runnable command) {
            command.run();
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
    }
}
