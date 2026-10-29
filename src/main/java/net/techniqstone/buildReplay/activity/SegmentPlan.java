package net.techniqstone.buildReplay.activity;

import java.util.Map;

/**
 * Geplanter Abschnitt in Ticks der Pufferaufnahme. {@code fromTick}/{@code toTick} enthalten Vor- und Nachlauf,
 * {@code firstActionTick}/{@code lastActionTick} die erste/letzte relevante Aktion.
 */
public record SegmentPlan(
        int fromTick,
        int toTick,
        int firstActionTick,
        int lastActionTick,
        int relevantActions,
        Map<ActivityType, Integer> counts) {

    public SegmentPlan {
        if (fromTick < 0 || toTick < fromTick) {
            throw new IllegalArgumentException("Ungültiger Abschnitt " + fromTick + ".." + toTick);
        }
        counts = Map.copyOf(counts);
    }

    public int durationTicks() {
        return toTick - fromTick;
    }
}
