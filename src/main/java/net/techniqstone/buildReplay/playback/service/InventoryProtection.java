package net.techniqstone.buildReplay.playback.service;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import org.bukkit.entity.Player;

/**
 * Sicherung von Inventar, Level/XP und Flugmodus eines Zuschauers während einer Wiedergabe. AdvancedReplay leert das
 * Inventar beim Start und stellt es erst nach dem Ende wieder her - beim Quit zu spät, bei einem Absturz oder einem
 * Startfehler gar nicht. Alle Methoden außer {@link #flush} auf dem Hauptthread.
 */
interface InventoryProtection {

    /**
     * Sichert den aktuellen Zustand (im Speicher und auf Platte), sofern noch keine Sicherung besteht - eine ältere
     * Sicherung ist dann die richtige.
     *
     * @return {@code true}, wenn danach eine Sicherung vorliegt
     */
    boolean backup(Player viewer);

    boolean hasBackup(UUID viewer);

    /** Sicherung sofort anwenden und entfernen; {@code false}, wenn keine vorlag. */
    boolean restore(Player viewer);

    /** Sicherung verwerfen, ohne sie anzuwenden (AdvancedReplay hat den Zuschauer selbst zurückgesetzt). */
    void discard(UUID viewer);

    /**
     * Beim Join: eine liegengebliebene Sicherung (Absturz während einer Wiedergabe) anwenden, wenn {@code allowed} auf
     * dem Hauptthread zustimmt (Spieler sieht gerade nichts). Das Future schließt auf dem Hauptthread ab.
     */
    CompletableFuture<Boolean> restoreOrphan(Player viewer, BooleanSupplier allowed);

    /** Wartet höchstens {@code timeoutMillis}, bis alle eingereihten Dateioperationen erledigt sind. */
    void flush(long timeoutMillis);
}
