package net.techniqstone.buildReplay.session.service;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.techniqstone.buildReplay.session.NetworkSession;
import net.techniqstone.buildReplay.storage.model.VisitEndReason;
import org.jspecify.annotations.Nullable;

/**
 * Zustand eines Spielers während (und bis zehn Minuten nach) seines Aufenthalts auf diesem Server. Jeder Join
 * erzeugt ein neues Objekt; asynchrone Schritte halten eine Referenz auf "ihr" Objekt, damit ein späterer Join
 * nicht von einer noch laufenden Auflösung überschrieben wird.
 *
 * <p>Aufenthaltsbeginn und -ende werden unter {@code synchronized (this)} geschrieben, damit {@code endVisit} nie
 * vor {@code startVisit} in die Schreibwarteschlange des Stores gelangt.
 */
final class LocalPlayer {

    final UUID uuid;
    final String name;
    final Instant joinedAt;
    final String visitKey;
    final AtomicBoolean upgrading = new AtomicBoolean();

    /** Ergebnis der Sitzungsauflösung; wird bei einer späteren Hochstufung durch ein neues Future ersetzt. */
    volatile CompletableFuture<NetworkSession> result = new CompletableFuture<>();
    volatile @Nullable NetworkSession session;
    /** Ende der Präsenz-Schreibvorgänge (markPresent/markLeft) - Folgeschritte hängen sich hier an. */
    volatile CompletableFuture<Void> presenceTail = Futures.done();
    volatile boolean present = true;
    volatile @Nullable Instant leftAt;
    volatile @Nullable VisitEndReason endReason;
    volatile @Nullable Instant cachedAt;

    // nur unter synchronized (this)
    boolean visitStarted;
    boolean visitEnded;

    LocalPlayer(UUID uuid, String name, Instant joinedAt, String visitKey) {
        this.uuid = uuid;
        this.name = name;
        this.joinedAt = joinedAt;
        this.visitKey = visitKey;
    }

    /** Ob der Eintrag nach dem Verlassen noch aufgehoben wird. */
    boolean retainedAt(Instant now, java.time.Duration retention) {
        Instant left = leftAt;
        return present || left == null || !now.isAfter(left.plus(retention));
    }
}
