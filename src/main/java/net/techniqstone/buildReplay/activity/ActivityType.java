package net.techniqstone.buildReplay.activity;

import java.util.Locale;
import java.util.Optional;

/**
 * Arten von Spieleraktivität. Ob eine Art als <em>relevante Aktion</em> (startet/verlängert einen Replay-Abschnitt)
 * oder nur als <em>Aktivitätsindikator</em> (Spieler ist nicht AFK, startet aber keinen Abschnitt) zählt, legt die
 * Konfiguration fest ({@code activity.relevant-actions} / {@code activity.indicators}); die hier hinterlegten Werte
 * sind nur die Standardbelegung.
 */
public enum ActivityType {

    // --- standardmäßig relevante Aktionen (Bauen/Verändern der Welt)
    BLOCK_PLACE(true),
    BLOCK_BREAK(true),
    BUCKET(true),
    SIGN_EDIT(true),
    HANGING(true),
    ARMOR_STAND(true),
    ENTITY_PLACE(true),
    BLOCK_INTERACT(true),
    WORLDEDIT(true),
    AXIOM(true),

    // --- standardmäßig nur Indikatoren (Spieler ist anwesend, baut aber nicht)
    MOVE(false),
    LOOK(false),
    CHAT(false),
    COMMAND(false),
    INVENTORY(false),
    ITEM_HELD(false),
    SWING(false);

    private final boolean relevantByDefault;

    ActivityType(boolean relevantByDefault) {
        this.relevantByDefault = relevantByDefault;
    }

    public boolean relevantByDefault() {
        return relevantByDefault;
    }

    /** Schlüssel in der Konfiguration, z. B. {@code block-place}. */
    public String configKey() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static Optional<ActivityType> fromConfigKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        String normalized = key.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (ActivityType type : values()) {
            if (type.name().equals(normalized)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
