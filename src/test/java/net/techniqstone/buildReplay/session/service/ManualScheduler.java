package net.techniqstone.buildReplay.session.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Scheduler mit virtueller Zeit: Aufgaben laufen erst bei {@link #advance(Duration)}, und zwar im Testthread.
 * Die zugehörige {@link MutableClock} wird dabei auf den jeweiligen Fälligkeitszeitpunkt gestellt.
 */
final class ManualScheduler extends AbstractExecutorService implements ScheduledExecutorService {

    private final MutableClock clock;
    private final PriorityQueue<Job> jobs = new PriorityQueue<>();
    private long sequence;
    private boolean shutdown;

    ManualScheduler(MutableClock clock) {
        this.clock = clock;
    }

    /** Lässt die Zeit um {@code duration} fortschreiten und führt alle bis dahin fälligen Aufgaben aus. */
    void advance(Duration duration) {
        Instant target = clock.instant().plus(duration);
        while (true) {
            Job next;
            synchronized (this) {
                next = jobs.peek();
                if (next == null || next.due.isAfter(target)) {
                    break;
                }
                jobs.poll();
            }
            if (next.isCancelled()) {
                continue;
            }
            if (next.due.isAfter(clock.instant())) {
                clock.set(next.due);
            }
            next.fire();
        }
        if (target.isAfter(clock.instant())) {
            clock.set(target);
        }
    }

    /** Führt alle sofort fälligen Aufgaben aus. */
    void runDue() {
        advance(Duration.ZERO);
    }

    synchronized int pendingJobs() {
        return (int) jobs.stream().filter(job -> !job.isCancelled()).count();
    }

    private synchronized Job enqueue(Job job) {
        if (shutdown) {
            throw new RejectedExecutionException("Scheduler beendet");
        }
        jobs.add(job);
        return job;
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        return enqueue(new Job(command, due(delay, unit), 0));
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        throw new UnsupportedOperationException("im Test nicht benötigt");
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        return enqueue(new Job(command, due(initialDelay, unit), unit.toMillis(period)));
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        return scheduleAtFixedRate(command, initialDelay, delay, unit);
    }

    @Override
    public void execute(Runnable command) {
        schedule(command, 0, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void shutdown() {
        shutdown = true;
    }

    @Override
    public synchronized List<Runnable> shutdownNow() {
        shutdown = true;
        List<Runnable> pending = new ArrayList<>(jobs);
        jobs.clear();
        return pending;
    }

    @Override
    public synchronized boolean isShutdown() {
        return shutdown;
    }

    @Override
    public synchronized boolean isTerminated() {
        return shutdown;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        return true;
    }

    private Instant due(long delay, TimeUnit unit) {
        return clock.instant().plusMillis(Math.max(0, unit.toMillis(delay)));
    }

    private final class Job extends FutureTask<Object> implements ScheduledFuture<Object> {

        private final long periodMillis;
        private final long order;
        private Instant due;

        Job(Runnable command, Instant due, long periodMillis) {
            super(command, null);
            this.due = due;
            this.periodMillis = periodMillis;
            synchronized (ManualScheduler.this) {
                this.order = sequence++;
            }
        }

        void fire() {
            if (periodMillis <= 0) {
                run();
            } else if (runAndReset()) {
                due = due.plusMillis(periodMillis);
                synchronized (ManualScheduler.this) {
                    if (!shutdown) {
                        jobs.add(this);
                    }
                }
            }
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(Duration.between(clock.instant(), due));
        }

        @Override
        public int compareTo(Delayed other) {
            if (other instanceof Job job) {
                int byDue = due.compareTo(job.due);
                return byDue != 0 ? byDue : Long.compare(order, job.order);
            }
            return Long.compare(getDelay(TimeUnit.MILLISECONDS), other.getDelay(TimeUnit.MILLISECONDS));
        }
    }
}
