package net.techniqstone.buildReplay.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.techniqstone.buildReplay.config.PluginConfig;
import net.techniqstone.buildReplay.storage.model.VisitRow;
import org.junit.jupiter.api.Test;

class FormattersTest {

    private final Formatters berlin = Formatters.of(PluginConfig.defaults().display());

    @Test
    void sessionDurationsLookLikeTheSimpleCloudDashboard() {
        assertEquals("<1 min", Formatters.sessionDuration(0));
        assertEquals("<1 min", Formatters.sessionDuration(59));
        assertEquals("1 min", Formatters.sessionDuration(60));
        assertEquals("14 min", Formatters.sessionDuration(871));
        assertEquals("59 min", Formatters.sessionDuration(3_599));
        assertEquals("1 h 00 min", Formatters.sessionDuration(3_600));
        assertEquals("1 h 05 min", Formatters.sessionDuration(3_900));
        assertEquals("25 h 00 min", Formatters.sessionDuration(90_000));
        assertEquals("<1 min", Formatters.sessionDuration(-5));
    }

    @Test
    void clipDurationsAreExactToTheSecond() {
        assertEquals("0 s", Formatters.clipDuration(0));
        assertEquals("45 s", Formatters.clipDuration(45));
        assertEquals("4 min 05 s", Formatters.clipDuration(245));
        assertEquals("1 h 05 min", Formatters.clipDuration(3_930));
        assertEquals("10 s", Formatters.roll(10_000));
        assertEquals("1,5 s", Formatters.roll(1_500));
    }

    @Test
    void datesUseTheConfiguredTimeZoneIncludingDaylightSavingTime() {
        // 29.10.2026: Winterzeit (UTC+1)
        assertEquals("29.10.2026 16:58", berlin.dateTime(Instant.parse("2026-10-29T15:58:03.154906Z")));
        // 01.07.2026: Sommerzeit (UTC+2)
        assertEquals("01.07.2026 14:30", berlin.dateTime(Instant.parse("2026-07-01T12:30:00Z")));
        assertEquals("–", berlin.dateTime(null));
        assertEquals("16:58:03", berlin.time(Instant.parse("2026-10-29T15:58:03Z")));
    }

    @Test
    void aCustomPatternAndZoneAreHonoured() {
        Formatters newYork = new Formatters(ZoneId.of("America/New_York"), "yyyy-MM-dd HH:mm");
        assertEquals("2026-10-29 11:58", newYork.dateTime(Instant.parse("2026-10-29T15:58:00Z")));
    }

    @Test
    void segmentTimesShowTheDayOnlyWhenItDiffersFromTheSession() {
        Instant login = Instant.parse("2026-10-29T22:30:00Z"); // 23:30 in Berlin
        assertEquals("23:45:00", berlin.timeOnDay(Instant.parse("2026-10-29T22:45:00Z"), login));
        assertEquals("30.10. 00:10:00", berlin.timeOnDay(Instant.parse("2026-10-29T23:10:00Z"), login));
        assertTrue(berlin.sameDay(login, Instant.parse("2026-10-29T22:59:59Z")));
        assertFalse(berlin.sameDay(login, Instant.parse("2026-10-29T23:00:00Z")));
    }

    @Test
    void actionSummariesAreGermanAndSortedByFrequency() {
        Map<String, Integer> summary = new LinkedHashMap<>();
        summary.put("block-break", 12);
        summary.put("block-place", 30);
        summary.put("worldedit", 2);
        summary.put("custom-thing", 1);
        summary.put("bucket", 0);
        assertEquals("30× Blöcke gesetzt, 12× Blöcke abgebaut, 2× WorldEdit, 1× custom-thing",
                Formatters.actionSummary(summary));
        assertEquals("Blöcke gesetzt", Formatters.actionLabel("BLOCK_PLACE"));
        assertEquals("–", Formatters.actionSummary(Map.of()));
    }

    @Test
    void serversAndRoutesAreListedReadably() {
        assertEquals("build, dev, lobby-1", Formatters.servers(Set.of("dev", "lobby-1", "build")));
        assertEquals("–", Formatters.servers(Set.of()));
        UUID player = UUID.randomUUID();
        List<VisitRow> visits = List.of(
                new VisitRow(3, "c", 1, player, "dev", "i", Instant.parse("2026-10-29T16:30:00Z"), null, null),
                new VisitRow(1, "a", 1, player, "lobby-1", "i", Instant.parse("2026-10-29T16:00:00Z"),
                        Instant.parse("2026-10-29T16:01:00Z"), null),
                new VisitRow(2, "b", 1, player, "dev", "i", Instant.parse("2026-10-29T16:01:00Z"),
                        Instant.parse("2026-10-29T16:20:00Z"), null),
                new VisitRow(4, "d", 1, player, "dev", "i", Instant.parse("2026-10-29T16:20:00Z"), null, null));
        assertEquals("lobby-1 → dev", Formatters.route(visits.subList(1, 3)));
        assertEquals("lobby-1 → dev", Formatters.route(visits));
    }

    @Test
    void userInputCannotInjectColourCodes() {
        assertEquals("＆cRot", Formatters.plain("&cRot"));
        assertEquals("?4x", Formatters.plain("§4x"));
        assertEquals("", Formatters.plain(null));
    }
}
