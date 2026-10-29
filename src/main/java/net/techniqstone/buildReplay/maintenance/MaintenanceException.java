package net.techniqstone.buildReplay.maintenance;

/**
 * Eine Wartungsaufgabe kann gerade nicht ausgeführt werden (läuft bereits, AdvancedReplay nicht verfügbar, ...).
 * Die Nachricht ist deutsch und für Administratoren gedacht; Befehle zeigen sie direkt an.
 */
public final class MaintenanceException extends RuntimeException {

    public MaintenanceException(String message) {
        super(message);
    }
}
