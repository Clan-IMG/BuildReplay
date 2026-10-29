package net.techniqstone.buildReplay.state.redis;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.techniqstone.buildReplay.state.SharedEvent;
import org.jspecify.annotations.Nullable;

/**
 * Stellt {@link SharedEvent}s nacheinander auf einem eigenen Daemon-Thread an alle Empfänger zu. Langsame
 * Empfänger blockieren so weder den Pub/Sub-Lesethread noch den Aufrufer von {@code publish}; läuft die
 * Warteschlange über, werden Events verworfen (Pub/Sub hat ohnehin keine Zustellgarantie).
 */
final class EventDispatcher implements AutoCloseable {

    static final int QUEUE_CAPACITY = 10_000;
    private static final long WARN_INTERVAL_NANOS = 60_000_000_000L;

    private final String threadName;
    private final Logger logger;
    private final List<Consumer<SharedEvent>> listeners = new CopyOnWriteArrayList<>();
    private @Nullable ThreadPoolExecutor executor;
    private volatile boolean closed;
    private volatile long lastDropWarning = System.nanoTime() - WARN_INTERVAL_NANOS;

    EventDispatcher(String threadName, Logger logger) {
        this.threadName = threadName;
        this.logger = logger;
    }

    void add(Consumer<SharedEvent> listener) {
        listeners.add(listener);
    }

    boolean hasListeners() {
        return !listeners.isEmpty();
    }

    /** Reiht das Event zur Zustellung ein (kehrt sofort zurück). */
    void dispatch(SharedEvent event) {
        if (closed || listeners.isEmpty()) {
            return;
        }
        ThreadPoolExecutor target = executor();
        if (target == null) {
            return;
        }
        try {
            target.execute(() -> deliver(event));
        } catch (RejectedExecutionException e) {
            if (!closed) {
                long now = System.nanoTime();
                if (now - lastDropWarning >= WARN_INTERVAL_NANOS) {
                    lastDropWarning = now;
                    logger.warning("BuildReplay-Events: Zustellwarteschlange voll - Event '" + event.type()
                            + "' wird verworfen");
                }
            }
        }
    }

    private void deliver(SharedEvent event) {
        for (Consumer<SharedEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "BuildReplay-Events: Empfänger für '" + event.type()
                        + "' ist fehlgeschlagen", e);
            }
        }
    }

    private synchronized @Nullable ThreadPoolExecutor executor() {
        if (closed) {
            return null;
        }
        if (executor == null) {
            ThreadPoolExecutor created = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(QUEUE_CAPACITY), runnable -> {
                        Thread thread = new Thread(runnable, threadName);
                        thread.setDaemon(true);
                        return thread;
                    }, new ThreadPoolExecutor.AbortPolicy());
            executor = created;
        }
        return executor;
    }

    @Override
    public void close() {
        ThreadPoolExecutor toStop;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            toStop = executor;
            executor = null;
        }
        if (toStop != null) {
            toStop.shutdown();
            try {
                if (!toStop.awaitTermination(1, TimeUnit.SECONDS)) {
                    toStop.shutdownNow();
                }
            } catch (InterruptedException e) {
                toStop.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
