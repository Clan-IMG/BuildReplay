package net.techniqstone.buildReplay.storage.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class WriteQueueTest {

    private static WriteOp op(String name) {
        return new WriteOp.UpsertPlayer(UUID.nameUUIDFromBytes(name.getBytes()), name, Instant.EPOCH);
    }

    @Test
    void rejectsWritesBeyondItsCapacity() {
        WriteQueue queue = new WriteQueue(2);
        assertTrue(queue.offer(op("a")));
        assertTrue(queue.offer(op("b")));
        assertFalse(queue.offer(op("c")));
        assertEquals(2, queue.size());
    }

    @Test
    void aClosedQueueAcceptsNothing() {
        WriteQueue queue = new WriteQueue(5);
        queue.close();
        assertFalse(queue.offer(op("a")));
        assertTrue(queue.isClosed());
    }

    @Test
    void flushOnAnEmptyQueueIsAlreadyDone() {
        assertTrue(new WriteQueue(5).flushFuture().isDone());
    }

    @Test
    void flushCompletesOnceEveryEarlierWriteWasExecuted() {
        WriteQueue queue = new WriteQueue(5);
        queue.offer(op("a"));
        queue.offer(op("b"));
        CompletableFuture<Void> flush = queue.flushFuture();
        queue.offer(op("c")); // nach dem Flush eingereiht - zählt nicht
        queue.complete(queue.peek());
        assertFalse(flush.isDone());
        queue.complete(queue.peek());
        assertTrue(flush.isDone());
        assertEquals(1, queue.size());
    }

    @Test
    void flushCompletesWhenASnapshotContainingTheWritesWasJournaled() {
        WriteQueue queue = new WriteQueue(5);
        queue.offer(op("a"));
        CompletableFuture<Void> flush = queue.flushFuture();
        WriteQueue.Snapshot snapshot = queue.snapshot();
        assertEquals(1, snapshot.ops().size());
        queue.markJournaled(snapshot.tailSeq());
        assertTrue(flush.isDone());
        // ein später eingereihter Vorgang ist vom alten Schnappschuss nicht abgedeckt
        queue.offer(op("b"));
        assertFalse(queue.flushFuture().isDone());
    }

    @Test
    void prependedWritesRunBeforeTheQueuedOnesInTheirOriginalOrder() {
        WriteQueue queue = new WriteQueue(10);
        queue.offer(op("new"));
        assertEquals(2, queue.prepend(List.of(op("old1"), op("old2"))));
        List<WriteOp> ops = queue.snapshot().ops();
        assertEquals(List.of(op("old1"), op("old2"), op("new")), ops);
        WriteQueue.Entry first = queue.peek();
        assertNotNull(first);
        assertEquals(op("old1"), first.op());
    }

    @Test
    void prependTakesOnlyWhatFitsIntoTheCapacity() {
        WriteQueue queue = new WriteQueue(3);
        queue.offer(op("new"));
        assertEquals(2, queue.prepend(List.of(op("1"), op("2"), op("3"))));
        assertEquals(List.of(op("1"), op("2"), op("new")), queue.snapshot().ops());
    }

    @Test
    void completingSomethingOtherThanTheHeadChangesNothing() {
        WriteQueue queue = new WriteQueue(5);
        queue.offer(op("a"));
        queue.offer(op("b"));
        WriteQueue.Entry head = queue.peek();
        queue.complete(new WriteQueue.Entry(99, op("x")));
        assertEquals(2, queue.size());
        queue.complete(head);
        assertEquals(1, queue.size());
    }

    @Test
    void everyChangeIncreasesTheVersion() {
        WriteQueue queue = new WriteQueue(5);
        long v0 = queue.version();
        queue.offer(op("a"));
        long v1 = queue.version();
        queue.complete(queue.peek());
        assertTrue(v1 > v0);
        assertTrue(queue.version() > v1);
    }

    @Test
    void completeAllWaitersReleasesPendingFlushes() {
        WriteQueue queue = new WriteQueue(5);
        queue.offer(op("a"));
        CompletableFuture<Void> flush = queue.flushFuture();
        queue.completeAllWaiters(null);
        assertTrue(flush.isDone());
        assertFalse(queue.hasWaiters());
    }

    @Test
    void aSignalBeforeAwaitIsNotLost() throws InterruptedException {
        WriteQueue queue = new WriteQueue(5);
        queue.signal();
        long start = System.nanoTime();
        queue.await(5_000_000_000L);
        assertTrue(System.nanoTime() - start < 1_000_000_000L);
    }
}
