package net.techniqstone.buildReplay.session.service;

import java.time.Duration;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import net.techniqstone.buildReplay.core.TaskRunner;

/** Kleine Hilfen für die asynchrone Verkettung (ohne blockierendes Warten auf I/O-Threads). */
final class Futures {

    private static final CompletableFuture<Void> DONE = CompletableFuture.completedFuture(null);

    private Futures() {
    }

    static CompletableFuture<Void> done() {
        return DONE;
    }

    /** Entfernt {@link CompletionException}/{@link ExecutionException}-Hüllen. */
    static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /** Ruft eine asynchrone API auf; synchron geworfene Fehler landen im Future statt beim Aufrufer. */
    static <T> CompletableFuture<T> call(Supplier<CompletableFuture<T>> call) {
        try {
            CompletableFuture<T> future = call.get();
            return future != null ? future
                    : CompletableFuture.failedFuture(new IllegalStateException("Aufruf lieferte kein Future"));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Future, das nach {@code delay} auf dem I/O-Pool abschließt. Der Scheduler wartet nur, die Folgearbeit läuft
     * auf {@link TaskRunner#io()}.
     */
    static CompletableFuture<Void> delay(TaskRunner tasks, Duration delay) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        try {
            tasks.scheduler().schedule(() -> {
                try {
                    tasks.io().execute(() -> result.complete(null));
                } catch (RejectedExecutionException e) {
                    result.completeExceptionally(e);
                }
            }, Math.max(0L, delay.toMillis()), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            result.completeExceptionally(e);
        }
        return result;
    }

    /**
     * Führt {@code action} nacheinander für alle Elemente aus. Fehler einzelner Elemente brechen die Schleife nicht
     * ab - die Aktion ist selbst für Protokollierung zuständig.
     */
    static <T> CompletableFuture<Void> sequentially(Iterator<T> items, Function<T, CompletableFuture<?>> action,
                                                    Executor executor) {
        if (!items.hasNext()) {
            return DONE;
        }
        T item = items.next();
        CompletableFuture<?> step;
        try {
            step = action.apply(item);
        } catch (RuntimeException e) {
            step = CompletableFuture.failedFuture(e);
        }
        return step.handle((ignored, error) -> (Void) null)
                .thenComposeAsync(ignored -> sequentially(items, action, executor), executor);
    }
}
