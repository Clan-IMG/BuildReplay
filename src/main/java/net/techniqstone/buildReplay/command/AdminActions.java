package net.techniqstone.buildReplay.command;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.techniqstone.buildReplay.maintenance.RetentionResult;

/** Administrative Aktionen, die das Hauptplugin bereitstellt ({@code /buildreplay admin ...}). */
public interface AdminActions {

    /** Lädt config.yml und messages.yml neu; liefert Konfigurationswarnungen. */
    CompletableFuture<List<String>> reload();

    /** Sofortiger Aufräumlauf; {@code dryRun} zählt nur. */
    CompletableFuture<RetentionResult> cleanup(boolean dryRun);

    /** Outbox leeren und Sitzungsabgleich mit SimpleCloud sofort ausführen. */
    CompletableFuture<Void> syncNow();
}
