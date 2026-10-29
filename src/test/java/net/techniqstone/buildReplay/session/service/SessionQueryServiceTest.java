package net.techniqstone.buildReplay.session.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import net.techniqstone.buildReplay.config.PluginConfig;
import net.techniqstone.buildReplay.session.AmbiguousReferenceException;
import net.techniqstone.buildReplay.session.NetworkSession;
import net.techniqstone.buildReplay.session.SessionDetails;
import net.techniqstone.buildReplay.session.SessionListEntry;
import net.techniqstone.buildReplay.session.SessionListPage;
import net.techniqstone.buildReplay.simplecloud.CloudSession;
import net.techniqstone.buildReplay.simplecloud.SimpleCloudException;
import net.techniqstone.buildReplay.storage.StorageUnavailableException;
import net.techniqstone.buildReplay.storage.model.SegmentStatus;
import net.techniqstone.buildReplay.storage.model.SessionRow;
import net.techniqstone.buildReplay.storage.model.SessionSource;
import net.techniqstone.buildReplay.storage.model.SessionStatus;
import net.techniqstone.buildReplay.storage.model.VisitStart;
import org.junit.jupiter.api.Test;

/** (14) Sitzungsliste der letzten 14 Tage und Sitzungsdetails. */
class SessionQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-29T12:00:00Z");

    private final MutableClock clock = new MutableClock(NOW);
    private final TestTasks tasks = TestTasks.deterministic(new ManualScheduler(clock));
    private final FakeSimpleCloudClient cloud = new FakeSimpleCloudClient();
    private final FakeReplayStore store = new FakeReplayStore(clock);
    private final UUID player = UUID.randomUUID();

    /** page-size 3, max-sessions 7, history-days 14. */
    private SessionQueryService service() {
        return service(TestConfigs.config(Map.of("display.page-size", 3, "display.max-sessions", 7,
                "display.history-days", 14)));
    }

    private SessionQueryService service(PluginConfig config) {
        return new SessionQueryService(config, tasks, clock, cloud, store, TestConfigs.quietLogger("query"));
    }

    /** {@code count} beendete Sitzungen im Fenster, stündlich zurück, je 30 Minuten lang (neueste zuerst). */
    private List<CloudSession> sessionsInWindow(int count) {
        List<CloudSession> created = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Instant login = NOW.minus(Duration.ofHours(i + 1));
            created.add(cloud.addClosed(player, login, login.plus(Duration.ofMinutes(30))));
        }
        return created;
    }

    private void sessionsBeforeWindow(int count) {
        for (int i = 0; i < count; i++) {
            Instant login = NOW.minus(Duration.ofDays(15)).minus(Duration.ofHours(i));
            cloud.addClosed(player, login, login.plus(Duration.ofMinutes(30)));
        }
    }

    private static List<String> refs(SessionListPage page) {
        return page.entries().stream().map(SessionListEntry::ref).toList();
    }

    private static List<String> refsOf(List<CloudSession> sessions) {
        return sessions.stream().map(session -> NetworkSession.shortRef(session.id())).toList();
    }

    // ------------------------------------------------------------------ Liste

    @Test
    void listingPagesThroughTheLastFourteenDays() {
        List<CloudSession> recent = sessionsInWindow(5);
        sessionsBeforeWindow(3);
        SessionQueryService queries = service();

        SessionListPage first = queries.listSessions(player, 0).join();
        assertEquals(SessionListPage.Source.SIMPLECLOUD, first.source());
        assertEquals(refsOf(recent.subList(0, 3)), refs(first));
        assertTrue(first.hasNext());
        assertEquals(5, first.totalInWindow());
        assertTrue(first.warnings().isEmpty());

        SessionListPage second = queries.listSessions(player, 1).join();
        assertEquals(refsOf(recent.subList(3, 5)), refs(second));
        assertFalse(second.hasNext(), "das zusätzliche Element liegt vor dem Fenster");
        assertEquals(5, second.totalInWindow());

        SessionListEntry entry = second.entries().getFirst();
        assertEquals(recent.get(3).id(), entry.cloudSessionId());
        assertEquals(1800L, entry.durationSeconds());
        assertFalse(entry.active());
        assertFalse(entry.provisional());
    }

    @Test
    void activeSessionsAndSessionsEndingInsideTheWindowAreListed() {
        CloudSession active = cloud.login(player, "Builder", NOW.minus(Duration.ofMinutes(20)));
        // vor dem Fensterbeginn eingeloggt, aber erst danach ausgeloggt → gehört dazu
        CloudSession straddling = cloud.addClosed(player, NOW.minus(Duration.ofDays(14)).minus(Duration.ofHours(2)),
                NOW.minus(Duration.ofDays(14)).plus(Duration.ofHours(1)));
        cloud.addClosed(player, NOW.minus(Duration.ofDays(14)).minus(Duration.ofHours(5)),
                NOW.minus(Duration.ofDays(14)).minus(Duration.ofHours(4)));

        SessionListPage page = service().listSessions(player, 0).join();

        assertEquals(refsOf(List.of(active, straddling)), refs(page));
        assertEquals(2, page.totalInWindow());
        assertFalse(page.hasNext());
        SessionListEntry first = page.entries().getFirst();
        assertTrue(first.active());
        assertNull(first.logoutTime());
        assertEquals(20 * 60L, first.durationSeconds());
    }

    @Test
    void listingIsCappedAtMaxSessions() {
        List<CloudSession> recent = sessionsInWindow(12);
        SessionQueryService queries = service();

        SessionListPage first = queries.listSessions(player, 0).join();
        assertEquals(7, first.totalInWindow());
        assertTrue(first.hasNext());

        SessionListPage second = queries.listSessions(player, 1).join();
        assertEquals(refsOf(recent.subList(3, 6)), refs(second));
        assertTrue(second.hasNext());

        SessionListPage third = queries.listSessions(player, 2).join();
        assertEquals(refsOf(recent.subList(6, 7)), refs(third));
        assertFalse(third.hasNext(), "die 8. Sitzung liegt hinter display.max-sessions");
        assertEquals(7, third.totalInWindow());

        SessionListPage beyond = queries.listSessions(player, 3).join();
        assertTrue(beyond.entries().isEmpty());
        assertFalse(beyond.hasNext());
    }

    @Test
    void listingEnrichesSessionsWithSegmentStatsFromTheDatabase() {
        List<CloudSession> recent = sessionsInWindow(2);
        CloudSession withSegments = recent.getFirst();
        SessionRow row = store.putSession(withSegments.id(), withSegments.id(), player, SessionSource.SIMPLECLOUD,
                SessionStatus.CLOSED, withSegments.loginTime(), withSegments.logoutTime());
        store.addSegment(withSegments.id(), "dev", SegmentStatus.AVAILABLE);
        store.addSegment(withSegments.id(), "build", SegmentStatus.MISSING);

        SessionListPage page = service().listSessions(player, 0).join();

        SessionListEntry entry = page.entries().getFirst();
        assertEquals(row.id(), entry.dbSessionId());
        assertEquals(2, entry.segments());
        assertEquals(1, entry.availableSegments());
        assertEquals(Set.of("dev", "build"), entry.servers());
        SessionListEntry without = page.entries().get(1);
        assertNull(without.dbSessionId());
        assertEquals(0, without.segments());
    }

    @Test
    void firstPageIncludesProvisionalDatabaseSessionsOfTheWindow() {
        List<CloudSession> recent = sessionsInWindow(4);
        String provisionalKey = NetworkSession.newProvisionalKey();
        store.putSession(provisionalKey, null, player, SessionSource.PROVISIONAL, SessionStatus.CLOSED,
                NOW.minus(Duration.ofMinutes(150)), NOW.minus(Duration.ofMinutes(140)));
        store.addSegment(provisionalKey, "dev", SegmentStatus.AVAILABLE);
        SessionQueryService queries = service();

        SessionListPage first = queries.listSessions(player, 0).join();
        assertEquals(List.of(NetworkSession.shortRef(recent.get(0).id()), NetworkSession.shortRef(recent.get(1).id()),
                NetworkSession.shortRef(provisionalKey), NetworkSession.shortRef(recent.get(2).id())), refs(first));
        SessionListEntry provisional = first.entries().get(2);
        assertTrue(provisional.provisional());
        assertTrue(provisional.ref().startsWith("p"));
        assertEquals(1, provisional.segments());
        assertEquals(5, first.totalInWindow());

        SessionListPage second = queries.listSessions(player, 1).join();
        assertTrue(second.entries().stream().noneMatch(SessionListEntry::provisional));
    }

    @Test
    void listingFallsBackToTheDatabaseWhenSimpleCloudFails() {
        CloudSession cloudSession = sessionsInWindow(1).getFirst();
        store.putSession(cloudSession.id(), cloudSession.id(), player, SessionSource.SIMPLECLOUD, SessionStatus.CLOSED,
                cloudSession.loginTime(), cloudSession.logoutTime());
        store.addSegment(cloudSession.id(), "dev", SegmentStatus.AVAILABLE);
        String provisionalKey = NetworkSession.newProvisionalKey();
        store.putSession(provisionalKey, null, player, SessionSource.PROVISIONAL, SessionStatus.ACTIVE,
                NOW.minus(Duration.ofMinutes(5)), null);
        cloud.setFailing(true);

        SessionListPage page = service().listSessions(player, 0).join();

        assertEquals(SessionListPage.Source.DATABASE, page.source());
        assertEquals(List.of(SessionQueryService.WARN_CLOUD_UNAVAILABLE), page.warnings());
        assertEquals(List.of(NetworkSession.shortRef(provisionalKey), NetworkSession.shortRef(cloudSession.id())),
                refs(page));
        assertEquals(2, page.totalInWindow());
        assertTrue(page.entries().getFirst().active());
        assertTrue(page.entries().getFirst().provisional());
        assertEquals(1, page.entries().get(1).segments());
        assertEquals(1800L, page.entries().get(1).durationSeconds());
    }

    @Test
    void listingIsSimpleCloudOnlyWhenTheDatabaseIsDown() {
        sessionsInWindow(2);
        store.setAvailable(false);

        SessionListPage page = service().listSessions(player, 0).join();

        assertEquals(SessionListPage.Source.SIMPLECLOUD_ONLY, page.source());
        assertEquals(List.of(SessionQueryService.WARN_SEGMENTS_UNAVAILABLE), page.warnings());
        assertEquals(2, page.entries().size());
        assertTrue(page.entries().stream().allMatch(entry -> entry.segments() == 0 && entry.dbSessionId() == null));
    }

    @Test
    void listingFailsWithTheStorageCauseWhenSimpleCloudAndDatabaseAreDown() {
        cloud.setFailing(true);
        store.setAvailable(false);

        CompletionException failure = assertThrows(CompletionException.class,
                () -> service().listSessions(player, 0).join());

        StorageUnavailableException cause = assertInstanceOf(StorageUnavailableException.class, failure.getCause());
        assertTrue(Arrays.stream(cause.getSuppressed()).anyMatch(SimpleCloudException.class::isInstance),
                "SimpleCloud-Fehler bleibt erhalten");
    }

    @Test
    void listingWithoutSimpleCloudConfigurationUsesTheDatabase() {
        cloud.setConfigured(false);

        SessionListPage page = service().listSessions(player, 0).join();

        assertEquals(SessionListPage.Source.DATABASE, page.source());
        assertEquals(List.of(SessionQueryService.WARN_CLOUD_NOT_CONFIGURED), page.warnings());
        assertEquals(0, cloud.totalCalls());
    }

    @Test
    void listingPagesAreCachedForTheConfiguredTtl() {
        sessionsInWindow(2);
        SessionQueryService queries = service();

        queries.listSessions(player, 0).join();
        queries.listSessions(player, 0).join();
        assertEquals(1, cloud.listCalls());

        clock.advance(Duration.ofSeconds(31)); // simplecloud.session-list-cache-seconds = 30
        queries.listSessions(player, 0).join();
        assertEquals(2, cloud.listCalls());

        queries.invalidate(player);
        queries.listSessions(player, 0).join();
        assertEquals(3, cloud.listCalls());
    }

    @Test
    void failedSimpleCloudPagesAreNotCached() {
        sessionsInWindow(1);
        SessionQueryService queries = service();
        cloud.setFailing(true);
        assertEquals(SessionListPage.Source.DATABASE, queries.listSessions(player, 0).join().source());

        cloud.setFailing(false);
        assertEquals(SessionListPage.Source.SIMPLECLOUD, queries.listSessions(player, 0).join().source());
    }

    // ------------------------------------------------------------------ Details

    @Test
    void detailsOfAStoredSessionContainSegmentsAndVisits() {
        CloudSession cloudSession = sessionsInWindow(1).getFirst();
        SessionRow row = store.putSession(cloudSession.id(), cloudSession.id(), player, SessionSource.SIMPLECLOUD,
                SessionStatus.CLOSED, cloudSession.loginTime(), cloudSession.logoutTime());
        store.addSegment(cloudSession.id(), "dev", SegmentStatus.AVAILABLE);
        store.startVisit(new VisitStart("dev-1:" + player + ":1", cloudSession.id(), player, "Builder", "dev", "dev-1",
                cloudSession.loginTime()));
        store.startVisit(new VisitStart("build-1:" + player + ":2", cloudSession.id(), player, "Builder", "build",
                "build-1", cloudSession.loginTime().plusSeconds(60)));

        SessionDetails details = service().details(player, NetworkSession.shortRef(cloudSession.id()).toUpperCase())
                .join().orElseThrow();

        assertEquals(row.id(), details.summary().dbSessionId());
        assertEquals(1, details.segments().size());
        assertEquals(2, details.visits().size());
        assertEquals(Set.of("dev", "build"), details.summary().servers());
        assertEquals(1, details.summary().availableSegments());
        assertEquals(cloudSession.logoutTime(), details.summary().logoutTime());
        assertTrue(details.warnings().isEmpty());
        assertEquals(0, cloud.totalCalls(), "geschlossene Sitzung braucht keine SimpleCloud-Abfrage");
    }

    @Test
    void detailsOfAnActiveRowTakeTheLogoutFromSimpleCloud() {
        CloudSession cloudSession = sessionsInWindow(1).getFirst();
        store.putSession(cloudSession.id(), cloudSession.id(), player, SessionSource.SIMPLECLOUD, SessionStatus.ACTIVE,
                cloudSession.loginTime(), null);

        SessionDetails details = service().details(player, NetworkSession.shortRef(cloudSession.id())).join()
                .orElseThrow();

        assertFalse(details.summary().active());
        assertEquals(cloudSession.logoutTime(), details.summary().logoutTime());
        assertEquals(1800L, details.summary().durationSeconds());
        SessionRow row = store.session(cloudSession.id()).orElseThrow();
        assertEquals(SessionStatus.CLOSED, row.status(), "Logout wird nachgetragen");
        assertTrue(details.warnings().contains(SessionQueryService.WARN_NO_SEGMENTS));
    }

    @Test
    void ambiguousReferenceFailsWithLongerCandidates() {
        String a = "59dc83e5-987b-4adc-85d7-2aaeaf7ddb53";
        String b = "59dc83e5-1234-4adc-85d7-2aaeaf7ddb53";
        store.putSession(a, a, player, SessionSource.SIMPLECLOUD, SessionStatus.CLOSED, NOW.minusSeconds(7200),
                NOW.minusSeconds(3600));
        store.putSession(b, b, player, SessionSource.SIMPLECLOUD, SessionStatus.CLOSED, NOW.minusSeconds(4000),
                NOW.minusSeconds(3800));
        SessionQueryService queries = service();

        CompletionException failure = assertThrows(CompletionException.class,
                () -> queries.details(player, "59dc83e5").join());

        AmbiguousReferenceException ambiguous = assertInstanceOf(AmbiguousReferenceException.class, failure.getCause());
        assertEquals(Set.of("59dc83e5-9", "59dc83e5-1"), Set.copyOf(ambiguous.candidates()));
        SessionDetails resolved = queries.details(player, "59dc83e5-9").join().orElseThrow();
        assertEquals(a, resolved.summary().cloudSessionId());
    }

    @Test
    void unknownReferenceIsLookedUpInSimpleCloudWithoutSegments() {
        CloudSession cloudSession = sessionsInWindow(3).get(1);

        Optional<SessionDetails> details = service().details(player, NetworkSession.shortRef(cloudSession.id())).join();

        SessionDetails found = details.orElseThrow();
        assertEquals(cloudSession.id(), found.summary().cloudSessionId());
        assertNull(found.summary().dbSessionId());
        assertTrue(found.segments().isEmpty());
        assertTrue(found.visits().isEmpty());
        assertEquals(List.of(SessionQueryService.WARN_NO_SEGMENTS), found.warnings());
    }

    @Test
    void detailsFallBackToSimpleCloudWhenTheDatabaseIsDown() {
        CloudSession cloudSession = sessionsInWindow(1).getFirst();
        store.setAvailable(false);

        SessionDetails found = service().details(player, NetworkSession.shortRef(cloudSession.id())).join()
                .orElseThrow();

        assertEquals(cloudSession.id(), found.summary().cloudSessionId());
        assertEquals(List.of(SessionQueryService.WARN_SEGMENTS_UNAVAILABLE), found.warnings());
    }

    @Test
    void referenceUnknownEverywhereIsEmpty() {
        sessionsInWindow(2);
        SessionQueryService queries = service();

        assertTrue(queries.details(player, "ffffffff").join().isEmpty());
        assertTrue(queries.details(player, "pabcdef1").join().isEmpty());
        assertTrue(queries.details(player, "  ").join().isEmpty());
    }

    @Test
    void provisionalReferenceResolvesTheStoredProvisionalSession() {
        String provisionalKey = NetworkSession.newProvisionalKey();
        store.putSession(provisionalKey, null, player, SessionSource.PROVISIONAL, SessionStatus.ACTIVE,
                NOW.minusSeconds(600), null);
        store.addSegment(provisionalKey, "dev", SegmentStatus.AVAILABLE);

        SessionDetails details = service().details(player, NetworkSession.shortRef(provisionalKey)).join()
                .orElseThrow();

        assertTrue(details.summary().provisional());
        assertTrue(details.summary().active());
        assertEquals(List.of(SessionQueryService.WARN_PROVISIONAL), details.warnings());
        assertEquals(0, cloud.totalCalls());
    }
}
