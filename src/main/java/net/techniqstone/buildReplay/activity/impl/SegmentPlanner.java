package net.techniqstone.buildReplay.activity.impl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.techniqstone.buildReplay.activity.ActivityType;
import net.techniqstone.buildReplay.activity.SegmentPlan;
import net.techniqstone.buildReplay.activity.SegmentPlanning;
import net.techniqstone.buildReplay.activity.TimelineEvent;
import net.techniqstone.buildReplay.config.PluginConfig;
import org.jspecify.annotations.Nullable;

/**
 * Abschnittsplanung nach {@link SegmentPlanning} - reine, deterministische Funktion ohne Zustand (threadsicher, darf
 * auf dem I/O-Pool laufen). Zeiten werden mit {@value #TICKS_PER_SECOND} Ticks pro Sekunde umgerechnet.
 *
 * <ol>
 *   <li>Ereignisse nach Tick sortieren und auf [0, endTick] begrenzen (unsortierte Eingaben, doppelte Ticks und
 *       {@code null}-Einträge sind erlaubt). Relevant ist, was {@link TimelineEvent#relevant()} sagt, alles andere
 *       sind Indikatoren.</li>
 *   <li>Aufeinanderfolgende relevante Aktionen mit Abstand &gt; {@code relevant-idle} trennen die Phase - außer der
 *       Abstand ist ≤ {@code max-indicator-extension} und Indikatoren decken ihn ohne Loch &gt; {@code afk-timeout}
 *       ab (die beiden relevanten Aktionen zählen als Rand mit).</li>
 *   <li>Phase → [erste − Vorlauf, letzte + Nachlauf], begrenzt auf [0, endTick].</li>
 *   <li>Abschnitte mit Abstand ≤ {@code merge-gap} (oder Überlappung) verbinden.</li>
 *   <li>Abschnitte mit weniger als {@code min-relevant-actions} Aktionen, kürzer als {@code min-segment} oder ohne
 *       Länge (0 Ticks - nicht schneidbar) verwerfen - vor dem Zerlegen.</li>
 *   <li>Abschnitte länger als {@code max-segment} in n = ⌈Länge / max⌉ gleich lange, lückenlos aufeinanderfolgende
 *       Teile zerlegen (Teil k+1 beginnt am Ende von Teil k). Eine Aktion auf einer Teilgrenze zählt zum späteren
 *       Teil. Teile ohne Aktion: {@code firstActionTick = lastActionTick = fromTick}, 0 Aktionen.</li>
 * </ol>
 * Zählungen je Abschnitt = relevante Ereignisse darin.
 */
public final class SegmentPlanner implements SegmentPlanning {

    public static final int TICKS_PER_SECOND = 20;
    private static final long MILLIS_PER_TICK = 1000L / TICKS_PER_SECOND;

    private static final Comparator<Action> ACTION_ORDER = Comparator.comparingInt(Action::tick)
            .thenComparingInt(action -> action.type() == null ? -1 : action.type().ordinal());

    private final int preRollTicks;
    private final int postRollTicks;
    private final int mergeGapTicks;
    private final int minRelevantActions;
    private final int minSegmentTicks;
    private final int maxSegmentTicks;
    private final int relevantIdleTicks;
    private final int afkTimeoutTicks;
    private final int maxIndicatorExtensionTicks;

    public SegmentPlanner(PluginConfig.RecordingSettings recording, PluginConfig.ActivitySettings activity) {
        Objects.requireNonNull(recording, "recording");
        Objects.requireNonNull(activity, "activity");
        this.preRollTicks = toTicks(recording.preRoll());
        this.postRollTicks = toTicks(recording.postRoll());
        this.mergeGapTicks = toTicks(recording.mergeGap());
        this.minRelevantActions = Math.max(0, recording.minRelevantActions());
        this.minSegmentTicks = toTicks(recording.minSegmentLength());
        this.maxSegmentTicks = toTicks(recording.maxSegmentLength());
        this.relevantIdleTicks = toTicks(activity.relevantIdleTimeout());
        this.afkTimeoutTicks = toTicks(activity.afkTimeout());
        this.maxIndicatorExtensionTicks = toTicks(activity.maxIndicatorExtension());
    }

    /** Dauer → Ticks (abgerundet, negativ → 0, gekappt auf {@link Integer#MAX_VALUE}). */
    public static int toTicks(Duration duration) {
        if (duration == null || duration.isNegative()) {
            return 0;
        }
        long ticks = duration.toMillis() / MILLIS_PER_TICK;
        return (int) Math.min(Integer.MAX_VALUE, ticks);
    }

    @Override
    public List<SegmentPlan> plan(List<TimelineEvent> events, int endTick) {
        Objects.requireNonNull(events, "events");
        if (endTick <= 0 || events.isEmpty()) {
            return List.of();
        }
        List<Action> relevant = new ArrayList<>();
        int[] indicators = new int[events.size()];
        int indicatorCount = 0;
        for (TimelineEvent event : events) {
            if (event == null) {
                continue;
            }
            int tick = Math.max(0, Math.min(endTick, event.tick()));
            if (event.relevant()) {
                relevant.add(new Action(tick, event.type()));
            } else {
                indicators[indicatorCount++] = tick;
            }
        }
        if (relevant.isEmpty()) {
            return List.of();
        }
        relevant.sort(ACTION_ORDER);
        int[] indicatorTicks = Arrays.copyOf(indicators, indicatorCount);
        Arrays.sort(indicatorTicks);

        List<Draft> drafts = new ArrayList<>();
        int phaseStart = 0;
        for (int i = 1; i <= relevant.size(); i++) {
            if (i == relevant.size() || splitsPhase(relevant.get(i - 1).tick(), relevant.get(i).tick(),
                    indicatorTicks)) {
                drafts.add(draft(relevant.subList(phaseStart, i), endTick));
                phaseStart = i;
            }
        }

        List<SegmentPlan> plans = new ArrayList<>();
        for (Draft segment : merge(drafts)) {
            int duration = segment.to() - segment.from();
            if (segment.actions().size() < minRelevantActions || duration < minSegmentTicks || duration <= 0) {
                continue;
            }
            split(segment, plans);
        }
        return List.copyOf(plans);
    }

    /** Ob zwischen zwei aufeinanderfolgenden relevanten Aktionen eine neue Phase beginnt. */
    private boolean splitsPhase(int previous, int next, int[] indicatorTicks) {
        long gap = (long) next - previous;
        if (gap <= relevantIdleTicks) {
            return false;
        }
        if (gap > maxIndicatorExtensionTicks) {
            return true;
        }
        return !coveredByIndicators(previous, next, indicatorTicks);
    }

    /** Indikatoren in (from, to) ohne Loch &gt; afk-timeout; {@code from}/{@code to} selbst zählen als Eingabe. */
    private boolean coveredByIndicators(int from, int to, int[] indicatorTicks) {
        int index = firstIndexAfter(indicatorTicks, from);
        long previous = from;
        while (index < indicatorTicks.length && indicatorTicks[index] < to) {
            if (indicatorTicks[index] - previous > afkTimeoutTicks) {
                return false;
            }
            previous = indicatorTicks[index];
            index++;
        }
        return to - previous <= afkTimeoutTicks;
    }

    /** Erster Index mit {@code ticks[i] > value} (binäre Suche, {@code ticks} sortiert). */
    private static int firstIndexAfter(int[] ticks, int value) {
        int low = 0;
        int high = ticks.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (ticks[mid] <= value) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    private Draft draft(List<Action> actions, int endTick) {
        long from = (long) actions.get(0).tick() - preRollTicks;
        long to = (long) actions.get(actions.size() - 1).tick() + postRollTicks;
        return new Draft((int) Math.max(0L, from), (int) Math.min(endTick, to), List.copyOf(actions));
    }

    private List<Draft> merge(List<Draft> drafts) {
        List<Draft> merged = new ArrayList<>();
        Draft current = null;
        for (Draft next : drafts) {
            if (current == null) {
                current = next;
            } else if ((long) next.from() - current.to() <= mergeGapTicks) {
                List<Action> actions = new ArrayList<>(current.actions().size() + next.actions().size());
                actions.addAll(current.actions());
                actions.addAll(next.actions());
                current = new Draft(Math.min(current.from(), next.from()), Math.max(current.to(), next.to()),
                        List.copyOf(actions));
            } else {
                merged.add(current);
                current = next;
            }
        }
        if (current != null) {
            merged.add(current);
        }
        return merged;
    }

    private void split(Draft segment, List<SegmentPlan> out) {
        int duration = segment.to() - segment.from();
        if (maxSegmentTicks <= 0 || duration <= maxSegmentTicks) {
            out.add(plan(segment.from(), segment.to(), segment.actions()));
            return;
        }
        int parts = (int) (((long) duration + maxSegmentTicks - 1) / maxSegmentTicks);
        List<Action> actions = segment.actions();
        int index = 0;
        for (int part = 0; part < parts; part++) {
            int partFrom = segment.from() + (int) ((long) duration * part / parts);
            int partTo = segment.from() + (int) ((long) duration * (part + 1) / parts);
            boolean lastPart = part == parts - 1;
            int firstIndex = index;
            while (index < actions.size() && (lastPart || actions.get(index).tick() < partTo)) {
                index++;
            }
            out.add(plan(partFrom, partTo, actions.subList(firstIndex, index)));
        }
    }

    private static SegmentPlan plan(int from, int to, List<Action> actions) {
        if (actions.isEmpty()) {
            return new SegmentPlan(from, to, from, from, 0, Map.of());
        }
        Map<ActivityType, Integer> counts = new EnumMap<>(ActivityType.class);
        for (Action action : actions) {
            if (action.type() != null) {
                counts.merge(action.type(), 1, Integer::sum);
            }
        }
        return new SegmentPlan(from, to, actions.get(0).tick(), actions.get(actions.size() - 1).tick(),
                actions.size(), counts);
    }

    private record Action(int tick, @Nullable ActivityType type) {
    }

    /** Abschnitt vor dem Zerlegen; {@code actions} sortiert. */
    private record Draft(int from, int to, List<Action> actions) {
    }
}
