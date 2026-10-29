package net.techniqstone.buildReplay.simplecloud.rest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Einfacher Circuit-Breaker für die SimpleCloud-REST-API.
 *
 * <ul>
 *     <li>{@code CLOSED}: Aufrufe laufen normal; nach {@code threshold} aufeinanderfolgenden fehlgeschlagenen
 *     Aufrufen (nach allen Wiederholungen) wird der Breaker geöffnet.</li>
 *     <li>{@code OPEN}: Aufrufe werden für {@code openDuration} sofort abgelehnt, ohne Anfrage.</li>
 *     <li>{@code HALF_OPEN}: genau ein Probeaufruf darf durch; Erfolg schließt den Breaker, ein Fehler öffnet ihn
 *     erneut. Bleibt das Ergebnis der Probe aus, wird nach {@code openDuration} eine neue Probe erlaubt.</li>
 * </ul>
 *
 * Threadsicher; die Zeit kommt aus der übergebenen {@link Clock}.
 */
final class CircuitBreaker {

    /** Erlaubnis für einen Aufruf. */
    enum Permit { NORMAL, TRIAL, REJECTED }

    enum State { CLOSED, OPEN, HALF_OPEN }

    /** Zustandswechsel durch ein Ergebnis (für das Logging). */
    enum Change { NONE, OPENED, REOPENED, CLOSED }

    private final int threshold;
    private final Duration openDuration;
    private final Clock clock;

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private @Nullable Instant openUntil;
    private @Nullable Instant trialStartedAt;

    CircuitBreaker(int threshold, Duration openDuration, Clock clock) {
        this.threshold = Math.max(1, threshold);
        this.openDuration = openDuration.isNegative() ? Duration.ZERO : openDuration;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    synchronized Permit tryAcquire() {
        Instant now = clock.instant();
        switch (state) {
            case CLOSED -> {
                return Permit.NORMAL;
            }
            case OPEN -> {
                if (openUntil != null && now.isBefore(openUntil)) {
                    return Permit.REJECTED;
                }
                state = State.HALF_OPEN;
                trialStartedAt = now;
                return Permit.TRIAL;
            }
            default -> {
                // HALF_OPEN: nur eine Probe gleichzeitig; eine verlorene Probe blockiert nicht dauerhaft
                if (trialStartedAt != null && now.isBefore(trialStartedAt.plus(openDuration))) {
                    return Permit.REJECTED;
                }
                trialStartedAt = now;
                return Permit.TRIAL;
            }
        }
    }

    /**
     * Aufruf war erfolgreich oder der Server hat zumindest sinnvoll geantwortet (z. B. 400/404). Jede Antwort
     * belegt die Erreichbarkeit und schließt den Breaker.
     */
    synchronized Change onSuccess(Permit permit) {
        if (permit == Permit.REJECTED) {
            return Change.NONE;
        }
        consecutiveFailures = 0;
        if (state == State.CLOSED) {
            return Change.NONE;
        }
        state = State.CLOSED;
        openUntil = null;
        trialStartedAt = null;
        return Change.CLOSED;
    }

    /** Aufruf ist endgültig fehlgeschlagen (nach Wiederholungen). */
    synchronized Change onFailure(Permit permit) {
        if (permit == Permit.REJECTED) {
            return Change.NONE;
        }
        if (permit == Permit.TRIAL && state == State.HALF_OPEN) {
            open();
            return Change.REOPENED;
        }
        if (state != State.CLOSED) {
            // verspätetes Ergebnis eines vor dem Öffnen gestarteten Aufrufs
            return Change.NONE;
        }
        consecutiveFailures++;
        if (consecutiveFailures >= threshold) {
            open();
            return Change.OPENED;
        }
        return Change.NONE;
    }

    synchronized State state() {
        return state;
    }

    /** Ob der Breaker nicht geschlossen ist (offen oder in der Probephase). */
    synchronized boolean isOpen() {
        return state != State.CLOSED;
    }

    synchronized @Nullable Instant openUntil() {
        return openUntil;
    }

    Duration openDuration() {
        return openDuration;
    }

    private void open() {
        state = State.OPEN;
        openUntil = clock.instant().plus(openDuration);
        trialStartedAt = null;
        consecutiveFailures = 0;
    }
}
