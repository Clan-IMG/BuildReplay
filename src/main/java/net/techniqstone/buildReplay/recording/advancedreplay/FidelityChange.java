package net.techniqstone.buildReplay.recording.advancedreplay;

import java.util.Objects;

/**
 * Exakte Blockänderung (vorher/nachher als {@code BlockData#getAsString()}), die AdvancedReplay selbst nicht
 * speichert (dort nur das Material - Treppen, Stufen, Türen usw. verlieren ihren Zustand).
 */
record FidelityChange(BlockKey key, String before, String after) {

    FidelityChange {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
    }
}
