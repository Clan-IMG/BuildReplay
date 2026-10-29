package net.techniqstone.buildReplay.recording;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Abgeschlossene Pufferaufnahme ("Rohaufnahme") - eine von AdvancedReplay unabhängige Momentaufnahme der
 * Aktionen, die die {@link ReplayEngine} intern hält. Wird nur an die Engine zurückgegeben (Abschnitte schneiden,
 * roh speichern).
 */
public interface RawRecording {

    String rawName();

    String playerName();

    @Nullable UUID playerUuid();

    /** Länge in AdvancedReplay-Ticks. */
    int durationTicks();

    int actionCount();

    /** Welt beim Start der Aufnahme (aus der Spawn-Aktion). */
    @Nullable String worldName();
}
