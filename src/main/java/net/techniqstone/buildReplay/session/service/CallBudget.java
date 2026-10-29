package net.techniqstone.buildReplay.session.service;

/**
 * Obergrenze für SimpleCloud-Aufrufe eines Abgleichlaufs. Nach einem Verbindungsproblem wird der Rest des Laufs
 * mit {@link #stop()} still übersprungen.
 */
final class CallBudget {

    private int remaining;
    private int used;
    private boolean stopped;

    CallBudget(int calls) {
        this.remaining = Math.max(0, calls);
    }

    /** Verbraucht einen Aufruf; {@code false}, wenn das Budget erschöpft oder der Lauf gestoppt ist. */
    synchronized boolean tryUse() {
        if (stopped || remaining <= 0) {
            return false;
        }
        remaining--;
        used++;
        return true;
    }

    synchronized void stop() {
        stopped = true;
    }

    synchronized boolean exhausted() {
        return stopped || remaining <= 0;
    }

    synchronized boolean stopped() {
        return stopped;
    }

    synchronized int used() {
        return used;
    }
}
