package net.techniqstone.buildReplay.activity.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.techniqstone.buildReplay.activity.ActivityType;
import net.techniqstone.buildReplay.activity.SegmentPlan;
import net.techniqstone.buildReplay.activity.TimelineEvent;
import net.techniqstone.buildReplay.config.PluginConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SegmentPlannerTest {

    /** Tick 0 der Pufferaufnahme = 15:00:00 (Europe/Berlin, Winterzeit). */
    private static final Instant START = Instant.parse("2026-10-29T14:00:00Z");

    private static final SegmentPlanner DEFAULT_PLANNER =
            new SegmentPlanner(TestSupport.recording(), TestSupport.activity());

    /** Uhrzeit 15:mm:ss → Tick der Aufnahme. */
    private static int clock(int minute, int second) {
        return (minute * 60 + second) * SegmentPlanner.TICKS_PER_SECOND;
    }

    private static int seconds(double seconds) {
        return (int) Math.round(seconds * SegmentPlanner.TICKS_PER_SECOND);
    }

    private static TimelineEvent relevant(int tick, ActivityType type) {
        return new TimelineEvent(tick, START.plusMillis(tick * 50L), type, true);
    }

    private static TimelineEvent relevant(int tick) {
        return relevant(tick, ActivityType.BLOCK_PLACE);
    }

    private static TimelineEvent indicator(int tick) {
        return new TimelineEvent(tick, START.plusMillis(tick * 50L), ActivityType.MOVE, false);
    }

    /** Relevante Aktionen von {@code fromTick} bis einschließlich {@code toTick} im Abstand {@code everyTicks}. */
    private static List<TimelineEvent> building(int fromTick, int toTick, int everyTicks) {
        List<TimelineEvent> events = new ArrayList<>();
        for (int tick = fromTick; tick <= toTick; tick += everyTicks) {
            events.add(relevant(tick, (tick / everyTicks) % 4 == 0 ? ActivityType.BLOCK_BREAK : ActivityType.BLOCK_PLACE));
        }
        return events;
    }

    private static List<TimelineEvent> walking(int fromTick, int toTick, int everyTicks) {
        List<TimelineEvent> events = new ArrayList<>();
        for (int tick = fromTick; tick <= toTick; tick += everyTicks) {
            events.add(indicator(tick));
        }
        return events;
    }

    @Test
    @DisplayName("a session contains several replay segments")
    void theRequirementExampleYieldsExactlyTwoSegmentsWithPreAndPostRoll() {
        List<TimelineEvent> events = new ArrayList<>();
        // 15:00 Join, bis 15:05 nur herumlaufen
        events.addAll(walking(clock(0, 1), clock(4, 59), seconds(1)));
        // 15:05-15:15 bauen (alle 3 s), dazwischen Bewegung
        events.addAll(building(clock(5, 0), clock(15, 0), seconds(3)));
        events.addAll(walking(clock(5, 0), clock(15, 0), seconds(1)));
        // 15:15-15:20 weiter bauen (alle 4 s)
        events.addAll(building(clock(15, 4), clock(20, 0), seconds(4)));
        events.addAll(walking(clock(15, 0), clock(20, 0), seconds(1)));
        // 15:20-15:25 AFK: keine einzige Eingabe
        // 15:25-15:30 wieder bauen
        events.addAll(building(clock(25, 0), clock(30, 0), seconds(3)));
        events.addAll(walking(clock(25, 0), clock(30, 0), seconds(1)));
        int endTick = clock(31, 0);

        List<SegmentPlan> plans = DEFAULT_PLANNER.plan(events, endTick);

        assertEquals(2, plans.size(), () -> "Abschnitte: " + plans);
        SegmentPlan first = plans.get(0);
        assertEquals(clock(4, 50), first.fromTick(), "10 s Vorlauf vor 15:05");
        assertEquals(clock(20, 10), first.toTick(), "10 s Nachlauf nach 15:20");
        assertEquals(clock(5, 0), first.firstActionTick());
        assertEquals(clock(20, 0), first.lastActionTick());
        assertEquals(201 + 75, first.relevantActions(), "15:05-15:15 alle 3 s und 15:15:04-15:20 alle 4 s");

        SegmentPlan second = plans.get(1);
        assertEquals(clock(24, 50), second.fromTick());
        assertEquals(clock(30, 10), second.toTick());
        assertEquals(clock(25, 0), second.firstActionTick());
        assertEquals(clock(30, 0), second.lastActionTick());
        assertEquals(101, second.relevantActions());
        assertEquals(second.relevantActions(), second.counts().values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    @DisplayName("a player stays idle for more than 60 seconds")
    void moreThanTheRelevantIdleTimeWithoutIndicatorsStartsANewSegment() {
        List<TimelineEvent> events = new ArrayList<>();
        events.addAll(building(seconds(10), seconds(20), seconds(2)));
        // 61 s ohne jede Eingabe
        events.addAll(building(seconds(81), seconds(91), seconds(2)));

        List<SegmentPlan> plans = DEFAULT_PLANNER.plan(events, seconds(200));

        assertEquals(2, plans.size(), () -> "Abschnitte: " + plans);
        assertEquals(seconds(0), plans.get(0).fromTick());
        assertEquals(seconds(30), plans.get(0).toTick());
        assertEquals(seconds(71), plans.get(1).fromTick());
        assertEquals(seconds(101), plans.get(1).toTick());
    }

    @Test
    void aGapOfExactlyTheRelevantIdleTimeKeepsOneSegment() {
        List<TimelineEvent> events = new ArrayList<>();
        events.addAll(building(seconds(10), seconds(20), seconds(2)));
        events.addAll(building(seconds(80), seconds(90), seconds(2)));

        List<SegmentPlan> plans = DEFAULT_PLANNER.plan(events, seconds(200));

        assertEquals(1, plans.size(), () -> "Abschnitte: " + plans);
        assertEquals(seconds(0), plans.get(0).fromTick());
        assertEquals(seconds(100), plans.get(0).toTick());
        assertEquals(12, plans.get(0).relevantActions());
    }

    @Test
    @DisplayName("a player resumes building after an AFK phase")
    void buildingAfterAnAfkPhaseGetsItsOwnSegmentWithFreshPreRoll() {
        List<TimelineEvent> events = new ArrayList<>();
        events.addAll(building(seconds(30), seconds(60), seconds(3)));
        events.addAll(walking(seconds(30), seconds(65), seconds(1)));
        // AFK von 65 s bis 200 s, danach geht es weiter
        events.addAll(walking(seconds(200), seconds(240), seconds(1)));
        events.addAll(building(seconds(205), seconds(240), seconds(5)));

        List<SegmentPlan> plans = DEFAULT_PLANNER.plan(events, seconds(300));

        assertEquals(2, plans.size(), () -> "Abschnitte: " + plans);
        assertEquals(seconds(20), plans.get(0).fromTick());
        assertEquals(seconds(70), plans.get(0).toTick());
        assertEquals(seconds(195), plans.get(1).fromTick(), "Vorlauf beginnt nach der AFK-Phase");
        assertEquals(seconds(250), plans.get(1).toTick());
        assertEquals(seconds(205), plans.get(1).firstActionTick());
    }

    @Test
    void indicatorsWithoutHolesKeepOneWindowAcrossALongGap() {
        List<TimelineEvent> events = new ArrayList<>();
        events.addAll(building(seconds(10), seconds(20), seconds(2)));
        // 180 s keine relevante Aktion, aber alle 30 s Bewegung (Loch ≤ 60 s, Lücke ≤ 240 s)
        events.addAll(walking(seconds(50), seconds(190), seconds(30)));
        events.addAll(building(seconds(200), seconds(210), seconds(2)));

        List<SegmentPlan> plans = DEFAULT_PLANNER.plan(events, seconds(400));

        assertEquals(1, plans.size(), () -> "Abschnitte: " + plans);
        assertEquals(seconds(0), plans.get(0).fromTick());
        assertEquals(seconds(220), plans.get(0).toTick());
        assertEquals(12, plans.get(0).relevantActions());
    }

    @Test
    void anIndicatorHoleLongerThanTheAfkTimeoutSplitsTheWindow() {
        List<TimelineEvent> events = new ArrayList<>();
        events.addAll(building(seconds(10), seconds(20), seconds(2)));
        events.add(indicator(seconds(60)));
        // Loch 60 s → 121 s = 61 s > afk-timeout
        events.add(indicator(seconds(121)));
        events.add(indicator(seconds(160)));
        events.addAll(building(seconds(200), seconds(210), seconds(2)));

        List<SegmentPlan> plans = DEFAULT_PLANNER.plan(events, seconds(400));

        assertEquals(2, plans.size(), () -> "Abschnitte: " + plans);
    }

    @Test
    void indicatorsDoNotExtendBeyondTheMaximumIndicatorExtension() {
        List<TimelineEvent> events = new ArrayList<>();
        events.addAll(building(seconds(10), seconds(20), seconds(2)));
        events.addAll(walking(seconds(21), seconds(270), seconds(10)));
        // Lücke 20 s → 271 s = 251 s > 240 s, obwohl lückenlos Bewegung
        events.addAll(building(seconds(271), seconds(281), seconds(2)));

        List<SegmentPlan> plans = DEFAULT_PLANNER.plan(events, seconds(400));

        assertEquals(2, plans.size(), () -> "Abschnitte: " + plans);
    }

    @Test
    void theHoleBeforeTheNextRelevantActionCountsToo() {
        List<TimelineEvent> events = new ArrayList<>();
        events.addAll(building(seconds(10), seconds(20), seconds(2)));
        events.addAll(walking(seconds(30), seconds(90), seconds(10)));
        // letzte Bewegung bei 90 s, nächste Aktion erst bei 151 s → Loch 61 s
        events.addAll(building(seconds(151), seconds(161), seconds(2)));

        assertEquals(2, DEFAULT_PLANNER.plan(events, seconds(400)).size());
    }

    @Test
    void segmentsCloserThanTheMergeGapAreMerged() {
        PluginConfig.ActivitySettings shortIdle = TestSupport.activity(Duration.ofSeconds(60), Duration.ofSeconds(10),
                Duration.ofSeconds(0));
        SegmentPlanner planner = new SegmentPlanner(TestSupport.recording(), shortIdle);
        List<TimelineEvent> events = new ArrayList<>();
        events.addAll(building(seconds(20), seconds(24), seconds(2)));
        // Lücke 40 s > 10 s Leerlauf → zwei Phasen; Abschnitte [10,34] und [54,..] → Abstand genau 20 s
        events.addAll(building(seconds(64), seconds(68), seconds(2)));

        List<SegmentPlan> plans = planner.plan(events, seconds(400));

        assertEquals(1, plans.size(), () -> "Abschnitte: " + plans);
        SegmentPlan merged = plans.get(0);
        assertEquals(seconds(10), merged.fromTick());
        assertEquals(seconds(78), merged.toTick());
        assertEquals(seconds(20), merged.firstActionTick());
        assertEquals(seconds(68), merged.lastActionTick());
        assertEquals(6, merged.relevantActions());
    }

    @Test
    void segmentsFurtherApartThanTheMergeGapStaySeparate() {
        PluginConfig.ActivitySettings shortIdle = TestSupport.activity(Duration.ofSeconds(60), Duration.ofSeconds(10),
                Duration.ofSeconds(0));
        SegmentPlanner planner = new SegmentPlanner(TestSupport.recording(), shortIdle);
        List<TimelineEvent> events = new ArrayList<>();
        events.addAll(building(seconds(20), seconds(24), seconds(2)));
        // Abschnitte [10,34] und [55,..] → Abstand 21 s > 20 s
        events.addAll(building(seconds(65), seconds(69), seconds(2)));

        assertEquals(2, planner.plan(events, seconds(400)).size());
    }

    @Test
    void segmentsWithTooFewRelevantActionsAreDropped() {
        List<TimelineEvent> events = new ArrayList<>();
        events.add(relevant(seconds(10)));
        events.add(relevant(seconds(12)));
        // zweite Phase mit genau drei Aktionen bleibt
        events.addAll(building(seconds(200), seconds(204), seconds(2)));

        List<SegmentPlan> plans = DEFAULT_PLANNER.plan(events, seconds(400));

        assertEquals(1, plans.size(), () -> "Abschnitte: " + plans);
        assertEquals(seconds(200), plans.get(0).firstActionTick());
        assertEquals(3, plans.get(0).relevantActions());
    }

    @Test
    void segmentsShorterThanTheMinimumLengthAreDropped() {
        PluginConfig.RecordingSettings noRoll = TestSupport.recording(Duration.ZERO, Duration.ZERO, 3,
                Duration.ofSeconds(5), Duration.ofMinutes(30), Duration.ofSeconds(20));
        SegmentPlanner planner = new SegmentPlanner(noRoll, TestSupport.activity());
        List<TimelineEvent> tooShort = building(seconds(10), seconds(14), seconds(2));
        List<TimelineEvent> longEnough = building(seconds(10), seconds(15), seconds(2.5));

        assertEquals(List.of(), planner.plan(tooShort, seconds(400)), "4 s < 5 s Mindestlänge");
        List<SegmentPlan> kept = planner.plan(longEnough, seconds(400));
        assertEquals(1, kept.size());
        assertEquals(seconds(5), kept.get(0).durationTicks());
    }

    @Test
    void segmentsWithoutAnyLengthAreNeverPlanned() {
        PluginConfig.RecordingSettings noRoll = TestSupport.recording(Duration.ZERO, Duration.ZERO, 1,
                Duration.ZERO, Duration.ofMinutes(30), Duration.ZERO);
        SegmentPlanner planner = new SegmentPlanner(noRoll, TestSupport.activity());

        assertEquals(List.of(), planner.plan(List.of(relevant(seconds(10)), relevant(seconds(10))), seconds(400)));
    }

    @Test
    void segmentsLongerThanTheMaximumAreSplitIntoConsecutiveParts() {
        PluginConfig.RecordingSettings oneMinute = TestSupport.recording(Duration.ofSeconds(10), Duration.ofSeconds(10),
                3, Duration.ofSeconds(5), Duration.ofMinutes(1), Duration.ofSeconds(20));
        SegmentPlanner planner = new SegmentPlanner(oneMinute, TestSupport.activity());
        // Aktionen bei 10, 40, 70, 100, 130, 160 s → Abschnitt [0, 170 s] = 3400 Ticks → 3 Teile
        List<TimelineEvent> events = building(seconds(10), seconds(160), seconds(30));

        List<SegmentPlan> plans = planner.plan(events, seconds(400));

        assertEquals(3, plans.size(), () -> "Teile: " + plans);
        assertEquals(0, plans.get(0).fromTick());
        assertEquals(seconds(170), plans.get(2).toTick());
        for (int i = 0; i < plans.size(); i++) {
            assertTrue(plans.get(i).durationTicks() <= seconds(60), "Teil " + i + " zu lang");
            if (i > 0) {
                assertEquals(plans.get(i - 1).toTick(), plans.get(i).fromTick(), "Teile schließen lückenlos an");
            }
        }
        // Mindestanzahl gilt vor dem Zerlegen: jeder Teil hat nur 2 Aktionen und bleibt trotzdem
        assertEquals(List.of(2, 2, 2), plans.stream().map(SegmentPlan::relevantActions).toList());
        assertEquals(6, plans.stream().mapToInt(SegmentPlan::relevantActions).sum());
    }

    @Test
    void partsWithoutRelevantActionsStartAtTheirOwnFromTick() {
        PluginConfig.RecordingSettings oneMinute = TestSupport.recording(Duration.ofSeconds(10), Duration.ofSeconds(10),
                3, Duration.ofSeconds(5), Duration.ofMinutes(1), Duration.ofSeconds(20));
        SegmentPlanner planner = new SegmentPlanner(oneMinute, TestSupport.activity());
        List<TimelineEvent> events = new ArrayList<>();
        events.addAll(building(seconds(10), seconds(14), seconds(2)));
        events.addAll(walking(seconds(30), seconds(230), seconds(30)));
        events.addAll(building(seconds(240), seconds(242), seconds(2)));

        List<SegmentPlan> plans = planner.plan(events, seconds(400));

        // Abschnitt [0, 252 s] = 5040 Ticks → 5 Teile à 1008 Ticks
        assertEquals(5, plans.size(), () -> "Teile: " + plans);
        assertEquals(3, plans.get(0).relevantActions());
        for (int i = 1; i <= 3; i++) {
            SegmentPlan empty = plans.get(i);
            assertEquals(0, empty.relevantActions());
            assertEquals(empty.fromTick(), empty.firstActionTick());
            assertEquals(empty.fromTick(), empty.lastActionTick());
            assertEquals(Map.of(), empty.counts());
        }
        assertEquals(2, plans.get(4).relevantActions());
        assertEquals(seconds(240), plans.get(4).firstActionTick());
    }

    @Test
    void aSegmentOfExactlyTheMaximumLengthIsNotSplit() {
        PluginConfig.RecordingSettings oneMinute = TestSupport.recording(Duration.ofSeconds(10), Duration.ofSeconds(10),
                3, Duration.ofSeconds(5), Duration.ofMinutes(1), Duration.ofSeconds(20));
        SegmentPlanner planner = new SegmentPlanner(oneMinute, TestSupport.activity());
        List<TimelineEvent> events = building(seconds(10), seconds(50), seconds(20));

        List<SegmentPlan> plans = planner.plan(events, seconds(400));

        assertEquals(1, plans.size());
        assertEquals(seconds(60), plans.get(0).durationTicks());
    }

    @Test
    void preRollIsClampedAtTickZeroAndPostRollAtTheEndTick() {
        List<TimelineEvent> events = new ArrayList<>();
        events.addAll(building(seconds(1), seconds(3), seconds(1)));
        events.addAll(building(seconds(200), seconds(204), seconds(2)));
        int endTick = seconds(206);

        List<SegmentPlan> plans = DEFAULT_PLANNER.plan(events, endTick);

        assertEquals(2, plans.size());
        assertEquals(0, plans.get(0).fromTick());
        assertEquals(seconds(13), plans.get(0).toTick());
        assertEquals(seconds(190), plans.get(1).fromTick());
        assertEquals(endTick, plans.get(1).toTick());
    }

    @Test
    void eventsOutsideTheRecordingAreClampedIntoIt() {
        List<TimelineEvent> events = List.of(relevant(-40), relevant(10), relevant(20),
                relevant(seconds(500)), relevant(seconds(501)), relevant(seconds(502)));
        int endTick = seconds(300);

        List<SegmentPlan> plans = DEFAULT_PLANNER.plan(events, endTick);

        assertEquals(2, plans.size(), () -> "Abschnitte: " + plans);
        assertEquals(0, plans.get(0).fromTick());
        assertEquals(0, plans.get(0).firstActionTick(), "negativer Tick → 0");
        assertEquals(endTick - seconds(10), plans.get(1).fromTick());
        assertEquals(endTick, plans.get(1).firstActionTick(), "Ticks nach dem Ende → endTick");
        assertEquals(endTick, plans.get(1).toTick());
        assertEquals(3, plans.get(1).relevantActions());
    }

    @Test
    void unsortedInputAndDuplicateTicksGiveTheSameResultAsSortedInput() {
        List<TimelineEvent> sorted = new ArrayList<>();
        sorted.addAll(building(seconds(10), seconds(40), seconds(2)));
        sorted.add(relevant(seconds(20), ActivityType.WORLDEDIT));
        sorted.add(relevant(seconds(20), ActivityType.WORLDEDIT));
        sorted.addAll(walking(seconds(41), seconds(150), seconds(5)));
        sorted.addAll(building(seconds(160), seconds(170), seconds(2)));
        sorted.addAll(building(seconds(400), seconds(420), seconds(2)));
        List<TimelineEvent> shuffled = new ArrayList<>(sorted);
        Collections.shuffle(shuffled, new Random(42));

        List<SegmentPlan> expected = DEFAULT_PLANNER.plan(sorted, seconds(500));
        List<SegmentPlan> actual = DEFAULT_PLANNER.plan(shuffled, seconds(500));

        assertEquals(expected, actual);
        assertEquals(expected, DEFAULT_PLANNER.plan(sorted, seconds(500)), "deterministisch");
        assertEquals(2, actual.size());
        assertEquals(16 + 2 + 6, actual.get(0).relevantActions(), "doppelte Ticks zählen einzeln");
        assertEquals(2, actual.get(0).counts().get(ActivityType.WORLDEDIT));
    }

    @Test
    void countsPerTypeSumUpToTheRelevantActions() {
        List<TimelineEvent> events = List.of(
                relevant(seconds(10), ActivityType.BLOCK_PLACE),
                relevant(seconds(11), ActivityType.BLOCK_PLACE),
                relevant(seconds(12), ActivityType.BLOCK_BREAK),
                relevant(seconds(13), ActivityType.SIGN_EDIT),
                indicator(seconds(14)));

        List<SegmentPlan> plans = DEFAULT_PLANNER.plan(events, seconds(100));

        assertEquals(1, plans.size());
        assertEquals(Map.of(ActivityType.BLOCK_PLACE, 2, ActivityType.BLOCK_BREAK, 1, ActivityType.SIGN_EDIT, 1),
                plans.get(0).counts());
        assertEquals(4, plans.get(0).relevantActions());
    }

    @Test
    void timelinesWithoutRelevantActionsOrLengthProduceNoSegments() {
        assertEquals(List.of(), DEFAULT_PLANNER.plan(List.of(), seconds(100)));
        assertEquals(List.of(), DEFAULT_PLANNER.plan(walking(0, seconds(100), seconds(1)), seconds(100)));
        assertEquals(List.of(), DEFAULT_PLANNER.plan(building(0, 40, 10), 0));
    }

    @Test
    void durationsAreConvertedWithTwentyTicksPerSecond() {
        assertEquals(200, SegmentPlanner.toTicks(Duration.ofSeconds(10)));
        assertEquals(36_000, SegmentPlanner.toTicks(Duration.ofMinutes(30)));
        assertEquals(1, SegmentPlanner.toTicks(Duration.ofMillis(99)));
        assertEquals(0, SegmentPlanner.toTicks(Duration.ofSeconds(-5)));
    }
}
