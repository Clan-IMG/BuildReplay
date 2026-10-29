package net.techniqstone.buildReplay.session;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.techniqstone.buildReplay.storage.model.VisitEndReason;

/**
 * Sitzungsverwaltung dieser Instanz. Die netzwerkweite Sitzung wird über SimpleCloud bestimmt (Session-ID), nicht
 * über lokale Join-/Quit-Zeitpunkte: ein Serverwechsel ändert die Sitzung nicht.
 */
public interface Sessions {

    /** Hauptthread: Spieler hat diesen Server betreten. Startet die asynchrone Auflösung und den Aufenthalt. */
    void handleJoin(UUID player, String name, Instant joinedAt);

    /** Hauptthread: Spieler hat diesen Server verlassen (Wechsel oder Logout - lokal nicht unterscheidbar). */
    void handleQuit(UUID player, String name, Instant leftAt, VisitEndReason reason);

    /** Bereits aufgelöste Sitzung eines lokal anwesenden (oder gerade gegangenen) Spielers. */
    Optional<NetworkSession> current(UUID player);

    /**
     * Sitzung eines Spielers, der auf diesem Server ist oder war. Wiederholt die SimpleCloud-Abfrage beim ersten
     * Join (Sitzung wird beim Proxy-Login mit Verzögerung angelegt) und fällt bei Ausfall auf eine vorläufige
     * Sitzung zurück; schlägt daher nur fehl, wenn auch das deaktiviert ist.
     */
    CompletableFuture<NetworkSession> resolve(UUID player);

    SessionServiceStats stats();
}
