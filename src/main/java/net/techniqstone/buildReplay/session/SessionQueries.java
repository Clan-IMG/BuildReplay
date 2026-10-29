package net.techniqstone.buildReplay.session;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Lesende Abfragen für die Befehle; Ergebnisse kommen auf einem I/O-Thread zurück. */
public interface SessionQueries {

    /**
     * Seite {@code page} (0-basiert) der Sitzungen von {@code player} innerhalb von {@code display.history-days}.
     * Blättert über {@code limit}/{@code offset} der SimpleCloud-API, bis die Zeitgrenze erreicht ist.
     */
    CompletableFuture<SessionListPage> listSessions(UUID player, int page);

    /** Details zu einer Sitzung über ihre Kurzreferenz ({@link NetworkSession#shortRef()}). */
    CompletableFuture<Optional<SessionDetails>> details(UUID player, String ref);
}
