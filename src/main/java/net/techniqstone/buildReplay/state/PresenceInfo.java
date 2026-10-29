package net.techniqstone.buildReplay.state;

import java.time.Instant;

/**
 * Wo sich ein Spieler gerade befindet. Nach dem Verlassen eines Servers bleibt der Eintrag für die
 * Wechsel-Karenzzeit mit {@code online=false} sichtbar, damit der Zielserver einen Serverwechsel von einem neuen
 * Login unterscheiden kann, auch wenn SimpleCloud gerade nicht erreichbar ist.
 */
public record PresenceInfo(String serverName, String instanceId, Instant since, boolean online) {
}
