package net.techniqstone.buildReplay.recording.service.fakes;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import net.techniqstone.buildReplay.activity.ActivityType;
import net.techniqstone.buildReplay.activity.SegmentPlan;
import net.techniqstone.buildReplay.activity.SegmentPlanning;
import net.techniqstone.buildReplay.activity.TimelineEvent;

/**
 * Vereinfachte Abschnittsplanung: relevante Aktionen mit mehr als {@code gapTicks} Abstand bilden getrennte
 * Abschnitte, je mit {@code rollTicks} Vor- und Nachlauf. Tests können die Planung auch komplett vorgeben.
 */
public final class FakePlanner implements SegmentPlanning {

    /** Ein Aufruf: Ereignisse und Endtick. */
    public record Call(List<TimelineEvent> events, int endTick) {
    }

    public final List<Call> calls = new ArrayList<>();
    public int gapTicks = 1200;
    public int rollTicks = 200;
    public BiFunction<List<TimelineEvent>, Integer, List<SegmentPlan>> override;

    @Override
    public List<SegmentPlan> plan(List<TimelineEvent> events, int endTick) {
        calls.add(new Call(List.copyOf(events), endTick));
        if (override != null) {
            return override.apply(events, endTick);
        }
        List<SegmentPlan> plans = new ArrayList<>();
        List<TimelineEvent> group = new ArrayList<>();
        for (TimelineEvent event : events) {
            if (!event.relevant()) {
                continue;
            }
            if (!group.isEmpty() && event.tick() - group.get(group.size() - 1).tick() > gapTicks) {
                plans.add(toPlan(group, endTick));
                group = new ArrayList<>();
            }
            group.add(event);
        }
        if (!group.isEmpty()) {
            plans.add(toPlan(group, endTick));
        }
        return plans;
    }

    private SegmentPlan toPlan(List<TimelineEvent> group, int endTick) {
        int first = group.get(0).tick();
        int last = group.get(group.size() - 1).tick();
        Map<ActivityType, Integer> counts = new EnumMap<>(ActivityType.class);
        group.forEach(event -> counts.merge(event.type(), 1, Integer::sum));
        return new SegmentPlan(Math.max(0, first - rollTicks), Math.min(endTick, last + rollTicks), first, last,
                group.size(), counts);
    }
}
