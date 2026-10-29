package net.techniqstone.buildReplay.simplecloud;

import java.util.List;

/**
 * Eine Seite aus {@code GET /v0/players/sessions}: neueste zuerst, {@code limit} wird serverseitig auf 100
 * begrenzt, ein {@code offset} hinter dem Ende liefert eine leere Liste bei unverändertem {@code total}.
 */
public record CloudSessionPage(List<CloudSession> sessions, int count, int total) {

    public CloudSessionPage {
        sessions = List.copyOf(sessions);
    }

    public static CloudSessionPage empty() {
        return new CloudSessionPage(List.of(), 0, 0);
    }
}
