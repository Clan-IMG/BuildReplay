package net.techniqstone.buildReplay.session;

import java.util.List;

/** Eine Sitzungs-Kurzreferenz passt auf mehrere Sitzungen - der Spieler muss mehr Zeichen angeben. */
public final class AmbiguousReferenceException extends RuntimeException {

    private final List<String> candidates;

    public AmbiguousReferenceException(String ref, List<String> candidates) {
        super("Mehrdeutige Sitzungsreferenz '" + ref + "': " + candidates);
        this.candidates = List.copyOf(candidates);
    }

    public List<String> candidates() {
        return candidates;
    }
}
