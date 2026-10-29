package net.techniqstone.buildReplay.maintenance;

/** Ergebnis eines Aufräumlaufs (Aufbewahrungsfrist, Pro-Spieler-Limit, verwaiste Rohaufnahmen). */
public record RetentionResult(int examined, int deleted, int failed, boolean dryRun) {
}
