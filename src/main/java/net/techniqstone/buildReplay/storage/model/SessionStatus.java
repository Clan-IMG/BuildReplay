package net.techniqstone.buildReplay.storage.model;

public enum SessionStatus {
    /** Spieler ist (vermutlich) noch im Netzwerk. */
    ACTIVE,
    /** Logout bekannt. */
    CLOSED,
    /** Vorläufige Sitzung, die einer SimpleCloud-Sitzung zugeordnet wurde (siehe merged_into). */
    MERGED
}
