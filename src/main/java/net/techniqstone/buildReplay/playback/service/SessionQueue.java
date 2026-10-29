package net.techniqstone.buildReplay.playback.service;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

/**
 * Abschnitte einer Sitzung, die ein Zuschauer nacheinander ansieht (nur die auf diesem Server abspielbaren). Nur auf
 * dem Hauptthread benutzen.
 */
final class SessionQueue {

    private final UUID owner;
    private final String ref;
    private final String displayRef;
    private final List<Long> segmentIds;
    private int next;

    /**
     * @param ref        Referenz, mit der die Sitzung gefunden wurde (für die Übergabe an andere Server)
     * @param displayRef Kurzreferenz für Nachrichten
     */
    SessionQueue(UUID owner, String ref, String displayRef, List<Long> segmentIds) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.ref = Objects.requireNonNull(ref, "ref");
        this.displayRef = Objects.requireNonNull(displayRef, "displayRef");
        this.segmentIds = List.copyOf(segmentIds);
    }

    UUID owner() {
        return owner;
    }

    String ref() {
        return ref;
    }

    String displayRef() {
        return displayRef;
    }

    boolean hasNext() {
        return next < segmentIds.size();
    }

    long peek() {
        if (!hasNext()) {
            throw new NoSuchElementException("Keine weiteren Abschnitte");
        }
        return segmentIds.get(next);
    }

    long next() {
        long id = peek();
        next++;
        return id;
    }

    /** 1-basierte Position des zuletzt entnommenen Abschnitts. */
    int position() {
        return next;
    }

    int size() {
        return segmentIds.size();
    }
}
