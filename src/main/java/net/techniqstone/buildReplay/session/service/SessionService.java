package net.techniqstone.buildReplay.session.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.techniqstone.buildReplay.config.PluginConfig;
import net.techniqstone.buildReplay.core.TaskRunner;
import net.techniqstone.buildReplay.server.ServerIdentity;
import net.techniqstone.buildReplay.session.NetworkSession;
import net.techniqstone.buildReplay.session.SessionServiceStats;
import net.techniqstone.buildReplay.session.Sessions;
import net.techniqstone.buildReplay.simplecloud.CloudSession;
import net.techniqstone.buildReplay.simplecloud.SimpleCloudClient;
import net.techniqstone.buildReplay.simplecloud.SimpleCloudPlatform;
import net.techniqstone.buildReplay.state.CachedSession;
import net.techniqstone.buildReplay.state.LockToken;
import net.techniqstone.buildReplay.state.PresenceInfo;
import net.techniqstone.buildReplay.state.SharedState;
import net.techniqstone.buildReplay.storage.ReplayStore;
import net.techniqstone.buildReplay.storage.model.SessionSource;
import net.techniqstone.buildReplay.storage.model.SessionUpsert;
import net.techniqstone.buildReplay.storage.model.VisitEndReason;
import net.techniqstone.buildReplay.storage.model.VisitStart;
import org.jspecify.annotations.Nullable;

/**
 * Netzwerkweite Sitzungsverwaltung dieser Instanz.
 *
 * <p><b>Auflösung beim Join</b> (ein Serverwechsel darf keine neue Sitzung erzeugen):
 * <ol>
 *   <li>SimpleCloud {@code /sessions/current}. Direkt nach dem ersten Netzwerk-Login ist ein 404 normal (die
 *       Sitzung entsteht beim Proxy-Login mit einigen Sekunden Verzögerung) - daher Wiederholung alle 1-2 s
 *       innerhalb von {@code simplecloud.current-session-retry-seconds}.</li>
 *   <li>Ohne SimpleCloud: zwischengespeicherte Sitzung aus Redis, wenn die Präsenz einen Serverwechsel zeigt
 *       (Spieler anderswo online oder vor weniger als {@code resilience.switch-grace-seconds} gegangen).</li>
 *   <li>Sonst vorläufige Sitzung ({@code prov-...}) unter einer verteilten Sperre, damit zwei gleichzeitig
 *       auflösende Server denselben Schlüssel verwenden. Sie wird später mit der SimpleCloud-Sitzung
 *       zusammengeführt (Hintergrundversuch für anwesende Spieler und {@link SessionReconciler}).</li>
 * </ol>
 *
 * <p><b>Threads</b>: {@link #handleJoin}/{@link #handleQuit} kommen vom Hauptthread und machen dort nur
 * Speicherarbeit bzw. nicht blockierende Store-Schreibaufrufe; Redis, HTTP und Lesezugriffe laufen auf
 * {@link TaskRunner#io()}. Die SimpleCloud-Events kommen auf Fremdthreads und werden sofort auf den I/O-Pool
 * verlagert; sie sind nur Hinweise und dürfen fehlen oder doppelt kommen.
 *
 * <p>Lebenszyklus: Konstruktor → {@link #start()} → ... → {@link #close()}.
 */
public final class SessionService implements Sessions, SimpleCloudPlatform.CloudEventListener, AutoCloseable {

    static final String RECONCILER_ROLE = "session-reconciler";
    /** So lange bleibt die Sitzung nach dem Verlassen abrufbar (Aufnahmen werden danach finalisiert). */
    static final Duration QUIT_RETENTION = Duration.ofMinutes(10);
    static final Duration LOCK_LEASE = Duration.ofSeconds(10);
    static final Duration LOCK_RETRY = Duration.ofMillis(250);
    static final Duration FIRST_RETRY_DELAY = Duration.ofSeconds(1);
    static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(2);
    static final Duration DISCONNECT_RECHECK = Duration.ofSeconds(5);
    static final int DISCONNECT_RECHECKS = 2;
    static final int MAX_UPGRADES_PER_TICK = 5;
    private static final Duration MIN_TICK = Duration.ofSeconds(1);

    private final PluginConfig config;
    private final ServerIdentity identity;
    private final TaskRunner tasks;
    private final Clock clock;
    private final ReplayStore store;
    private final SharedState shared;
    private final Logger logger;
    private final CloudCalls cloud;
    private final SessionCloser closer;
    private final SessionReconciler reconciler;

    private final ConcurrentHashMap<UUID, LocalPlayer> locals = new ConcurrentHashMap<>();
    private final List<ScheduledFuture<?>> scheduled = new CopyOnWriteArrayList<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final Object reconcileLock = new Object();
    private @Nullable CompletableFuture<Void> activeRun; // unter reconcileLock
    private volatile Instant nextReconcileAt = Instant.MIN;
    private volatile boolean leader;
    private volatile boolean closed;

    public SessionService(PluginConfig config, ServerIdentity identity, TaskRunner tasks, Clock clock,
                          SimpleCloudClient cloud, ReplayStore store, SharedState shared, Logger logger) {
        this.config = Objects.requireNonNull(config, "config");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.store = Objects.requireNonNull(store, "store");
        this.shared = Objects.requireNonNull(shared, "shared");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.cloud = new CloudCalls(Objects.requireNonNull(cloud, "cloud"), logger);
        this.closer = new SessionCloser(identity, this.cloud, store, shared, logger, tasks.io());
        this.reconciler = new SessionReconciler(clock, this.cloud, store, shared, closer, new SessionReconciler.Hooks() {
            @Override
            public boolean presentLocallyWith(UUID player, String sessionKey) {
                LocalPlayer local = locals.get(player);
                NetworkSession session = local != null ? local.session : null;
                return local != null && local.present && session != null && sessionKey.equals(session.sessionKey());
            }

            @Override
            public void provisionalMerged(UUID player, String provisionalKey, CloudSession target) {
                onProvisionalMerged(player, provisionalKey, target);
            }
        }, logger, tasks.io());
    }

    // ------------------------------------------------------------------ Lebenszyklus

    /** Plant Präsenz-Auffrischung, Hochstufung vorläufiger Sitzungen und den Sitzungsabgleich. */
    public void start() {
        if (closed || !started.compareAndSet(false, true)) {
            return;
        }
        PluginConfig.ResilienceSettings resilience = config.resilience();
        Duration refresh = atLeast(min(resilience.heartbeatInterval(), resilience.presenceTtl().dividedBy(3)));
        // Leader-Lease öfter erneuern als sie abläuft; die eigentliche Arbeit nur alle online-poll-seconds
        Duration reconcileTick = atLeast(min(config.simpleCloud().onlinePollInterval(),
                resilience.leaderLease().dividedBy(3)));
        schedule("Präsenz-Auffrischung", refresh, this::refreshTick);
        schedule("Sitzungsabgleich", reconcileTick, () -> startReconcile(false));
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (ScheduledFuture<?> future : scheduled) {
            future.cancel(false);
        }
        scheduled.clear();
        IllegalStateException stopped = closedException();
        for (LocalPlayer local : locals.values()) {
            local.result.completeExceptionally(stopped);
        }
        if (leader) {
            leader = false;
            executeIo("Leader-Rolle freigeben",
                    () -> shared.releaseLeadership(RECONCILER_ROLE, identity.instanceId()));
        }
    }

    /**
     * Führt sofort einen Sitzungsabgleich aus (z. B. {@code /buildreplay admin sync}), auch ohne Leader-Rolle -
     * dann ohne Online-Diff. Läuft bereits einer, wird dessen Ende geliefert.
     */
    public CompletableFuture<Void> reconcileNow() {
        return startReconcile(true);
    }

    // ------------------------------------------------------------------ Sessions

    @Override
    public void handleJoin(UUID player, String name, Instant joinedAt) {
        if (closed) {
            logger.fine("Join von " + name + " nach dem Beenden des Sitzungsdienstes ignoriert");
            return;
        }
        LocalPlayer local = new LocalPlayer(player, name, joinedAt,
                identity.instanceId() + ":" + player + ":" + joinedAt.toEpochMilli());
        CompletableFuture<NetworkSession> result = local.result;
        // nur der Hauptthread fügt ein - get und put sind hier daher nicht nebenläufig
        LocalPlayer previous = locals.get(player);
        if (previous != null) {
            endWithoutQuit(previous, joinedAt);
        }
        CompletableFuture<Void> after = previous != null ? previous.presenceTail : Futures.done();
        try {
            // Präsenz vor dem Überschreiben lesen: nur der vorherige Eintrag verrät einen Serverwechsel. Die
            // Kette muss stehen, bevor der Eintrag sichtbar wird (die Auffrischung hängt sich dort an).
            CompletableFuture<Optional<PresenceInfo>> joinStep = after.handle((ignored, error) -> (Void) null)
                    .thenApplyAsync(ignored -> joinPresence(local), tasks.io());
            synchronized (local) {
                local.presenceTail = joinStep.handle((ignored, error) -> null);
            }
            locals.put(player, local);
            joinStep.thenComposeAsync(prior -> resolveSession(local, prior), tasks.io())
                    .thenApplyAsync(session -> {
                        activate(local, session);
                        return session;
                    }, tasks.io())
                    .whenComplete((session, error) -> {
                        if (error == null) {
                            result.complete(session);
                            return;
                        }
                        Throwable cause = Futures.unwrap(error);
                        if (!closed) {
                            logger.warning("Netzwerksitzung von " + name + " konnte nicht bestimmt werden: "
                                    + cause.getMessage());
                        }
                        result.completeExceptionally(cause);
                    });
        } catch (RejectedExecutionException e) {
            logger.warning("Sitzungsauflösung für " + name + " nicht möglich: I/O-Pool nimmt keine Aufgaben an");
            result.completeExceptionally(e);
            locals.put(player, local);
        }
    }

    @Override
    public void handleQuit(UUID player, String name, Instant leftAt, VisitEndReason reason) {
        PresenceInfo left = new PresenceInfo(identity.serverName(), identity.instanceId(), leftAt, false);
        Duration grace = config.resilience().switchGrace();
        LocalPlayer local = locals.get(player);
        if (local == null) {
            logger.fine("Quit von " + name + " ohne bekannten Join");
            executeIo("Präsenz aktualisieren", () -> shared.markLeft(player, left, grace));
            return;
        }
        synchronized (local) {
            if (!local.present) {
                return; // doppelter Quit
            }
            local.present = false;
            local.leftAt = leftAt;
            local.endReason = reason;
            if (local.visitStarted && !local.visitEnded) {
                // Store-Schreibaufrufe kehren sofort zurück und dürfen auf dem Hauptthread laufen
                safeStore("Aufenthaltsende speichern", () -> store.endVisit(local.visitKey, leftAt, reason));
                local.visitEnded = true;
            }
            appendPresence(local, () -> safeShared(() -> shared.markLeft(player, left, grace)));
        }
    }

    /** Join ohne vorherigen Quit (verpasstes Event): offenen Aufenthalt des alten Eintrags beenden. */
    private void endWithoutQuit(LocalPlayer previous, Instant at) {
        synchronized (previous) {
            if (!previous.present) {
                return;
            }
            previous.present = false;
            previous.leftAt = at;
            previous.endReason = VisitEndReason.QUIT;
            if (previous.visitStarted && !previous.visitEnded) {
                safeStore("Aufenthaltsende speichern", () -> store.endVisit(previous.visitKey, at, VisitEndReason.QUIT));
                previous.visitEnded = true;
            }
        }
        logger.fine("Erneuter Join von " + previous.name + " ohne Quit - alter Aufenthalt beendet");
    }

    @Override
    public Optional<NetworkSession> current(UUID player) {
        LocalPlayer local = locals.get(player);
        if (local == null || !local.retainedAt(clock.instant(), QUIT_RETENTION)) {
            return Optional.empty();
        }
        return Optional.ofNullable(local.session);
    }

    @Override
    public CompletableFuture<NetworkSession> resolve(UUID player) {
        LocalPlayer local = locals.get(player);
        if (local == null || !local.retainedAt(clock.instant(), QUIT_RETENTION)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Spieler " + player + " ist auf diesem Server nicht bekannt"));
        }
        return local.result.copy();
    }

    @Override
    public SessionServiceStats stats() {
        Instant now = clock.instant();
        int present = 0;
        int resolved = 0;
        int provisional = 0;
        for (LocalPlayer local : locals.values()) {
            if (!local.retainedAt(now, QUIT_RETENTION)) {
                continue;
            }
            if (local.present) {
                present++;
            }
            NetworkSession session = local.session;
            if (session != null) {
                resolved++;
                if (session.provisional()) {
                    provisional++;
                }
            }
        }
        return new SessionServiceStats(present, resolved, provisional, cloud.lookups(), cloud.failures(), leader);
    }

    // ------------------------------------------------------------------ SimpleCloud-Events (Fremdthread)

    @Override
    public void onNetworkLogin(UUID player, @Nullable String sessionId, Instant timestamp) {
        if (closed || sessionId == null || sessionId.isBlank()) {
            return;
        }
        executeIo("Login-Event verarbeiten", () -> {
            Optional<CachedSession> cached = cachedSession(player);
            if (cached.isEmpty()) {
                return;
            }
            CachedSession session = cached.get();
            Instant login = session.loginTime();
            boolean stale = session.cloudSessionId() != null
                    ? !sessionId.equals(session.cloudSessionId())
                    : login == null || login.isBefore(timestamp.minus(SessionCloser.TIME_SLACK));
            if (stale) {
                safeShared(() -> shared.evictSession(player));
                logger.fine("Neuer Netzwerk-Login von " + player + " (" + NetworkSession.shortRef(sessionId)
                        + ") - alte Sitzung " + NetworkSession.shortRef(session.sessionKey()) + " aus dem Cache entfernt");
            }
        });
    }

    @Override
    public void onNetworkDisconnect(UUID player, Instant timestamp, long sessionDurationSeconds) {
        if (closed) {
            return;
        }
        if (!leader && !locals.containsKey(player)) {
            return; // erledigt der Leader bzw. der Server, auf dem der Spieler war
        }
        executeIo("Disconnect-Event verarbeiten",
                () -> handleDisconnect(player, timestamp, sessionDurationSeconds, 0));
    }

    @Override
    public void onServerSwitch(UUID player, @Nullable String fromServer, @Nullable String toServer,
                               Instant timestamp) {
        logger.fine("Serverwechsel von " + player + ": " + fromServer + " -> " + toServer);
    }

    // ------------------------------------------------------------------ Auflösung

    private Optional<PresenceInfo> joinPresence(LocalPlayer local) {
        Optional<PresenceInfo> prior = safeShared(() -> shared.presence(local.uuid), Optional.<PresenceInfo>empty());
        if (local.present) {
            safeShared(() -> shared.markPresent(local.uuid, presenceOf(local), config.resilience().presenceTtl()));
        }
        safeStore("Spieler speichern", () -> store.upsertPlayer(local.uuid, local.name, local.joinedAt));
        return prior;
    }

    private CompletableFuture<NetworkSession> resolveSession(LocalPlayer local, Optional<PresenceInfo> prior) {
        if (!cloud.configured()) {
            return fallback(local, prior);
        }
        Instant deadline = clock.instant().plus(config.simpleCloud().currentSessionRetryWindow());
        return lookupCurrent(local, deadline, 0).thenComposeAsync(found -> found.isPresent()
                ? CompletableFuture.completedFuture(found.get())
                : fallback(local, prior), tasks.io());
    }

    /** {@code /sessions/current} mit Wiederholung, solange SimpleCloud "nicht online" meldet. */
    private CompletableFuture<Optional<NetworkSession>> lookupCurrent(LocalPlayer local, Instant deadline,
                                                                      int attempt) {
        if (closed) {
            return CompletableFuture.failedFuture(closedException());
        }
        return cloud.currentSession(local.uuid).handleAsync((found, error) -> {
            if (error != null) {
                // Ausfall: sofort auf Cache/vorläufig ausweichen, die Hochstufung holt das später nach
                return CompletableFuture.completedFuture(Optional.<NetworkSession>empty());
            }
            if (found.isPresent()) {
                return CompletableFuture.completedFuture(Optional.of(fromCloud(local.uuid, found.get())));
            }
            Duration wait = attempt == 0 ? FIRST_RETRY_DELAY
                    : min(FIRST_RETRY_DELAY.plus(Duration.ofMillis(500L * attempt)), MAX_RETRY_DELAY);
            if (clock.instant().plus(wait).isAfter(deadline)) {
                logger.fine("SimpleCloud kennt noch keine aktuelle Sitzung von " + local.name + " (nach "
                        + (attempt + 1) + " Versuchen) - weiter ohne SimpleCloud");
                return CompletableFuture.completedFuture(Optional.<NetworkSession>empty());
            }
            return Futures.delay(tasks, wait)
                    .thenComposeAsync(ignored -> lookupCurrent(local, deadline, attempt + 1), tasks.io());
        }, tasks.io()).thenCompose(Function.identity());
    }

    private CompletableFuture<NetworkSession> fallback(LocalPlayer local, Optional<PresenceInfo> prior) {
        return tasks.supplyIo(() -> {
            Optional<CachedSession> cached = cachedSession(local.uuid);
            if (cached.isPresent() && looksLikeSwitch(prior, local.joinedAt)) {
                logger.fine("Serverwechsel von " + local.name + " erkannt - Sitzung "
                        + NetworkSession.shortRef(cached.get().sessionKey()) + " aus dem gemeinsamen Zustand");
                return Optional.of(fromCached(local.uuid, cached.get()));
            }
            return Optional.<NetworkSession>empty();
        }).thenCompose(found -> {
            if (found.isPresent()) {
                return CompletableFuture.completedFuture(found.get());
            }
            if (!config.resilience().provisionalSessions()) {
                return CompletableFuture.failedFuture(new IllegalStateException("SimpleCloud liefert keine Sitzung "
                        + "und vorläufige Sitzungen sind deaktiviert (resilience.provisional-sessions)"));
            }
            return provisional(local, prior);
        });
    }

    /** Vorläufige Sitzung unter der Sperre {@code session:<uuid>}; ein paralleler Auflöser findet sie im Cache. */
    private CompletableFuture<NetworkSession> provisional(LocalPlayer local, Optional<PresenceInfo> prior) {
        String lockName = "session:" + local.uuid;
        Instant lockDeadline = clock.instant().plus(LOCK_LEASE).plus(MIN_TICK);
        return acquireLock(lockName, lockDeadline).thenApplyAsync(token -> {
            try {
                Optional<CachedSession> cached = cachedSession(local.uuid);
                if (cached.isPresent() && (looksLikeSwitch(prior, local.joinedAt)
                        || resolvedRecently(cached.get(), local.joinedAt))) {
                    return fromCached(local.uuid, cached.get());
                }
                NetworkSession created = new NetworkSession(NetworkSession.newProvisionalKey(), null, local.uuid,
                        SessionSource.PROVISIONAL, local.joinedAt);
                cacheShared(local.uuid, created);
                // FINE: bei einem Ausfall träfe das jeden Join; der Ausfall selbst wird einmal gewarnt
                logger.fine("Vorläufige Netzwerksitzung " + created.shortRef() + " für " + local.name
                        + " angelegt (SimpleCloud nicht verfügbar) - wird später zusammengeführt");
                return created;
            } finally {
                token.ifPresent(t -> safeShared(() -> shared.unlock(t)));
            }
        }, tasks.io());
    }

    private CompletableFuture<Optional<LockToken>> acquireLock(String name, Instant deadline) {
        return tasks.supplyIo(() -> {
            try {
                return new LockAttempt(shared.tryLock(name, LOCK_LEASE), false);
            } catch (RuntimeException e) {
                logger.log(Level.FINE, "Sperre " + name + " nicht verfügbar", e);
                return new LockAttempt(Optional.empty(), true);
            }
        }).thenCompose(attempt -> {
            if (attempt.token().isPresent() || attempt.failed() || closed || !clock.instant().isBefore(deadline)) {
                if (attempt.token().isEmpty()) {
                    logger.fine("Sperre " + name + " nicht erhalten - fahre ohne Sperre fort");
                }
                return CompletableFuture.completedFuture(attempt.token());
            }
            return Futures.delay(tasks, LOCK_RETRY).thenCompose(ignored -> acquireLock(name, deadline));
        });
    }

    private record LockAttempt(Optional<LockToken> token, boolean failed) {
    }

    /** Sitzung übernehmen: MariaDB (Sitzung + Aufenthalt) und gemeinsamer Cache. */
    private void activate(LocalPlayer local, NetworkSession session) {
        safeStore("Sitzung speichern", () -> store.upsertSession(upsertOf(session)));
        synchronized (local) {
            local.session = session;
            if (!local.visitStarted) {
                safeStore("Aufenthalt speichern", () -> store.startVisit(new VisitStart(local.visitKey,
                        session.sessionKey(), local.uuid, local.name, identity.serverName(), identity.instanceId(),
                        local.joinedAt)));
                local.visitStarted = true;
            }
            Instant left = local.leftAt;
            if (left != null && !local.visitEnded) {
                VisitEndReason reason = local.endReason != null ? local.endReason : VisitEndReason.QUIT;
                safeStore("Aufenthaltsende speichern", () -> store.endVisit(local.visitKey, left, reason));
                local.visitEnded = true;
            }
        }
        Instant left = local.leftAt;
        if (local.present || (left != null
                && !clock.instant().isAfter(left.plus(config.resilience().switchGrace())))) {
            cache(local, session);
        }
    }

    private boolean looksLikeSwitch(Optional<PresenceInfo> prior, Instant joinedAt) {
        if (prior.isEmpty()) {
            return false;
        }
        PresenceInfo presence = prior.get();
        if (presence.online()) {
            return true;
        }
        return !presence.since().isBefore(joinedAt.minus(config.resilience().switchGrace()));
    }

    private boolean resolvedRecently(CachedSession cached, Instant joinedAt) {
        return !cached.resolvedAt().isBefore(joinedAt.minus(config.resilience().switchGrace()));
    }

    // ------------------------------------------------------------------ Hintergrund: Präsenz & Hochstufung

    private void refreshTick() {
        if (closed) {
            return;
        }
        Instant now = clock.instant();
        Duration halfCacheTtl = config.simpleCloud().sessionCacheTtl().dividedBy(2);
        for (LocalPlayer local : locals.values()) {
            if (!local.retainedAt(now, QUIT_RETENTION)) {
                locals.remove(local.uuid, local);
                continue;
            }
            if (!local.present) {
                continue;
            }
            appendPresence(local, () -> {
                if (local.present) {
                    safeShared(() -> shared.markPresent(local.uuid, presenceOf(local),
                            config.resilience().presenceTtl()));
                }
            });
            NetworkSession session = local.session;
            Instant cachedAt = local.cachedAt;
            if (session != null && (cachedAt == null || now.isAfter(cachedAt.plus(halfCacheTtl)))) {
                cache(local, session);
            }
        }
        upgradeSessions();
    }

    /** Vorläufige bzw. fehlgeschlagene Auflösungen anwesender Spieler über SimpleCloud nachholen. */
    private void upgradeSessions() {
        if (!cloud.configured() || cloud.circuitOpen()) {
            return;
        }
        int remaining = MAX_UPGRADES_PER_TICK;
        for (LocalPlayer local : locals.values()) {
            if (remaining <= 0) {
                break;
            }
            if (!local.present) {
                continue;
            }
            NetworkSession session = local.session;
            boolean needed = session != null ? session.provisional() : local.result.isCompletedExceptionally();
            if (!needed || !local.upgrading.compareAndSet(false, true)) {
                continue;
            }
            remaining--;
            cloud.currentSession(local.uuid).handleAsync((found, error) -> {
                try {
                    if (error == null && found.isPresent() && !closed) {
                        upgrade(local, found.get());
                    }
                } catch (RuntimeException e) {
                    logger.log(Level.WARNING, "Hochstufen der Sitzung von " + local.name + " fehlgeschlagen", e);
                } finally {
                    local.upgrading.set(false);
                }
                return null;
            }, tasks.io());
        }
    }

    private void upgrade(LocalPlayer local, CloudSession cloudSession) {
        NetworkSession upgraded = fromCloud(local.uuid, cloudSession);
        NetworkSession current = local.session;
        if (current == null) {
            activate(local, upgraded);
            local.result = CompletableFuture.completedFuture(upgraded);
            logger.info("Netzwerksitzung von " + local.name + " nachträglich über SimpleCloud bestimmt ("
                    + upgraded.shortRef() + ")");
            return;
        }
        if (!current.provisional()) {
            return;
        }
        Instant provisionalLogin = current.loginTime();
        if (provisionalLogin != null && !cloudSession.covers(provisionalLogin, SessionReconciler.COVER_SLACK)) {
            logger.fine("Aktuelle SimpleCloud-Sitzung von " + local.name + " beginnt nach der vorläufigen Sitzung "
                    + current.shortRef() + " - Zuordnung übernimmt der Abgleich");
            return;
        }
        safeStore("Sitzung zusammenführen",
                () -> store.mergeProvisionalSession(current.sessionKey(), upsertOf(cloudSession, local.uuid)));
        local.session = upgraded;
        local.result = CompletableFuture.completedFuture(upgraded);
        cache(local, upgraded);
        logger.info("Vorläufige Sitzung " + current.shortRef() + " von " + local.name + " mit SimpleCloud-Sitzung "
                + upgraded.shortRef() + " zusammengeführt");
    }

    private void onProvisionalMerged(UUID player, String provisionalKey, CloudSession target) {
        NetworkSession merged = fromCloud(player, target);
        Optional<CachedSession> cached = cachedSession(player);
        if (cached.isPresent() && provisionalKey.equals(cached.get().sessionKey())) {
            if (target.active()) {
                cacheShared(player, merged);
            } else {
                safeShared(() -> shared.evictSession(player));
            }
        }
        LocalPlayer local = locals.get(player);
        if (local != null) {
            NetworkSession session = local.session;
            if (session != null && provisionalKey.equals(session.sessionKey())) {
                local.session = merged;
                local.result = CompletableFuture.completedFuture(merged);
            }
        }
    }

    // ------------------------------------------------------------------ Sitzungsende

    private void handleDisconnect(UUID player, Instant timestamp, long durationSeconds, int attempt) {
        if (closed) {
            return;
        }
        if (attempt == 0 && !locals.containsKey(player) && cachedSession(player).isEmpty()) {
            return; // nie auf einem BuildReplay-Server aufgelöst - kein SimpleCloud-Aufruf nötig
        }
        if (!cloud.configured()) {
            closer.closeFromCache(player, timestamp, durationSeconds);
            return;
        }
        closer.closeLatest(player).whenCompleteAsync((result, error) -> {
            if (error != null) {
                closer.closeFromCache(player, timestamp, durationSeconds);
                return;
            }
            CloudSession latest = result.latest();
            if (result.outcome() == SessionCloser.Outcome.STILL_ACTIVE && latest != null
                    && latest.loginTime().isBefore(timestamp) && attempt < DISCONNECT_RECHECKS) {
                // SimpleCloud hat den Logout evtl. noch nicht gespeichert - kurz später erneut fragen
                Futures.delay(tasks, DISCONNECT_RECHECK)
                        .thenRun(() -> handleDisconnect(player, timestamp, durationSeconds, attempt + 1));
            }
        }, tasks.io());
    }

    private CompletableFuture<Void> startReconcile(boolean forced) {
        if (closed) {
            return Futures.done();
        }
        synchronized (reconcileLock) {
            CompletableFuture<Void> running = activeRun;
            if (running != null && !running.isDone()) {
                return forced ? running.copy() : Futures.done();
            }
            CompletableFuture<Void> run;
            try {
                run = tasks.supplyIo(this::renewLeadership).thenComposeAsync(isLeader -> {
                    Instant now = clock.instant();
                    if (!forced && (!isLeader || now.isBefore(nextReconcileAt))) {
                        return Futures.done();
                    }
                    nextReconcileAt = now.plus(config.simpleCloud().onlinePollInterval());
                    return reconciler.run(isLeader);
                }, tasks.io()).exceptionally(error -> {
                    logger.log(Level.WARNING, "Sitzungsabgleich fehlgeschlagen", Futures.unwrap(error));
                    return null;
                });
            } catch (RejectedExecutionException e) {
                return Futures.done();
            }
            activeRun = run;
            return run;
        }
    }

    private boolean renewLeadership() {
        boolean nowLeader = safeShared(() -> shared.tryAcquireOrRenewLeadership(RECONCILER_ROLE,
                identity.instanceId(), config.resilience().leaderLease()), Boolean.FALSE);
        if (nowLeader != leader) {
            logger.fine(nowLeader ? "Diese Instanz führt jetzt den Sitzungsabgleich"
                    : "Sitzungsabgleich wird von einer anderen Instanz geführt");
        }
        if (!nowLeader) {
            reconciler.resetSnapshot();
        }
        leader = nowLeader;
        return nowLeader;
    }

    // ------------------------------------------------------------------ Hilfen

    private void appendPresence(LocalPlayer local, Runnable operation) {
        synchronized (local) {
            try {
                local.presenceTail = local.presenceTail.handle((ignored, error) -> (Void) null)
                        .thenRunAsync(operation, tasks.io());
            } catch (RejectedExecutionException e) {
                logger.fine("Präsenz von " + local.name + " nicht aktualisiert: I/O-Pool nimmt keine Aufgaben an");
            }
        }
    }

    private void cache(LocalPlayer local, NetworkSession session) {
        cacheShared(local.uuid, session);
        local.cachedAt = clock.instant();
    }

    private void cacheShared(UUID player, NetworkSession session) {
        CachedSession cached = new CachedSession(session.sessionKey(), session.cloudSessionId(), session.source(),
                session.loginTime(), identity.instanceId(), clock.instant());
        safeShared(() -> shared.cacheSession(player, cached, config.simpleCloud().sessionCacheTtl()));
    }

    private Optional<CachedSession> cachedSession(UUID player) {
        return safeShared(() -> shared.cachedSession(player), Optional.<CachedSession>empty());
    }

    private PresenceInfo presenceOf(LocalPlayer local) {
        return new PresenceInfo(identity.serverName(), identity.instanceId(), local.joinedAt, true);
    }

    private static NetworkSession fromCloud(UUID player, CloudSession session) {
        return new NetworkSession(session.id(), session.id(), player, SessionSource.SIMPLECLOUD,
                session.loginTime());
    }

    private static NetworkSession fromCached(UUID player, CachedSession cached) {
        return new NetworkSession(cached.sessionKey(), cached.cloudSessionId(), player, cached.source(),
                cached.loginTime());
    }

    private static SessionUpsert upsertOf(NetworkSession session) {
        return new SessionUpsert(session.sessionKey(), session.cloudSessionId(), session.playerUuid(),
                session.source(), session.loginTime(), null, null);
    }

    private static SessionUpsert upsertOf(CloudSession session, UUID player) {
        return new SessionUpsert(session.id(), session.id(), player, SessionSource.SIMPLECLOUD, session.loginTime(),
                session.logoutTime(), SessionCloser.durationOf(session));
    }

    private void schedule(String what, Duration period, Runnable work) {
        long millis = period.toMillis();
        try {
            scheduled.add(tasks.scheduler().scheduleWithFixedDelay(() -> executeIo(what, work), millis, millis,
                    TimeUnit.MILLISECONDS));
        } catch (RejectedExecutionException e) {
            logger.warning(what + " konnte nicht geplant werden: Scheduler nimmt keine Aufgaben an");
        }
    }

    private void executeIo(String what, Runnable work) {
        try {
            tasks.io().execute(() -> {
                try {
                    work.run();
                } catch (RuntimeException e) {
                    logger.log(Level.WARNING, what + " fehlgeschlagen", e);
                }
            });
        } catch (RejectedExecutionException e) {
            logger.fine(what + " verworfen: I/O-Pool nimmt keine Aufgaben an");
        }
    }

    /** Redis-Zugriff; die Implementierung wirft laut Vertrag nicht - zur Sicherheit trotzdem abgefangen. */
    private void safeShared(Runnable operation) {
        try {
            operation.run();
        } catch (RuntimeException e) {
            logger.log(Level.FINE, "Zugriff auf den gemeinsamen Zustand fehlgeschlagen", e);
        }
    }

    private <T> T safeShared(Supplier<T> operation, T fallback) {
        try {
            T value = operation.get();
            return value != null ? value : fallback;
        } catch (RuntimeException e) {
            logger.log(Level.FINE, "Zugriff auf den gemeinsamen Zustand fehlgeschlagen", e);
            return fallback;
        }
    }

    private void safeStore(String what, Runnable operation) {
        try {
            operation.run();
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, what + " fehlgeschlagen", e);
        }
    }

    private static IllegalStateException closedException() {
        return new IllegalStateException("Sitzungsdienst wurde beendet");
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static Duration atLeast(Duration value) {
        return value.compareTo(MIN_TICK) < 0 ? MIN_TICK : value;
    }
}
