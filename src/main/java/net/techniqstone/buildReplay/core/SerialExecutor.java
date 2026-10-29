package net.techniqstone.buildReplay.core;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Führt Aufgaben nacheinander in Einreihungsreihenfolge auf einem fremden Pool aus, ohne einen eigenen Thread zu
 * belegen. Gebraucht, wo die Reihenfolge zählt: Redis-Zugriffe (Aufnahmestatus setzen und wieder entfernen, Übergaben)
 * und Dateizugriffe auf Inventar-Sicherungen (Schreiben muss vor dem Löschen laufen). Lehnt der Pool ab (z. B. beim
 * Herunterfahren), werden die wartenden Aufgaben je nach Einstellung direkt auf dem aufrufenden Thread ausgeführt
 * (Dateien - eine liegengebliebene Sicherung würde beim nächsten Join ein altes Inventar zurückspielen) oder
 * verworfen (Redis). Fehler einer Aufgabe werden protokolliert und halten die folgenden nicht auf.
 */
public final class SerialExecutor implements Executor {

    private final Executor delegate;
    private final Logger logger;
    private final boolean runInlineWhenRejected;
    private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    private @Nullable Runnable active;

    /**
     * @param runInlineWhenRejected {@code true}: lehnt {@code delegate} ab, laufen die Aufgaben auf dem aufrufenden
     *                              Thread; {@code false}: sie werden verworfen
     */
    public SerialExecutor(Executor delegate, Logger logger, boolean runInlineWhenRejected) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.runInlineWhenRejected = runInlineWhenRejected;
    }

    @Override
    public synchronized void execute(Runnable task) {
        Objects.requireNonNull(task, "task");
        queue.add(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "Fehler in einer BuildReplay-Hintergrundaufgabe", e);
            } finally {
                scheduleNext();
            }
        });
        if (active == null) {
            scheduleNext();
        }
    }

    private synchronized void scheduleNext() {
        Runnable next = queue.poll();
        active = next;
        if (next == null) {
            return;
        }
        try {
            delegate.execute(next);
        } catch (RejectedExecutionException e) {
            if (runInlineWhenRejected) {
                next.run();
                return;
            }
            int dropped = queue.size() + 1;
            queue.clear();
            active = null;
            logger.fine("Hintergrund-Pool nimmt keine Aufgaben mehr an - " + dropped + " Aufgabe(n) verworfen");
        }
    }
}
