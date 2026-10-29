package net.techniqstone.buildReplay.recording.service.fakes;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.techniqstone.buildReplay.core.TaskRunner;

/**
 * Synchroner {@link TaskRunner}: Hauptthread-Aufgaben und I/O laufen sofort auf dem Testthread, verzögerte Aufgaben
 * warten, bis der Test sie mit {@link #runDelayed()} auslöst. Mit {@link #rejectIo} lässt sich ein beendeter Pool
 * nachstellen.
 */
public final class FakeTaskRunner implements TaskRunner {

    /** Verzögerte Hauptthread-Aufgabe. */
    public record Delayed(Runnable task, long delayTicks) {
    }

    private final List<Delayed> delayed = new ArrayList<>();
    private final DirectExecutorService io = new DirectExecutorService();
    private ScheduledExecutorService scheduler;
    public boolean rejectIo;

    @Override
    public void runOnMain(Runnable task) {
        task.run();
    }

    @Override
    public void runOnMainLater(Runnable task, long delayTicks) {
        delayed.add(new Delayed(task, delayTicks));
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
    public synchronized ScheduledExecutorService scheduler() {
        if (scheduler == null) {
            scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "fake-scheduler");
                thread.setDaemon(true);
                return thread;
            });
        }
        return scheduler;
    }

    /** Führt alle bisher eingereihten verzögerten Aufgaben aus (neu eingereihte warten auf den nächsten Aufruf). */
    public int runDelayed() {
        List<Delayed> due = new ArrayList<>(delayed);
        delayed.clear();
        due.forEach(entry -> entry.task().run());
        return due.size();
    }

    /** Führt verzögerte Aufgaben wiederholt aus, höchstens {@code rounds} Runden. */
    public void runDelayed(int rounds) {
        for (int i = 0; i < rounds && !delayed.isEmpty(); i++) {
            runDelayed();
        }
    }

    public List<Delayed> delayed() {
        return List.copyOf(delayed);
    }

    private final class DirectExecutorService extends AbstractExecutorService {
        private volatile boolean shutdown;

        @Override
        public void execute(Runnable command) {
            if (shutdown || rejectIo) {
                throw new RejectedExecutionException("I/O-Pool beendet");
            }
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
