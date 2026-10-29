package net.techniqstone.buildReplay.state.redis;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.techniqstone.buildReplay.state.ActivityStamp;
import net.techniqstone.buildReplay.state.CachedSession;
import net.techniqstone.buildReplay.state.HandoffRequest;
import net.techniqstone.buildReplay.state.InstanceInfo;
import net.techniqstone.buildReplay.state.LockToken;
import net.techniqstone.buildReplay.state.PresenceInfo;
import net.techniqstone.buildReplay.state.RecordingState;
import net.techniqstone.buildReplay.state.SharedEvent;
import net.techniqstone.buildReplay.state.SharedState;
import net.techniqstone.buildReplay.state.SharedStateHealth;
import org.jspecify.annotations.Nullable;
import redis.clients.jedis.exceptions.JedisAccessControlException;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisException;

/**
 * {@link SharedState} mit transparentem Ausweichen: Solange Redis gesund ist, geht jeder Aufruf an
 * {@link RedisSharedState}. Schlägt ein Aufruf fehl, gilt Redis als nicht verfügbar ({@code lastError}), der Aufruf
 * wird aus {@link LocalSharedState} bedient und alle weiteren Aufrufe ebenfalls - ohne Wartezeit. Ein
 * Hintergrund-PING (Standard alle 5 s) stellt den Redis-Modus wieder her; der während des Ausfalls lokal gesammelte
 * Zustand wird dann verworfen (er war nie netzwerkweit sichtbar). Keine Methode wirft wegen Redis.
 *
 * <p>Besonderheiten:
 * <ul>
 *   <li>Sperren: Im Redis-Modus entscheidet allein Redis; eine noch gültige, während eines Ausfalls lokal vergebene
 *       Sperre ({@code distributed=false}) wird aber respektiert. Umgekehrt merkt sich die Instanz ihre gehaltenen
 *       Redis-Sperren, damit sie im Ausweichmodus nicht ein zweites Mal lokal vergeben werden. So schließen sich
 *       Threads dieser Instanz auch über einen Moduswechsel hinweg aus; netzwerkweit gelten Sperren im
 *       Ausweichmodus nicht.</li>
 *   <li>Herzschläge werden immer auch lokal geschrieben, damit {@link #liveInstances()} im Ausweichmodus sofort die
 *       eigene Instanz kennt.</li>
 *   <li>Pub/Sub: im Redis-Modus über den Kanal (eigene Events kommen über Redis zurück), im Ausweichmodus nur an
 *       die eigenen Empfänger. Ging die Antwort auf PUBLISH verloren, kann ein Event doppelt ankommen.</li>
 * </ul>
 */
public final class FailoverSharedState implements SharedState {

    public static final Duration DEFAULT_HEALTH_INTERVAL = Duration.ofSeconds(5);

    /** Ohne PONG über die Abo-Verbindung für so viele Prüfintervalle wird sie neu aufgebaut. */
    private static final int SUBSCRIBER_SILENCE_INTERVALS = 3;

    private final RedisSharedState redis;
    private final LocalSharedState local;
    private final Clock clock;
    private final Logger logger;
    private final Duration healthInterval;
    private final Object stateLock = new Object();
    /** Von dieser Instanz gehaltene Redis-Sperren (Name → Token) bis zum Lease-Ablauf. */
    private final ExpiringMap<String, String> heldRedisLocks;

    private volatile boolean healthy = true;
    private volatile boolean confirmed;
    private volatile @Nullable Instant lastSuccess;
    private volatile @Nullable String lastError;
    private volatile boolean closed;
    private @Nullable ScheduledExecutorService healthExecutor;

    /**
     * Startet noch keine Threads, siehe {@link #start()}. Bis zum ersten Fehler bzw. PING wird Redis als
     * erreichbar angenommen (kein kurzzeitiger lokaler Leader beim Start).
     *
     * @param healthInterval Abstand der Hintergrund-PINGs (mindestens 50 ms)
     */
    public FailoverSharedState(RedisSharedState redis, LocalSharedState local, Clock clock, Logger logger,
                               Duration healthInterval) {
        this.redis = redis;
        this.local = local;
        this.clock = clock;
        this.logger = logger;
        this.heldRedisLocks = new ExpiringMap<>(clock);
        this.healthInterval = healthInterval.compareTo(Duration.ofMillis(50)) < 0
                ? Duration.ofMillis(50) : healthInterval;
    }

    /** Startet den Gesundheitscheck (Daemon-Thread, erster PING sofort). Mehrfacher Aufruf ist harmlos. */
    public void start() {
        synchronized (stateLock) {
            if (closed || healthExecutor != null) {
                return;
            }
            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "BuildReplay-RedisHealth");
                thread.setDaemon(true);
                return thread;
            });
            healthExecutor = executor;
            executor.scheduleWithFixedDelay(this::healthCheck, 0L, healthInterval.toMillis(),
                    TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public boolean isDistributed() {
        return healthy && !closed;
    }

    @Override
    public SharedStateHealth health() {
        boolean connected = isDistributed();
        return new SharedStateHealth(true, connected, !connected, lastSuccess, lastError);
    }

    /** {@code true}, solange der Redis-Kanal abonniert ist. */
    boolean subscriptionActive() {
        return redis.subscriptionActive();
    }

    int subscriptionCount() {
        return redis.subscriptionCount();
    }

    // ---- Sitzungscache

    @Override
    public Optional<CachedSession> cachedSession(UUID player) {
        return call(r -> r.cachedSession(player), () -> local.cachedSession(player));
    }

    @Override
    public void cacheSession(UUID player, CachedSession session, Duration ttl) {
        run(r -> r.cacheSession(player, session, ttl), () -> local.cacheSession(player, session, ttl));
    }

    @Override
    public void evictSession(UUID player) {
        run(r -> r.evictSession(player), () -> local.evictSession(player));
    }

    // ---- Präsenz

    @Override
    public void markPresent(UUID player, PresenceInfo presence, Duration ttl) {
        run(r -> r.markPresent(player, presence, ttl), () -> local.markPresent(player, presence, ttl));
    }

    @Override
    public void markLeft(UUID player, PresenceInfo presence, Duration grace) {
        run(r -> r.markLeft(player, presence, grace), () -> local.markLeft(player, presence, grace));
    }

    @Override
    public Optional<PresenceInfo> presence(UUID player) {
        return call(r -> r.presence(player), () -> local.presence(player));
    }

    // ---- Aufnahmestatus

    @Override
    public void putRecordingState(UUID player, RecordingState state, Duration ttl) {
        run(r -> r.putRecordingState(player, state, ttl), () -> local.putRecordingState(player, state, ttl));
    }

    @Override
    public void clearRecordingState(UUID player, String rawName) {
        run(r -> r.clearRecordingState(player, rawName), () -> local.clearRecordingState(player, rawName));
    }

    @Override
    public Optional<RecordingState> recordingState(UUID player) {
        return call(r -> r.recordingState(player), () -> local.recordingState(player));
    }

    // ---- Aktivität

    @Override
    public void putActivity(UUID player, ActivityStamp stamp, Duration ttl) {
        run(r -> r.putActivity(player, stamp, ttl), () -> local.putActivity(player, stamp, ttl));
    }

    @Override
    public Optional<ActivityStamp> activity(UUID player) {
        return call(r -> r.activity(player), () -> local.activity(player));
    }

    // ---- Sperren

    @Override
    public Optional<LockToken> tryLock(String name, Duration lease) {
        String token = UUID.randomUUID().toString();
        if (isDistributed()) {
            if (local.isLocked(name)) {
                return Optional.empty();
            }
            try {
                boolean acquired = redis.tryLock(name, lease, token);
                succeeded();
                if (!acquired) {
                    return Optional.empty();
                }
                heldRedisLocks.put(name, token, lease);
                heldRedisLocks.purgeExpired();
                return Optional.of(new LockToken(name, token, true));
            } catch (RuntimeException e) {
                failed(e);
            }
        }
        if (heldRedisLocks.get(name).isPresent()) {
            return Optional.empty();
        }
        // gleiches Token: hat Redis das SET trotz Fehler ausgeführt, räumt unlock() den Schlüssel später mit ab
        return local.tryLock(name, lease, token)
                ? Optional.of(new LockToken(name, token, false))
                : Optional.empty();
    }

    /**
     * Verteilte Tokens werden per Compare-and-Delete in Redis freigegeben (ist Redis gerade weg, läuft die Sperre
     * mit ihrer Lease ab; Ergebnis dann {@code true}, wenn diese Instanz sie noch hielt). Lokale Tokens werden lokal
     * freigegeben und - falls Redis erreichbar ist - vorsorglich auch in Redis, falls der Schlüssel trotz
     * Fehlermeldung gesetzt wurde.
     */
    @Override
    public boolean unlock(LockToken token) {
        if (token.distributed()) {
            boolean heldHere = heldRedisLocks.removeIf(token.name(), held -> held.equals(token.token()));
            return call(r -> r.unlock(token.name(), token.token()), () -> heldHere);
        }
        boolean releasedLocally = local.unlock(token.name(), token.token());
        run(r -> r.unlock(token.name(), token.token()), () -> { });
        return releasedLocally;
    }

    // ---- Leader

    @Override
    public boolean tryAcquireOrRenewLeadership(String role, String instanceId, Duration lease) {
        return call(r -> r.tryAcquireOrRenewLeadership(role, instanceId, lease),
                () -> local.tryAcquireOrRenewLeadership(role, instanceId, lease));
    }

    @Override
    public void releaseLeadership(String role, String instanceId) {
        local.releaseLeadership(role, instanceId);
        run(r -> r.releaseLeadership(role, instanceId), () -> { });
    }

    // ---- Instanzen

    @Override
    public void heartbeat(InstanceInfo info, Duration ttl) {
        local.heartbeat(info, ttl);
        run(r -> r.heartbeat(info, ttl), () -> { });
    }

    @Override
    public List<InstanceInfo> liveInstances() {
        return call(RedisSharedState::liveInstances, local::liveInstances);
    }

    // ---- Übergabe

    @Override
    public void putHandoff(UUID player, HandoffRequest request, Duration ttl) {
        run(r -> r.putHandoff(player, request, ttl), () -> local.putHandoff(player, request, ttl));
    }

    @Override
    public Optional<HandoffRequest> takeHandoff(UUID player) {
        return call(r -> r.takeHandoff(player), () -> local.takeHandoff(player));
    }

    // ---- Pub/Sub

    @Override
    public void publish(SharedEvent event) {
        run(r -> r.publish(event), () -> local.publish(event));
    }

    /** Registriert den Empfänger für beide Wege (Redis-Kanal und lokale Zustellung im Ausweichmodus). */
    @Override
    public void subscribe(Consumer<SharedEvent> listener) {
        local.subscribe(listener);
        if (!closed) {
            redis.subscribe(listener);
        }
    }

    // ---- Ausweichlogik

    private <T> T call(Function<RedisSharedState, T> remote, Supplier<T> fallback) {
        if (isDistributed()) {
            try {
                T result = remote.apply(redis);
                succeeded();
                return result;
            } catch (RuntimeException e) {
                failed(e);
            }
        }
        return fallback.get();
    }

    private void run(Consumer<RedisSharedState> remote, Runnable fallback) {
        call(r -> {
            remote.accept(r);
            return Boolean.TRUE;
        }, () -> {
            fallback.run();
            return Boolean.FALSE;
        });
    }

    private void succeeded() {
        lastSuccess = clock.instant();
        if (!confirmed) {
            synchronized (stateLock) {
                if (!confirmed) {
                    confirmed = true;
                    logger.info("Redis verbunden (" + redis.description() + ") - Zustand wird netzwerkweit geteilt");
                }
            }
        }
    }

    private void failed(RuntimeException error) {
        if (closed) {
            return;
        }
        String reason = RedisSharedState.describe(error);
        lastError = reason;
        boolean transition;
        synchronized (stateLock) {
            transition = healthy;
            healthy = false;
        }
        if (transition) {
            logger.warning(failureKind(error) + " (" + redis.description() + ": " + reason
                    + ") - verwende lokalen Zustand, Sperren und Leader gelten nur für diese Instanz");
            if (!(error instanceof JedisException)) {
                logger.log(Level.FINE, "Unerwarteter Fehler im Redis-Zugriff", error);
            }
        }
    }

    private static String failureKind(RuntimeException error) {
        if (error instanceof JedisConnectionException) {
            return "Redis nicht erreichbar";
        }
        if (error instanceof JedisAccessControlException) {
            return "Redis-Anmeldung abgelehnt (Benutzer/Passwort prüfen)";
        }
        return "Redis-Befehl fehlgeschlagen";
    }

    private void healthCheck() {
        if (closed) {
            return;
        }
        try {
            redis.ping();
            succeeded();
            boolean recovered;
            synchronized (stateLock) {
                recovered = !healthy && !closed;
                healthy = true;
            }
            if (recovered) {
                local.resetAfterOutage();
                logger.info("Redis wieder erreichbar (" + redis.description()
                        + ") - geteilter Zustand ist wieder netzwerkweit");
            }
        } catch (RuntimeException e) {
            failed(e);
        }
        try {
            redis.maintainSubscriber(healthInterval.multipliedBy(SUBSCRIBER_SILENCE_INTERVALS));
        } catch (RuntimeException e) {
            logger.log(Level.FINE, "Prüfung des Redis-Abos fehlgeschlagen", e);
        }
    }

    @Override
    public void close() {
        ScheduledExecutorService executor;
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            closed = true;
            executor = healthExecutor;
            healthExecutor = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        redis.close();
        local.close();
    }
}
