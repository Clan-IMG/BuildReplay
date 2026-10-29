package net.techniqstone.buildReplay.activity;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Zeitachse einer Pufferaufnahme: relevante Aktionen, Indikatoren (gedrosselt) und regelmäßige Tick/Zeit-Proben.
 * Wird vom Hauptthread befüllt; {@link #snapshot()} liefert eine unveränderliche Kopie für die asynchrone
 * Abschnittsberechnung.
 */
public final class ActivityTimeline {

    private final Instant startedAt;
    private final List<TimelineEvent> events = new ArrayList<>();
    private final List<ClockSample> samples = new ArrayList<>();
    private int relevantCount;

    public ActivityTimeline(Instant startedAt) {
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.samples.add(new ClockSample(0, startedAt));
    }

    public synchronized void append(TimelineEvent event) {
        events.add(event);
        if (event.relevant()) {
            relevantCount++;
        }
    }

    public synchronized void sample(int tick, Instant at) {
        if (tick < 0) {
            return;
        }
        ClockSample last = samples.get(samples.size() - 1);
        if (tick > last.tick()) {
            samples.add(new ClockSample(tick, at));
        }
    }

    public synchronized int relevantCount() {
        return relevantCount;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(startedAt, List.copyOf(events), List.copyOf(samples));
    }

    /** Unveränderliche Kopie der Zeitachse. */
    public record Snapshot(Instant startedAt, List<TimelineEvent> events, List<ClockSample> samples) {

        /** Uhrzeit eines Ticks, linear zwischen den Proben interpoliert (danach mit 20 Ticks/s extrapoliert). */
        public Instant timeOfTick(int tick) {
            ClockSample before = samples.get(0);
            ClockSample after = null;
            for (ClockSample sample : samples) {
                if (sample.tick() <= tick) {
                    before = sample;
                } else {
                    after = sample;
                    break;
                }
            }
            if (after == null || after.tick() == before.tick()) {
                return before.at().plus(Duration.ofMillis((tick - before.tick()) * 50L));
            }
            double fraction = (double) (tick - before.tick()) / (after.tick() - before.tick());
            long span = Duration.between(before.at(), after.at()).toMillis();
            return before.at().plusMillis(Math.round(span * fraction));
        }
    }
}
