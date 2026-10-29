package net.techniqstone.buildReplay.command;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.techniqstone.buildReplay.core.TaskRunner;

/**
 * Test-{@link TaskRunner}: Hauptthread-Aufgaben aus anderen Threads landen in einer Warteschlange und laufen erst
 * mit {@link #drainMain()} - so lässt sich prüfen, dass Antworten erst auf dem Hauptthread gesendet werden.
 */
final class QueueingTaskRunner implements TaskRunner {

    private final Deque<Runnable> main = new ArrayDeque<>();
    private boolean onMain;

    private final ExecutorService io = new AbstractExecutorService() {
        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    };

    @Override
    public synchronized void runOnMain(Runnable task) {
        main.add(task);
    }

    @Override
    public synchronized void runOnMainLater(Runnable task, long delayTicks) {
        main.add(task);
    }

    @Override
    public synchronized boolean isMainThread() {
        return onMain;
    }

    synchronized int pendingMain() {
        return main.size();
    }

    /** Führt alle wartenden Hauptthread-Aufgaben aus. */
    void drainMain() {
        while (true) {
            Runnable next;
            synchronized (this) {
                next = main.poll();
                onMain = next != null;
            }
            if (next == null) {
                return;
            }
            try {
                next.run();
            } finally {
                synchronized (this) {
                    onMain = false;
                }
            }
        }
    }

    @Override
    public ExecutorService io() {
        return io;
    }

    @Override
    public ScheduledExecutorService scheduler() {
        throw new UnsupportedOperationException("Befehle planen nichts");
    }
}
