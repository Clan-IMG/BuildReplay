package net.techniqstone.buildReplay.command;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.techniqstone.buildReplay.session.SessionDetails;
import net.techniqstone.buildReplay.session.SessionListEntry;
import net.techniqstone.buildReplay.session.SessionListPage;
import net.techniqstone.buildReplay.storage.model.SegmentRow;
import net.techniqstone.buildReplay.storage.model.SegmentStatus;
import net.techniqstone.buildReplay.storage.model.VisitRow;

/** Testdaten für Listen und Details. */
final class SessionData {

    static final UUID STEVE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID ALEX = UUID.fromString("22222222-2222-2222-2222-222222222222");
    /** 29.10.2026 16:58 in Berlin. */
    static final Instant LOGIN = Instant.parse("2026-10-29T15:58:03Z");

    private SessionData() {
    }

    static SessionListEntry active(String ref) {
        return new SessionListEntry(ref, ref + "-cloud", 1L, LOGIN, null, 0, true, false, 3, 3, Set.of("dev"));
    }

    static SessionListEntry closed(String ref, Instant login, long seconds, int segments, int available,
                                   Set<String> servers) {
        return new SessionListEntry(ref, ref + "-cloud", 2L, login, login.plusSeconds(seconds), seconds, false, false,
                segments, available, servers);
    }

    static SessionListPage page(int page, boolean hasNext, int total, SessionListPage.Source source,
                                List<SessionListEntry> entries, List<String> warnings) {
        return new SessionListPage(STEVE, page, 8, hasNext, total, source, entries, warnings);
    }

    static SegmentRow segment(long id, Instant start, int seconds, SegmentStatus status) {
        Instant end = start.plusSeconds(seconds);
        return new SegmentRow(id, "br-test-" + id, 1L, 5L, STEVE, "Steve", "dev", "world", "FILE", null, start, end,
                start.plusSeconds(10), end.minusSeconds(10), seconds * 20, 10_000, 10_000, 42,
                Map.of("block-place", 30, "block-break", 12), status, 2048L, null, end);
    }

    static SessionDetails details(SessionListEntry summary, List<SegmentRow> segments, List<String> warnings) {
        List<VisitRow> visits = List.of(
                new VisitRow(1, "v1", 1, STEVE, "lobby-1", "i1", LOGIN, LOGIN.plusSeconds(30), null),
                new VisitRow(2, "v2", 1, STEVE, "dev", "i2", LOGIN.plusSeconds(30), null, null));
        return new SessionDetails(summary, segments, visits, warnings);
    }

    static Duration minutes(long minutes) {
        return Duration.ofMinutes(minutes);
    }
}
