package net.techniqstone.buildReplay.session.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.techniqstone.buildReplay.session.NetworkSession;
import net.techniqstone.buildReplay.simplecloud.CloudOnlinePlayer;
import net.techniqstone.buildReplay.simplecloud.CloudSession;
import net.techniqstone.buildReplay.state.CachedSession;
import net.techniqstone.buildReplay.state.SharedState;
import net.techniqstone.buildReplay.storage.ReplayStore;
import net.techniqstone.buildReplay.storage.model.SessionRow;
import net.techniqstone.buildReplay.storage.model.SessionSource;
import net.techniqstone.buildReplay.storage.model.SessionStatus;
import net.techniqstone.buildReplay.storage.model.SessionUpsert;
import org.jspecify.annotations.Nullable;

/**
 * Netzwerkweiter Sitzungsabgleich mit SimpleCloud (läuft nur auf der Leader-Instanz):
 *
 * <ol>
 *   <li>Online-Liste gegen den vorherigen Stand: verschwundene Spieler → Sitzung schließen; abweichende
 *       Session-ID → Sitzungscache leeren.</li>
 *   <li>Aktive SimpleCloud-Sitzungen in MariaDB, deren Spieler nicht (mehr mit dieser Sitzung) online ist →
 *       echten Logout aus der Sitzungshistorie übernehmen.</li>
 *   <li>Vorläufige Sitzungen → die SimpleCloud-Sitzung suchen, die den Login-Zeitpunkt abdeckt, und
 *       zusammenführen.</li>
 * </ol>
 *
 * Höchstens {@value #MAX_CALLS_PER_RUN} SimpleCloud-Aufrufe pro Lauf; bei offenem Circuit-Breaker oder Ausfall
 * wird still abgebrochen. Erfolglose Zeilen werden mit wachsendem Abstand erneut versucht.
 */
final class SessionReconciler {

    static final int MAX_CALLS_PER_RUN = 20;
    static final int ACTIVE_BATCH = 50;
    static final int PROVISIONAL_BATCH = 20;
    static final int HISTORY_LIMIT = 10;
    static final int COVER_PAGE_SIZE = 25;
    static final int COVER_MAX_PAGES = 3;
    static final Duration COVER_SLACK = Duration.ofSeconds(10);
    private static final Duration FIRST_BACKOFF = Duration.ofMinutes(5);
    private static final Duration MAX_BACKOFF = Duration.ofHours(1);
    private static final int BACKOFF_PRUNE_THRESHOLD = 1_000;

    /** Rückkanal in die Sitzungsverwaltung dieser Instanz. */
    interface Hooks {

        /** Ob der Spieler gerade auf diesem Server mit dieser Sitzung anwesend ist (dann nie schließen). */
        boolean presentLocallyWith(UUID player, String sessionKey);

        /** Eine vorläufige Sitzung wurde in {@code target} überführt (Cache und lokale Spieler nachziehen). */
        void provisionalMerged(UUID player, String provisionalKey, CloudSession target);
    }

    private record OnlineDiff(Map<UUID, CloudOnlinePlayer> online, List<CloudOnlinePlayer> gone) {
    }

    private record Covering(@Nullable CloudSession session, boolean definitive) {
        static final Covering NONE = new Covering(null, true);
        static final Covering UNKNOWN = new Covering(null, false);
    }

    private record Backoff(Instant notBefore, Duration last) {
    }

    /** Zähler eines Laufs (nur für das Protokoll). */
    private static final class RunStats {
        final AtomicInteger closed = new AtomicInteger();
        final AtomicInteger merged = new AtomicInteger();
        final AtomicInteger evicted = new AtomicInteger();
    }

    private final Clock clock;
    private final CloudCalls cloud;
    private final ReplayStore store;
    private final SharedState shared;
    private final SessionCloser closer;
    private final Hooks hooks;
    private final Logger logger;
    private final Executor io;
    private final Map<String, Backoff> backoff = new ConcurrentHashMap<>();
    private volatile @Nullable Map<UUID, CloudOnlinePlayer> snapshot;

    SessionReconciler(Clock clock, CloudCalls cloud, ReplayStore store, SharedState shared, SessionCloser closer,
                      Hooks hooks, Logger logger, Executor io) {
        this.clock = clock;
        this.cloud = cloud;
        this.store = store;
        this.shared = shared;
        this.closer = closer;
        this.hooks = hooks;
        this.logger = logger;
        this.io = io;
    }

    /** Vergisst den Online-Stand (z. B. nach Verlust der Leader-Rolle - der neue Leader beginnt ohne Diff). */
    void resetSnapshot() {
        snapshot = null;
    }

    /**
     * Ein Abgleichlauf. {@code leader=false} (erzwungener Lauf auf einer Nicht-Leader-Instanz) verwendet und
     * verändert den Online-Stand nicht; die Schritte 2 und 3 laufen trotzdem (idempotent).
     */
    CompletableFuture<Void> run(boolean leader) {
        if (!cloud.configured()) {
            return Futures.done();
        }
        if (cloud.circuitOpen()) {
            logger.fine("Sitzungsabgleich übersprungen: SimpleCloud-Circuit-Breaker ist offen");
            return Futures.done();
        }
        pruneBackoff(clock.instant());
        CallBudget budget = new CallBudget(MAX_CALLS_PER_RUN);
        RunStats stats = new RunStats();
        return reconcileOnline(leader, budget, stats)
                .thenComposeAsync(online -> reconcileActive(online, budget, stats), io)
                .thenComposeAsync(ignored -> reconcileProvisional(budget, stats), io)
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        logger.log(Level.WARNING, "Sitzungsabgleich mit SimpleCloud abgebrochen", Futures.unwrap(error));
                    } else if (stats.closed.get() + stats.merged.get() + stats.evicted.get() > 0 || budget.stopped()) {
                        logger.fine("Sitzungsabgleich: " + stats.closed.get() + " geschlossen, " + stats.merged.get()
                                + " zusammengeführt, " + stats.evicted.get() + " Cache-Einträge verworfen, "
                                + budget.used() + " SimpleCloud-Aufrufe" + (budget.stopped() ? " (abgebrochen)" : ""));
                    }
                });
    }

    // ------------------------------------------------------------------ 1. Online-Liste

    private CompletableFuture<@Nullable Map<UUID, CloudOnlinePlayer>> reconcileOnline(boolean leader,
                                                                                       CallBudget budget,
                                                                                       RunStats stats) {
        if (!budget.tryUse()) {
            return CompletableFuture.completedFuture(null);
        }
        return cloud.onlinePlayers().handleAsync((players, error) -> {
            if (error != null) {
                budget.stop();
                return (OnlineDiff) null;
            }
            Map<UUID, CloudOnlinePlayer> online = new HashMap<>();
            for (CloudOnlinePlayer player : players) {
                online.put(player.uuid(), player);
            }
            for (CloudOnlinePlayer player : players) {
                if (evictIfDifferent(player)) {
                    stats.evicted.incrementAndGet();
                }
            }
            List<CloudOnlinePlayer> gone = List.of();
            if (leader) {
                Map<UUID, CloudOnlinePlayer> previous = snapshot;
                snapshot = Map.copyOf(online);
                if (previous != null) {
                    gone = previous.values().stream().filter(p -> !online.containsKey(p.uuid())).toList();
                }
            }
            return new OnlineDiff(online, gone);
        }, io).thenComposeAsync(diff -> {
            if (diff == null) {
                return CompletableFuture.<@Nullable Map<UUID, CloudOnlinePlayer>>completedFuture(null);
            }
            return Futures.sequentially(diff.gone().iterator(),
                            logged("Sitzungsende nach Netzwerk-Logout", player -> closeGone(player, budget, stats)), io)
                    .thenApply(ignored -> diff.online());
        }, io);
    }

    private boolean evictIfDifferent(CloudOnlinePlayer player) {
        String sessionId = player.sessionId();
        if (sessionId == null) {
            return false;
        }
        try {
            Optional<CachedSession> cached = shared.cachedSession(player.uuid());
            if (cached.isPresent() && cached.get().cloudSessionId() != null
                    && !sessionId.equals(cached.get().cloudSessionId())) {
                shared.evictSession(player.uuid());
                logger.fine("Sitzungscache von " + player.name() + " verworfen: SimpleCloud meldet Sitzung "
                        + NetworkSession.shortRef(sessionId));
                return true;
            }
        } catch (RuntimeException e) {
            logger.log(Level.FINE, "Sitzungscache von " + player.uuid() + " nicht prüfbar", e);
        }
        return false;
    }

    private CompletableFuture<?> closeGone(CloudOnlinePlayer player, CallBudget budget, RunStats stats) {
        closer.evictIfMatches(player.uuid(), player.sessionId());
        String sessionId = player.sessionId();
        if (sessionId != null) {
            Optional<SessionRow> row;
            try {
                row = store.findSessionByKey(sessionId);
            } catch (RuntimeException e) {
                row = null; // MariaDB unbekannt - trotzdem schließen (landet notfalls in der Outbox)
            }
            if (row != null && (row.isEmpty() || row.get().status() != SessionStatus.ACTIVE)) {
                return Futures.done(); // nie auf einem BuildReplay-Server gewesen oder bereits geschlossen
            }
        }
        if (!budget.tryUse()) {
            return Futures.done();
        }
        return closer.closeLatest(player.uuid()).handle((result, error) -> {
            if (error != null) {
                if (CloudCalls.outage(error)) {
                    budget.stop();
                }
            } else if (result.outcome() == SessionCloser.Outcome.CLOSED) {
                stats.closed.incrementAndGet();
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ 2. aktive Sitzungen in MariaDB

    private CompletableFuture<Void> reconcileActive(@Nullable Map<UUID, CloudOnlinePlayer> online,
                                                    CallBudget budget, RunStats stats) {
        if (online == null || budget.exhausted()) {
            return Futures.done();
        }
        List<SessionRow> rows;
        try {
            rows = store.activeSessions(ACTIVE_BATCH);
        } catch (RuntimeException e) {
            logger.fine("Sitzungsabgleich: aktive Sitzungen nicht lesbar (" + e.getMessage() + ")");
            return Futures.done();
        }
        Instant now = clock.instant();
        List<SessionRow> candidates = rows.stream()
                .filter(row -> row.source() == SessionSource.SIMPLECLOUD)
                .filter(row -> !backedOff(row.sessionKey(), now))
                .filter(row -> {
                    CloudOnlinePlayer player = online.get(row.playerUuid());
                    if (player == null) {
                        return true;
                    }
                    String current = player.sessionId();
                    return current != null && !current.equals(cloudIdOf(row));
                })
                .filter(row -> !hooks.presentLocallyWith(row.playerUuid(), row.sessionKey()))
                .toList();
        return Futures.sequentially(candidates.iterator(),
                logged("Abgleich einer aktiven Sitzung", row -> closeFromHistory(row, budget, stats)), io);
    }

    private CompletableFuture<?> closeFromHistory(SessionRow row, CallBudget budget, RunStats stats) {
        if (!budget.tryUse()) {
            return Futures.done();
        }
        String cloudId = cloudIdOf(row);
        return cloud.listSessions(row.playerUuid(), HISTORY_LIMIT, 0).handleAsync((page, error) -> {
            if (error != null) {
                if (CloudCalls.outage(error)) {
                    budget.stop();
                } else {
                    backoff(row.sessionKey());
                }
                return null;
            }
            Optional<CloudSession> match = page.sessions().stream().filter(s -> s.id().equals(cloudId)).findFirst();
            if (match.isPresent()) {
                CloudSession session = match.get();
                if (!session.active()) {
                    closer.closeKnown(row.playerUuid(), row.sessionKey(), session.logoutTime(),
                            SessionCloser.durationOf(session));
                    stats.closed.incrementAndGet();
                }
                return null;
            }
            // nicht unter den neuesten Sitzungen: Logout spätestens beim nächsten Login (Sitzungen überlappen nicht)
            Instant login = row.loginTime();
            Optional<Instant> nextLogin = login == null ? Optional.empty() : page.sessions().stream()
                    .map(CloudSession::loginTime)
                    .filter(time -> time.isAfter(login))
                    .min(Comparator.naturalOrder());
            if (login != null && nextLogin.isPresent()) {
                long duration = Math.max(0L, Duration.between(login, nextLogin.get()).toSeconds());
                closer.closeKnown(row.playerUuid(), row.sessionKey(), nextLogin.get(), duration);
                stats.closed.incrementAndGet();
                logger.fine("Sitzung " + NetworkSession.shortRef(row.sessionKey()) + " nicht mehr in der "
                        + "SimpleCloud-Historie - Logout auf den folgenden Login geschätzt");
            } else {
                backoff(row.sessionKey());
            }
            return null;
        }, io);
    }

    // ------------------------------------------------------------------ 3. vorläufige Sitzungen

    private CompletableFuture<Void> reconcileProvisional(CallBudget budget, RunStats stats) {
        if (budget.exhausted()) {
            return Futures.done();
        }
        List<SessionRow> rows;
        try {
            rows = store.provisionalSessions(PROVISIONAL_BATCH);
        } catch (RuntimeException e) {
            logger.fine("Sitzungsabgleich: vorläufige Sitzungen nicht lesbar (" + e.getMessage() + ")");
            return Futures.done();
        }
        Instant now = clock.instant();
        List<SessionRow> candidates = rows.stream()
                .filter(row -> row.status() != SessionStatus.MERGED)
                .filter(row -> !backedOff(row.sessionKey(), now))
                .toList();
        return Futures.sequentially(candidates.iterator(),
                logged("Zusammenführung einer vorläufigen Sitzung", row -> mergeProvisional(row, budget, stats)), io);
    }

    private CompletableFuture<?> mergeProvisional(SessionRow row, CallBudget budget, RunStats stats) {
        Instant at = row.loginTime() != null ? row.loginTime() : row.createdAt();
        return findCovering(row.playerUuid(), at, 0, budget).thenAcceptAsync(covering -> {
            CloudSession target = covering.session();
            if (target == null) {
                if (covering.definitive()) {
                    backoff(row.sessionKey());
                    logger.fine("Keine SimpleCloud-Sitzung deckt die vorläufige Sitzung "
                            + NetworkSession.shortRef(row.sessionKey()) + " (" + at + ") ab");
                }
                return;
            }
            SessionUpsert upsert = new SessionUpsert(target.id(), target.id(), row.playerUuid(),
                    SessionSource.SIMPLECLOUD, target.loginTime(), target.logoutTime(),
                    SessionCloser.durationOf(target));
            try {
                if (row.sessionKey().equals(target.id())) {
                    store.upsertSession(upsert);
                } else {
                    store.mergeProvisionalSession(row.sessionKey(), upsert);
                }
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "Vorläufige Sitzung " + NetworkSession.shortRef(row.sessionKey())
                        + " konnte nicht zusammengeführt werden", e);
                return;
            }
            backoff.remove(row.sessionKey());
            stats.merged.incrementAndGet();
            hooks.provisionalMerged(row.playerUuid(), row.sessionKey(), target);
            logger.info("Vorläufige Sitzung " + NetworkSession.shortRef(row.sessionKey()) + " von "
                    + (row.playerName() != null ? row.playerName() : row.playerUuid().toString())
                    + " mit SimpleCloud-Sitzung " + NetworkSession.shortRef(target.id()) + " zusammengeführt");
        }, io);
    }

    /** Sucht (neueste zuerst, höchstens {@value #COVER_MAX_PAGES} Seiten) die Sitzung, die {@code at} abdeckt. */
    private CompletableFuture<Covering> findCovering(UUID player, Instant at, int pageIndex, CallBudget budget) {
        if (pageIndex >= COVER_MAX_PAGES) {
            return CompletableFuture.completedFuture(Covering.NONE);
        }
        if (!budget.tryUse()) {
            return CompletableFuture.completedFuture(Covering.UNKNOWN);
        }
        return cloud.listSessions(player, COVER_PAGE_SIZE, pageIndex * COVER_PAGE_SIZE).handleAsync((page, error) -> {
            if (error != null) {
                if (CloudCalls.outage(error)) {
                    budget.stop();
                }
                return CompletableFuture.completedFuture(Covering.UNKNOWN);
            }
            CloudSession strict = null;
            CloudSession loose = null;
            boolean reachedOlder = false;
            for (CloudSession session : page.sessions()) {
                if (strict == null && session.covers(at, Duration.ZERO)) {
                    strict = session;
                }
                if (loose == null && session.covers(at, COVER_SLACK)) {
                    loose = session;
                }
                if (session.loginTime().isBefore(at.minus(COVER_SLACK))) {
                    reachedOlder = true; // ältere Sitzungen endeten vor dieser - keine deckt "at" mehr ab
                    break;
                }
            }
            if (strict != null || loose != null) {
                return CompletableFuture.completedFuture(new Covering(strict != null ? strict : loose, true));
            }
            if (reachedOlder || page.sessions().size() < COVER_PAGE_SIZE) {
                return CompletableFuture.completedFuture(Covering.NONE);
            }
            return findCovering(player, at, pageIndex + 1, budget);
        }, io).thenCompose(Function.identity());
    }

    // ------------------------------------------------------------------ Hilfen

    /** Unerwartete Fehler eines Schleifenschritts protokollieren (die Schleife läuft weiter). */
    private <T> Function<T, CompletableFuture<?>> logged(String what, Function<T, CompletableFuture<?>> action) {
        return item -> {
            CompletableFuture<?> step;
            try {
                step = action.apply(item);
            } catch (RuntimeException e) {
                step = CompletableFuture.failedFuture(e);
            }
            return step.whenComplete((ignored, error) -> {
                if (error != null) {
                    logger.log(Level.WARNING, what + " fehlgeschlagen", Futures.unwrap(error));
                }
            });
        };
    }

    private static String cloudIdOf(SessionRow row) {
        return row.cloudSessionId() != null ? row.cloudSessionId() : row.sessionKey();
    }

    private void backoff(String sessionKey) {
        Instant now = clock.instant();
        backoff.compute(sessionKey, (key, old) -> {
            Duration next = old == null ? FIRST_BACKOFF : old.last().multipliedBy(2);
            if (next.compareTo(MAX_BACKOFF) > 0) {
                next = MAX_BACKOFF;
            }
            return new Backoff(now.plus(next), next);
        });
    }

    private boolean backedOff(String sessionKey, Instant now) {
        Backoff entry = backoff.get(sessionKey);
        return entry != null && now.isBefore(entry.notBefore());
    }

    private void pruneBackoff(Instant now) {
        if (backoff.size() > BACKOFF_PRUNE_THRESHOLD) {
            backoff.entrySet().removeIf(entry -> now.isAfter(entry.getValue().notBefore().plus(MAX_BACKOFF)));
        }
    }
}
