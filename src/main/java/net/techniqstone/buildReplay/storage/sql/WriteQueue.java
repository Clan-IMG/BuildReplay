package net.techniqstone.buildReplay.storage.sql;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;

/**
 * Begrenzte FIFO-Warteschlange der Schreibvorgänge (zugleich der Inhalt der Outbox). Jeder Eintrag hat eine
 * fortlaufende Nummer in Warteschlangenreihenfolge; aus der Outbox geladene Vorgänge werden mit kleineren Nummern
 * vorne eingereiht. {@link #flushFuture()} merkt sich die Nummer des letzten Eintrags und wird fertig, sobald alle
 * Einträge bis dahin entweder ausgeführt (aus der Schlange entfernt) oder in einem Outbox-Schnappschuss enthalten
 * sind. {@link #prepend} ist nur vor dem Start des Schreib-Threads gedacht (die Vorgänge stammen aus der Datei).
 *
 * <p>Alle Methoden sind threadsicher; Sperren werden nur kurz gehalten (der Hauptthread reiht ein). Futures werden
 * außerhalb der Sperre abgeschlossen.
 */
final class WriteQueue {

    record Entry(long seq, WriteOp op) {
    }

    /** Inhalt der Schlange zu einem Zeitpunkt: {@code tailSeq} = Nummer des letzten Eintrags, {@code version} = Änderungszähler. */
    record Snapshot(List<WriteOp> ops, long tailSeq, long version) {
        boolean isEmpty() {
            return ops.isEmpty();
        }
    }

    private record Waiter(long targetSeq, CompletableFuture<Void> future) {
    }

    private final int capacity;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private final List<Waiter> waiters = new ArrayList<>();
    private long nextSeq = 1;
    private long lowestSeq = 1;
    /** Alle Einträge mit Nummer ≤ diesem Wert sind ausgeführt oder in der Outbox-Datei. */
    private long journaledSeq;
    private long version;
    private boolean closed;
    private boolean signalled;

    WriteQueue(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity < 1");
        }
        this.capacity = capacity;
    }

    int capacity() {
        return capacity;
    }

    /** Reiht ein; {@code false}, wenn die Schlange voll oder geschlossen ist (der Vorgang ist dann verworfen). */
    boolean offer(WriteOp op) {
        lock.lock();
        try {
            if (closed || entries.size() >= capacity) {
                return false;
            }
            entries.addLast(new Entry(nextSeq++, op));
            version++;
            signalled = true;
            changed.signalAll();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Stellt Vorgänge (aus der Outbox) in gegebener Reihenfolge vor die vorhandenen. Was über die Kapazität geht,
     * wird nicht übernommen.
     *
     * @return Anzahl übernommener Vorgänge
     */
    int prepend(List<WriteOp> ops) {
        lock.lock();
        try {
            int accepted = Math.max(0, Math.min(ops.size(), capacity - entries.size()));
            for (int i = accepted - 1; i >= 0; i--) {
                entries.addFirst(new Entry(--lowestSeq, ops.get(i)));
            }
            if (accepted > 0) {
                version++;
                signalled = true;
                changed.signalAll();
            }
            return accepted;
        } finally {
            lock.unlock();
        }
    }

    @Nullable Entry peek() {
        lock.lock();
        try {
            return entries.peekFirst();
        } finally {
            lock.unlock();
        }
    }

    /** Entfernt den ausgeführten (oder endgültig verworfenen) Kopf und schließt erreichte Flushes ab. */
    void complete(Entry entry) {
        List<CompletableFuture<Void>> ready;
        lock.lock();
        try {
            if (entries.peekFirst() != entry) {
                return;
            }
            entries.removeFirst();
            version++;
            ready = takeReady();
        } finally {
            lock.unlock();
        }
        ready.forEach(f -> f.complete(null));
    }

    int size() {
        lock.lock();
        try {
            return entries.size();
        } finally {
            lock.unlock();
        }
    }

    boolean isEmpty() {
        return size() == 0;
    }

    long version() {
        lock.lock();
        try {
            return version;
        } finally {
            lock.unlock();
        }
    }

    Snapshot snapshot() {
        lock.lock();
        try {
            List<WriteOp> ops = new ArrayList<>(entries.size());
            for (Entry entry : entries) {
                ops.add(entry.op());
            }
            Entry last = entries.peekLast();
            long tail = last == null ? nextSeq - 1 : last.seq();
            return new Snapshot(List.copyOf(ops), tail, version);
        } finally {
            lock.unlock();
        }
    }

    /** Ein Schnappschuss bis {@code tailSeq} liegt jetzt auf Platte. */
    void markJournaled(long tailSeq) {
        List<CompletableFuture<Void>> ready;
        lock.lock();
        try {
            journaledSeq = Math.max(journaledSeq, tailSeq);
            ready = takeReady();
        } finally {
            lock.unlock();
        }
        ready.forEach(f -> f.complete(null));
    }

    /** Wird fertig, wenn alle bis jetzt eingereihten Vorgänge ausgeführt oder gesichert sind. */
    CompletableFuture<Void> flushFuture() {
        lock.lock();
        try {
            Entry last = entries.peekLast();
            if (last == null || last.seq() <= journaledSeq) {
                return CompletableFuture.completedFuture(null);
            }
            CompletableFuture<Void> future = new CompletableFuture<>();
            waiters.add(new Waiter(last.seq(), future));
            signalled = true;
            changed.signalAll();
            return future;
        } finally {
            lock.unlock();
        }
    }

    boolean hasWaiters() {
        lock.lock();
        try {
            return !waiters.isEmpty();
        } finally {
            lock.unlock();
        }
    }

    /** Schließt alle offenen Flushes ab (beim Beenden, nachdem der Rest gesichert wurde oder werden konnte). */
    void completeAllWaiters(@Nullable Throwable error) {
        List<Waiter> all;
        lock.lock();
        try {
            all = new ArrayList<>(waiters);
            waiters.clear();
        } finally {
            lock.unlock();
        }
        for (Waiter waiter : all) {
            if (error == null) {
                waiter.future().complete(null);
            } else {
                waiter.future().completeExceptionally(error);
            }
        }
    }

    /** Keine neuen Vorgänge mehr annehmen. */
    void close() {
        lock.lock();
        try {
            closed = true;
            signalled = true;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    boolean isClosed() {
        lock.lock();
        try {
            return closed;
        } finally {
            lock.unlock();
        }
    }

    /** Weckt den Schreib-Thread (Flush, Beenden, Wiederverbindungswunsch). */
    void signal() {
        lock.lock();
        try {
            signalled = true;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Wartet höchstens {@code nanos} auf ein Signal (neuer Vorgang, Flush, Beenden). Ein Signal, das vor dem Aufruf
     * kam, wird nicht verpasst.
     */
    void await(long nanos) throws InterruptedException {
        lock.lock();
        try {
            long remaining = nanos;
            while (!signalled && remaining > 0) {
                remaining = changed.awaitNanos(remaining);
            }
            signalled = false;
        } finally {
            lock.unlock();
        }
    }

    private List<CompletableFuture<Void>> takeReady() {
        // Alles vor dem Kopf ist erledigt (FIFO, Nummern steigen in Schlangenreihenfolge); leer = alles erledigt.
        Entry head = entries.peekFirst();
        long reached = head == null ? Long.MAX_VALUE : Math.max(journaledSeq, head.seq() - 1);
        List<CompletableFuture<Void>> ready = new ArrayList<>();
        for (Iterator<Waiter> it = waiters.iterator(); it.hasNext(); ) {
            Waiter waiter = it.next();
            if (waiter.targetSeq() <= reached) {
                ready.add(waiter.future());
                it.remove();
            }
        }
        return ready;
    }
}
