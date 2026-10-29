package net.techniqstone.buildReplay.session.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.techniqstone.buildReplay.config.PluginConfig;
import net.techniqstone.buildReplay.core.TaskRunner;
import net.techniqstone.buildReplay.session.AmbiguousReferenceException;
import net.techniqstone.buildReplay.session.NetworkSession;
import net.techniqstone.buildReplay.session.SessionDetails;
import net.techniqstone.buildReplay.session.SessionListEntry;
import net.techniqstone.buildReplay.session.SessionListPage;
import net.techniqstone.buildReplay.session.SessionQueries;
import net.techniqstone.buildReplay.simplecloud.CloudSession;
import net.techniqstone.buildReplay.simplecloud.CloudSessionPage;
import net.techniqstone.buildReplay.simplecloud.SimpleCloudClient;
import net.techniqstone.buildReplay.storage.ReplayStore;
import net.techniqstone.buildReplay.storage.StorageUnavailableException;
import net.techniqstone.buildReplay.storage.model.SegmentRow;
import net.techniqstone.buildReplay.storage.model.SessionRow;
import net.techniqstone.buildReplay.storage.model.SessionSource;
import net.techniqstone.buildReplay.storage.model.SessionStats;
import net.techniqstone.buildReplay.storage.model.SessionStatus;
import net.techniqstone.buildReplay.storage.model.VisitRow;
import org.jspecify.annotations.Nullable;

/**
 * Sitzungsliste und -details für die Befehle.
 *
 * <p><b>Liste</b>: maßgeblich ist SimpleCloud (Login, Logout, Dauer). Pro Seite ein Aufruf mit
 * {@code offset = page * pageSize} und {@code limit = pageSize + 1}; das zusätzliche Element zeigt, ob es eine
 * nächste Seite im Zeitfenster ({@code display.history-days}) gibt. Eine Sitzung gehört zum Fenster, wenn sie aktiv
 * ist oder nach dem Fensterbeginn endete; höchstens {@code display.max-sessions} Sitzungen. MariaDB ergänzt die
 * Abschnittsdaten und auf Seite 0 die vorläufigen Sitzungen. Fällt SimpleCloud aus, kommt die Liste aus MariaDB
 * ({@link SessionListPage.Source#DATABASE}); fällt MariaDB aus, fehlen nur die Abschnittsdaten
 * ({@link SessionListPage.Source#SIMPLECLOUD_ONLY}); fallen beide aus, schlägt das Future fehl (Ursache:
 * {@link StorageUnavailableException}, der SimpleCloud-Fehler hängt als "suppressed" daran).
 *
 * <p>{@code totalInWindow} zählt SimpleCloud-Sitzungen im Fenster (genau, notfalls über zusätzliche, ebenfalls
 * zwischengespeicherte Seiten zu je 100) plus vorläufige Sitzungen aus MariaDB. Zum Blättern {@code hasNext}
 * verwenden.
 *
 * <p>Ergebnisse kommen auf einem I/O-Thread (oder dem Thread des SimpleCloud-Clients) zurück.
 */
public final class SessionQueryService implements SessionQueries {

    static final String WARN_SEGMENTS_UNAVAILABLE = "Abschnittsdaten derzeit nicht verfügbar";
    static final String WARN_CLOUD_UNAVAILABLE =
            "SimpleCloud derzeit nicht erreichbar - Sitzungen aus der Datenbank (Login/Logout können abweichen)";
    static final String WARN_CLOUD_NOT_CONFIGURED = "SimpleCloud nicht konfiguriert - Sitzungen aus der Datenbank";
    static final String WARN_NO_SEGMENTS = "Zu dieser Sitzung wurden keine Abschnitte gespeichert";
    static final String WARN_PROVISIONAL = "Vorläufige Sitzung - SimpleCloud war beim Betreten nicht erreichbar";
    static final String WARN_LOGOUT_NOT_REFRESHED = "Aktueller Stand von SimpleCloud nicht abrufbar - Logout ggf. veraltet";
    static final int CLOUD_MAX_LIMIT = 100;
    static final int DETAIL_HISTORY_LIMIT = 10;

    private static final Comparator<SessionListEntry> NEWEST_FIRST =
            Comparator.comparing(SessionListEntry::loginTime).reversed();

    private final PluginConfig config;
    private final TaskRunner tasks;
    private final Clock clock;
    private final SimpleCloudClient cloud;
    private final ReplayStore store;
    private final Logger logger;
    private final CloudPageCache pages;

    public SessionQueryService(PluginConfig config, TaskRunner tasks, Clock clock, SimpleCloudClient cloud,
                               ReplayStore store, Logger logger) {
        this.config = Objects.requireNonNull(config, "config");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.cloud = Objects.requireNonNull(cloud, "cloud");
        this.store = Objects.requireNonNull(store, "store");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.pages = new CloudPageCache(cloud, clock, () -> config.simpleCloud().sessionListCacheTtl());
    }

    /** Verwirft zwischengespeicherte SimpleCloud-Seiten eines Spielers (z. B. nach {@code SESSION_CLOSED}). */
    public void invalidate(UUID player) {
        pages.invalidate(player);
    }

    /** Zeitfenster und Seitengeometrie einer Anfrage. */
    private record Window(UUID player, int page, int pageSize, int maxSessions, Instant now, Instant start) {

        int offset() {
            return page * pageSize;
        }
    }

    // ------------------------------------------------------------------ Liste

    @Override
    public CompletableFuture<SessionListPage> listSessions(UUID player, int page) {
        PluginConfig.DisplaySettings display = config.display();
        int pageSize = Math.max(1, display.pageSize());
        int maxSessions = Math.max(1, display.maxSessions());
        int pageIndex = Math.min(Math.max(0, page), maxSessions / pageSize + 1);
        Instant now = clock.instant();
        Window window = new Window(player, pageIndex, pageSize, maxSessions, now,
                now.minus(Duration.ofDays(Math.max(1, display.historyDays()))));
        if (!cloud.isConfigured()) {
            return databasePage(window, WARN_CLOUD_NOT_CONFIGURED, null);
        }
        int limit = Math.min(pageSize + 1, CLOUD_MAX_LIMIT);
        CompletableFuture<CloudSessionPage> fetch = window.offset() >= maxSessions
                ? CompletableFuture.completedFuture(CloudSessionPage.empty())
                : pages.page(player, window.offset(), limit);
        return fetch.handle((cloudPage, error) -> error == null
                        ? fromCloud(window, limit, cloudPage)
                        : databasePage(window, WARN_CLOUD_UNAVAILABLE, Futures.unwrap(error)))
                .thenCompose(Function.identity());
    }

    private CompletableFuture<SessionListPage> fromCloud(Window window, int limit, CloudSessionPage cloudPage) {
        List<CloudSession> sessions = cloudPage.sessions();
        List<CloudSession> inWindow = new ArrayList<>();
        boolean boundary = false;
        for (int i = 0; i < sessions.size(); i++) {
            CloudSession session = sessions.get(i);
            if (window.offset() + i >= window.maxSessions() || !inWindow(session, window.start())) {
                boundary = true; // neueste zuerst: alles Folgende liegt ebenfalls außerhalb
                break;
            }
            inWindow.add(session);
        }
        boolean endOfList = sessions.size() < limit;
        List<CloudSession> visible = List.copyOf(inWindow.subList(0, Math.min(window.pageSize(), inWindow.size())));
        boolean hasNext = inWindow.size() > window.pageSize();
        int seen = window.offset() + inWindow.size();
        CompletableFuture<Integer> total;
        if ((boundary || endOfList) && (window.offset() == 0 || !inWindow.isEmpty())) {
            total = CompletableFuture.completedFuture(seen);
        } else {
            total = countWindow(window, 0).exceptionally(error -> seen);
        }
        return total.thenApplyAsync(cloudTotal -> enrich(window, visible, hasNext, cloudTotal), tasks.io());
    }

    /** Zählt die SimpleCloud-Sitzungen im Fenster (Seiten zu 100, zwischengespeichert). */
    private CompletableFuture<Integer> countWindow(Window window, int offset) {
        if (offset >= window.maxSessions()) {
            return CompletableFuture.completedFuture(window.maxSessions());
        }
        return pages.page(window.player(), offset, CLOUD_MAX_LIMIT).thenCompose(page -> {
            int counted = 0;
            for (CloudSession session : page.sessions()) {
                if (offset + counted >= window.maxSessions()) {
                    return CompletableFuture.completedFuture(window.maxSessions());
                }
                if (!inWindow(session, window.start())) {
                    return CompletableFuture.completedFuture(offset + counted);
                }
                counted++;
            }
            if (page.sessions().size() < CLOUD_MAX_LIMIT) {
                return CompletableFuture.completedFuture(offset + counted);
            }
            return countWindow(window, offset + CLOUD_MAX_LIMIT);
        });
    }

    /** I/O-Thread: Abschnittsdaten und (Seite 0) vorläufige Sitzungen aus MariaDB ergänzen. */
    private SessionListPage enrich(Window window, List<CloudSession> visible, boolean hasNext, int cloudTotal) {
        List<String> warnings = new ArrayList<>();
        SessionListPage.Source source = SessionListPage.Source.SIMPLECLOUD;
        Map<String, SessionStats> stats = Map.of();
        Map<String, Long> dbIds = Map.of();
        List<SessionRow> provisionalRows = List.of();
        Map<Long, SessionStats> provisionalStats = Map.of();
        try {
            if (!store.isEnabled()) {
                throw new StorageUnavailableException("Datenbank ist deaktiviert");
            }
            List<String> ids = visible.stream().map(CloudSession::id).toList();
            stats = ids.isEmpty() ? Map.of() : store.statsByCloudSessionIds(ids);
            List<SessionRow> rows = store.sessionsOfPlayer(window.player(), window.start(), window.maxSessions(), 0);
            Map<String, Long> byCloudId = new HashMap<>();
            for (SessionRow row : rows) {
                if (row.cloudSessionId() != null) {
                    byCloudId.putIfAbsent(row.cloudSessionId(), row.id());
                }
            }
            dbIds = byCloudId;
            provisionalRows = rows.stream()
                    .filter(row -> row.source() == SessionSource.PROVISIONAL && row.status() != SessionStatus.MERGED)
                    .filter(row -> inWindow(row, window.start()))
                    .toList();
            if (window.page() == 0 && !provisionalRows.isEmpty()) {
                provisionalStats = store.statsBySessionIds(provisionalRows.stream().map(SessionRow::id).toList());
            }
        } catch (RuntimeException e) {
            if (!(e instanceof StorageUnavailableException)) {
                logger.log(Level.WARNING, "Abschnittsdaten der Sitzungsliste nicht lesbar", e);
            } else {
                logger.fine("Sitzungsliste ohne Abschnittsdaten: " + e.getMessage());
            }
            source = SessionListPage.Source.SIMPLECLOUD_ONLY;
            warnings.add(WARN_SEGMENTS_UNAVAILABLE);
            stats = Map.of();
            dbIds = Map.of();
            provisionalRows = List.of();
            provisionalStats = Map.of();
        }
        List<SessionListEntry> entries = new ArrayList<>();
        for (CloudSession session : visible) {
            entries.add(entryOf(session, dbIds.get(session.id()), stats.getOrDefault(session.id(), SessionStats.EMPTY),
                    window.now()));
        }
        if (window.page() == 0) {
            for (SessionRow row : provisionalRows) {
                entries.add(entryOf(row, provisionalStats.getOrDefault(row.id(), SessionStats.EMPTY), window.now()));
            }
        }
        entries.sort(NEWEST_FIRST);
        return new SessionListPage(window.player(), window.page(), window.pageSize(), hasNext,
                cloudTotal + provisionalRows.size(), source, entries, warnings);
    }

    /** Rückfall ohne SimpleCloud: Liste aus MariaDB. */
    private CompletableFuture<SessionListPage> databasePage(Window window, String warning,
                                                            @Nullable Throwable cloudError) {
        if (cloudError != null) {
            logger.fine("Sitzungsliste aus der Datenbank: " + CloudCalls.describe(cloudError));
        }
        return tasks.supplyIo(() -> {
            try {
                if (!store.isEnabled()) {
                    throw new StorageUnavailableException("Datenbank ist deaktiviert");
                }
                int total = Math.min(store.countSessionsOfPlayer(window.player(), window.start()),
                        window.maxSessions());
                List<SessionRow> rows = window.offset() >= window.maxSessions() ? List.of()
                        : store.sessionsOfPlayer(window.player(), window.start(), window.pageSize() + 1,
                        window.offset());
                int visibleCount = Math.min(Math.min(rows.size(), window.pageSize()),
                        Math.max(0, window.maxSessions() - window.offset()));
                List<SessionRow> visible = rows.subList(0, visibleCount);
                boolean hasNext = rows.size() > window.pageSize()
                        && window.offset() + window.pageSize() < window.maxSessions();
                Map<Long, SessionStats> stats = visible.isEmpty() ? Map.of()
                        : store.statsBySessionIds(visible.stream().map(SessionRow::id).toList());
                List<SessionListEntry> entries = new ArrayList<>();
                for (SessionRow row : visible) {
                    entries.add(entryOf(row, stats.getOrDefault(row.id(), SessionStats.EMPTY), window.now()));
                }
                return new SessionListPage(window.player(), window.page(), window.pageSize(), hasNext, total,
                        SessionListPage.Source.DATABASE, entries, List.of(warning));
            } catch (RuntimeException e) {
                if (cloudError != null && cloudError != e) {
                    e.addSuppressed(cloudError);
                }
                throw e;
            }
        });
    }

    // ------------------------------------------------------------------ Details

    private record StoredLookup(List<SessionRow> rows, @Nullable RuntimeException error) {
    }

    private record Stored(SessionRow row, List<SegmentRow> segments, List<VisitRow> visits) {
    }

    @Override
    public CompletableFuture<Optional<SessionDetails>> details(UUID player, String ref) {
        String normalized = ref == null ? "" : ref.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return tasks.supplyIo(() -> lookupStored(player, normalized)).thenCompose(lookup -> {
            RuntimeException storageError = lookup.error();
            if (storageError != null) {
                return fromCloudOnly(player, normalized, WARN_SEGMENTS_UNAVAILABLE, storageError);
            }
            List<SessionRow> rows = lookup.rows();
            if (rows.size() > 1) {
                return CompletableFuture.failedFuture(new AmbiguousReferenceException(normalized,
                        SessionRefs.candidates(rows.stream().map(SessionRow::sessionKey).toList())));
            }
            if (rows.size() == 1) {
                return fromStored(rows.getFirst());
            }
            return fromCloudOnly(player, normalized, WARN_NO_SEGMENTS, null);
        });
    }

    private StoredLookup lookupStored(UUID player, String ref) {
        if (!store.isEnabled()) {
            return new StoredLookup(List.of(), new StorageUnavailableException("Datenbank ist deaktiviert"));
        }
        try {
            return new StoredLookup(store.findSessionsByRef(player, ref, 2), null);
        } catch (StorageUnavailableException e) {
            return new StoredLookup(List.of(), e);
        }
    }

    private CompletableFuture<Optional<SessionDetails>> fromStored(SessionRow row) {
        return tasks.supplyIo(() -> new Stored(row, store.segmentsOfSession(row.id()), store.visitsOfSession(row.id())))
                .thenCompose(stored -> {
                    boolean refresh = row.status() == SessionStatus.ACTIVE && row.source() == SessionSource.SIMPLECLOUD
                            && cloud.isConfigured();
                    if (!refresh) {
                        return CompletableFuture.completedFuture(detailsOf(stored, null, List.of()));
                    }
                    String cloudId = row.cloudSessionId() != null ? row.cloudSessionId() : row.sessionKey();
                    return Futures.call(() -> cloud.listSessions(row.playerUuid(), DETAIL_HISTORY_LIMIT, 0))
                            .handleAsync((page, error) -> {
                                if (error != null) {
                                    return detailsOf(stored, null, List.of(WARN_LOGOUT_NOT_REFRESHED));
                                }
                                CloudSession match = page.sessions().stream()
                                        .filter(session -> session.id().equals(cloudId))
                                        .findFirst().orElse(null);
                                if (match != null && !match.active() && match.logoutTime() != null) {
                                    try {
                                        // Selbstheilung: der Abgleich hätte das ohnehin nachgeholt
                                        store.closeSession(row.sessionKey(), match.logoutTime(),
                                                SessionCloser.durationOf(match));
                                    } catch (RuntimeException e) {
                                        logger.log(Level.FINE, "Logout konnte nicht gespeichert werden", e);
                                    }
                                }
                                return detailsOf(stored, match, List.of());
                            }, tasks.io());
                });
    }

    private Optional<SessionDetails> detailsOf(Stored stored, @Nullable CloudSession cloudSession,
                                               List<String> extraWarnings) {
        SessionRow row = stored.row();
        Instant now = clock.instant();
        Set<String> servers = new TreeSet<>();
        int available = 0;
        for (SegmentRow segment : stored.segments()) {
            servers.add(segment.serverName());
            if (segment.status().playable()) {
                available++;
            }
        }
        for (VisitRow visit : stored.visits()) {
            servers.add(visit.serverName());
        }
        SessionListEntry summary;
        if (cloudSession != null) {
            summary = new SessionListEntry(NetworkSession.shortRef(row.sessionKey()), cloudSession.id(), row.id(),
                    cloudSession.loginTime(), cloudSession.logoutTime(), cloudSession.durationSecondsAt(now),
                    cloudSession.active(), false, stored.segments().size(), available, servers);
        } else {
            summary = entryOf(row, new SessionStats(stored.segments().size(), available, 0L, servers, null, null), now);
        }
        List<String> warnings = new ArrayList<>(extraWarnings);
        if (row.source() == SessionSource.PROVISIONAL) {
            warnings.add(WARN_PROVISIONAL);
        }
        if (stored.segments().isEmpty()) {
            warnings.add(WARN_NO_SEGMENTS);
        }
        return Optional.of(new SessionDetails(summary, stored.segments(), stored.visits(), warnings));
    }

    /** Referenz ohne MariaDB-Zeile: in den SimpleCloud-Sitzungen des Spielers suchen (ohne Abschnitte). */
    private CompletableFuture<Optional<SessionDetails>> fromCloudOnly(UUID player, String ref, String warning,
                                                                      @Nullable RuntimeException storageError) {
        if (!cloud.isConfigured() || !SessionRefs.isCloudRef(ref)) {
            return storageError != null ? CompletableFuture.failedFuture(storageError)
                    : CompletableFuture.completedFuture(Optional.empty());
        }
        String compact = SessionRefs.compact(ref);
        return findByPrefix(player, compact, 0, new ArrayList<>()).handle((matches, error) -> {
            if (error != null) {
                Throwable cause = Futures.unwrap(error);
                if (storageError != null) {
                    storageError.addSuppressed(cause);
                    throw new CompletionException(storageError);
                }
                throw new CompletionException(cause);
            }
            if (matches.size() > 1) {
                throw new CompletionException(new AmbiguousReferenceException(ref,
                        SessionRefs.candidates(matches.stream().map(CloudSession::id).toList())));
            }
            if (matches.isEmpty()) {
                return Optional.<SessionDetails>empty();
            }
            SessionListEntry summary = entryOf(matches.getFirst(), null, SessionStats.EMPTY, clock.instant());
            return Optional.of(new SessionDetails(summary, List.of(), List.of(), List.of(warning)));
        });
    }

    private CompletableFuture<List<CloudSession>> findByPrefix(UUID player, String compactRef, int offset,
                                                               List<CloudSession> matches) {
        int max = Math.max(1, config.display().maxSessions());
        if (offset >= max) {
            return CompletableFuture.completedFuture(matches);
        }
        int limit = Math.min(CLOUD_MAX_LIMIT, max - offset);
        return pages.page(player, offset, limit).thenCompose(page -> {
            for (CloudSession session : page.sessions()) {
                if (SessionRefs.compact(session.id()).startsWith(compactRef)) {
                    matches.add(session);
                }
            }
            if (matches.size() > 1 || page.sessions().size() < limit) {
                return CompletableFuture.completedFuture(matches);
            }
            return findByPrefix(player, compactRef, offset + limit, matches);
        });
    }

    // ------------------------------------------------------------------ Hilfen

    private static boolean inWindow(CloudSession session, Instant windowStart) {
        Instant logout = session.logoutTime();
        return logout == null || !logout.isBefore(windowStart);
    }

    private static boolean inWindow(SessionRow row, Instant windowStart) {
        if (row.status() == SessionStatus.ACTIVE) {
            return true;
        }
        Instant reference = row.logoutTime() != null ? row.logoutTime()
                : row.loginTime() != null ? row.loginTime() : row.createdAt();
        return !reference.isBefore(windowStart);
    }

    private static SessionListEntry entryOf(CloudSession session, @Nullable Long dbSessionId, SessionStats stats,
                                            Instant now) {
        return new SessionListEntry(NetworkSession.shortRef(session.id()), session.id(), dbSessionId,
                session.loginTime(), session.logoutTime(), session.durationSecondsAt(now), session.active(), false,
                stats.segments(), stats.availableSegments(), stats.servers());
    }

    private static SessionListEntry entryOf(SessionRow row, SessionStats stats, Instant now) {
        Instant login = row.loginTime() != null ? row.loginTime() : row.createdAt();
        boolean active = row.status() == SessionStatus.ACTIVE;
        Instant logout = row.logoutTime();
        long duration;
        if (row.durationSeconds() != null) {
            duration = row.durationSeconds();
        } else if (logout != null) {
            duration = Math.max(0L, logout.getEpochSecond() - login.getEpochSecond());
        } else if (active) {
            duration = Math.max(0L, now.getEpochSecond() - login.getEpochSecond());
        } else {
            duration = 0L;
        }
        return new SessionListEntry(NetworkSession.shortRef(row.sessionKey()), row.cloudSessionId(), row.id(), login,
                logout, duration, active, row.source() == SessionSource.PROVISIONAL, stats.segments(),
                stats.availableSegments(), stats.servers());
    }
}
