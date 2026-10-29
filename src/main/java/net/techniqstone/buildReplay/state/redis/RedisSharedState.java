package net.techniqstone.buildReplay.state.redis;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.techniqstone.buildReplay.config.PluginConfig;
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
import redis.clients.jedis.ConnectionPoolConfig;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.SslOptions;
import redis.clients.jedis.exceptions.JedisException;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.params.SetParams;
import redis.clients.jedis.resps.ScanResult;

/**
 * {@link SharedState} direkt auf Redis (Jedis 7, gepoolter {@link RedisClient}).
 *
 * <p><b>Wirft bei Verbindungs- und Redis-Fehlern {@link JedisException}</b> - im Plugin daher nie direkt benutzen,
 * sondern über {@link FailoverSharedState} (siehe {@link SharedStates#create}), der dann auf den lokalen Zustand
 * ausweicht. Ungültige Einträge (fremdes Format) gelten als nicht vorhanden.
 *
 * <p>Schlüssel unter {@code keyPrefix}: {@code session:{uuid}}, {@code presence:{uuid}}, {@code activity:{uuid}},
 * {@code handoff:{uuid}}, {@code instance:{id}} (JSON mit PX-TTL), {@code rec:{uuid}} (Hash
 * raw/server/instance/since mit PEXPIRE), {@code lock:{name}} (Token), {@code leader:{role}} (Instanz-ID),
 * Pub/Sub-Kanal {@code events}. Sperren, Leader, Aufnahmestatus und Übergaben werden per Lua atomar geprüft
 * (Compare-and-Delete bzw. Get-and-Delete, kompatibel ab Redis 2.6).
 *
 * <p>Pub/Sub läuft auf einer eigenen, ungepoolten Verbindung in einem Daemon-Thread, der nach Verbindungsverlust
 * selbstständig neu abonniert (Backoff 0,5 s bis 5 s). {@link #maintainSubscriber} erkennt zusätzlich halboffene
 * Verbindungen per PING.
 */
public final class RedisSharedState implements SharedState {

    private static final long SUBSCRIBE_BACKOFF_START_MS = 500;
    private static final long SUBSCRIBE_BACKOFF_MAX_MS = 5_000;
    private static final int SCAN_COUNT = 200;
    private static final int MGET_BATCH = 200;

    /** KEYS[1]=Schlüssel, ARGV[1]=erwarteter Wert; löscht nur bei Übereinstimmung. */
    static final String COMPARE_AND_DELETE = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """;

    /** KEYS[1]=leader-Schlüssel, ARGV[1]=Instanz-ID, ARGV[2]=Lease in ms; erwirbt (SET NX PX) oder verlängert. */
    static final String ACQUIRE_OR_RENEW = """
            local current = redis.call('GET', KEYS[1])
            if current == ARGV[1] then
              redis.call('PEXPIRE', KEYS[1], ARGV[2])
              return 1
            end
            if not current then
              redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
              return 1
            end
            return 0
            """;

    /**
     * KEYS[1]=presence-Schlüssel, ARGV[1]=JSON (online=false), ARGV[2]=Instanz-ID, ARGV[3]=Karenz in ms.
     * Überschreibt nur einen fehlenden oder eigenen Eintrag; der neuere Eintrag eines anderen Servers bleibt.
     */
    static final String MARK_LEFT = """
            local current = redis.call('GET', KEYS[1])
            if current then
              local ok, decoded = pcall(cjson.decode, current)
              if ok and type(decoded) == 'table' and decoded['instanceId'] ~= nil
                  and decoded['instanceId'] ~= ARGV[2] then
                return 0
              end
            end
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[3])
            return 1
            """;

    /** KEYS[1]=rec-Schlüssel, ARGV[1..4]=raw/server/instance/since, ARGV[5]=TTL in ms. */
    static final String PUT_RECORDING = """
            redis.call('DEL', KEYS[1])
            redis.call('HSET', KEYS[1], 'raw', ARGV[1], 'server', ARGV[2], 'instance', ARGV[3], 'since', ARGV[4])
            redis.call('PEXPIRE', KEYS[1], ARGV[5])
            return 1
            """;

    /** KEYS[1]=rec-Schlüssel, ARGV[1]=Name der Pufferaufnahme; löscht nur, wenn das Feld raw passt. */
    static final String CLEAR_RECORDING = """
            if redis.call('HGET', KEYS[1], 'raw') == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """;

    /** KEYS[1]=handoff-Schlüssel; liefert den Wert und löscht ihn atomar (GETDEL erst ab Redis 6.2). */
    static final String GET_AND_DELETE = """
            local value = redis.call('GET', KEYS[1])
            if value then
              redis.call('DEL', KEYS[1])
            end
            return value
            """;

    private final RedisKeys keys;
    private final StateJson json;
    private final Clock clock;
    private final Logger logger;
    private final HostAndPort address;
    private final JedisClientConfig clientConfig;
    private final RedisClient client;
    private final String description;
    private final EventDispatcher events;
    private final CountDownLatch closeSignal = new CountDownLatch(1);

    private final Object subscriberLock = new Object();
    private @Nullable Thread subscriberThread;
    private volatile @Nullable Subscriber subscriber;
    private volatile @Nullable Jedis subscriberConnection;
    private final AtomicInteger subscriptions = new AtomicInteger();
    private volatile boolean subscriptionFailureLogged;
    private volatile boolean everSubscribed;

    private volatile @Nullable Instant lastSuccess;
    private volatile @Nullable String lastError;
    private volatile boolean closed;

    /**
     * Baut den Verbindungspool auf, ohne sich schon zu verbinden (keine I/O im Konstruktor).
     *
     * @param instanceId erscheint als Client-Name in {@code CLIENT LIST}
     */
    public RedisSharedState(PluginConfig.RedisSettings settings, String instanceId, Clock clock, Logger logger) {
        this.keys = new RedisKeys(settings.keyPrefix());
        this.json = new StateJson(logger);
        this.clock = clock;
        this.logger = logger;
        this.address = new HostAndPort(settings.host(), settings.port());
        this.description = settings.host() + ":" + settings.port() + "/" + settings.database()
                + (settings.ssl() ? " (TLS)" : "");
        int timeoutMillis = (int) Math.max(100L, Math.min(Integer.MAX_VALUE, settings.timeout().toMillis()));
        DefaultJedisClientConfig.Builder config = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(timeoutMillis)
                .socketTimeoutMillis(timeoutMillis)
                .database(settings.database())
                .clientName(clientName(instanceId));
        if (settings.ssl()) {
            config.sslOptions(SslOptions.defaults());
        }
        if (!settings.username().isBlank()) {
            config.user(settings.username());
        }
        if (!settings.password().isEmpty()) {
            config.password(settings.password());
        }
        this.clientConfig = config.build();

        int poolSize = Math.max(1, settings.poolSize());
        ConnectionPoolConfig pool = new ConnectionPoolConfig();
        pool.setMaxTotal(poolSize);
        pool.setMaxIdle(poolSize);
        pool.setMinIdle(0);
        pool.setBlockWhenExhausted(true);
        pool.setMaxWait(Duration.ofMillis(timeoutMillis));
        pool.setTestWhileIdle(true);
        pool.setTimeBetweenEvictionRuns(Duration.ofSeconds(30));
        pool.setJmxEnabled(false);
        this.client = RedisClient.builder()
                .hostAndPort(address)
                .clientConfig(clientConfig)
                .poolConfig(pool)
                .build();
        this.events = new EventDispatcher("BuildReplay-RedisEvents", logger);
    }

    /** Adresse ohne Zugangsdaten, z. B. für Logmeldungen. */
    String description() {
        return description;
    }

    /** {@code PING}; wirft bei Fehlern. */
    void ping() {
        String reply = exec(() -> client.ping());
        if (!"PONG".equalsIgnoreCase(reply)) {
            throw new JedisException("Unerwartete Antwort auf PING: " + reply);
        }
    }

    @Override
    public boolean isDistributed() {
        return !closed;
    }

    @Override
    public SharedStateHealth health() {
        Instant success = lastSuccess;
        boolean connected = !closed && success != null;
        return new SharedStateHealth(true, connected, false, success, lastError);
    }

    // ---- Sitzungscache

    @Override
    public Optional<CachedSession> cachedSession(UUID player) {
        return Optional.ofNullable(json.session(exec(() -> client.get(keys.session(player)))));
    }

    @Override
    public void cacheSession(UUID player, CachedSession session, Duration ttl) {
        setWithTtl(keys.session(player), json.encode(session), ttl);
    }

    @Override
    public void evictSession(UUID player) {
        exec(() -> client.del(keys.session(player)));
    }

    // ---- Präsenz

    @Override
    public void markPresent(UUID player, PresenceInfo presence, Duration ttl) {
        setWithTtl(keys.presence(player), json.encode(presence), ttl);
    }

    /** Wie {@link LocalSharedState#markLeft}: nur fehlende oder eigene Einträge werden überschrieben (Lua). */
    @Override
    public void markLeft(UUID player, PresenceInfo presence, Duration grace) {
        String offline = json.encode(LocalSharedState.offline(presence));
        exec(() -> client.eval(MARK_LEFT, List.of(keys.presence(player)),
                List.of(offline, presence.instanceId(), Long.toString(Leases.millis(grace)))));
    }

    @Override
    public Optional<PresenceInfo> presence(UUID player) {
        return Optional.ofNullable(json.presence(exec(() -> client.get(keys.presence(player)))));
    }

    // ---- Aufnahmestatus

    @Override
    public void putRecordingState(UUID player, RecordingState state, Duration ttl) {
        exec(() -> client.eval(PUT_RECORDING, List.of(keys.recording(player)), List.of(state.rawName(),
                state.serverName(), state.instanceId(), state.since().toString(),
                Long.toString(Leases.millis(ttl)))));
    }

    @Override
    public void clearRecordingState(UUID player, String rawName) {
        exec(() -> client.eval(CLEAR_RECORDING, List.of(keys.recording(player)), List.of(rawName)));
    }

    @Override
    public Optional<RecordingState> recordingState(UUID player) {
        Map<String, String> hash = exec(() -> client.hgetAll(keys.recording(player)));
        if (hash == null || hash.isEmpty()) {
            return Optional.empty();
        }
        String raw = hash.get("raw");
        String server = hash.get("server");
        String instance = hash.get("instance");
        String since = hash.get("since");
        if (raw == null || server == null || instance == null || since == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new RecordingState(raw, server, instance, Instant.parse(since)));
        } catch (DateTimeParseException e) {
            logger.fine("Ungültiger Aufnahmestatus in Redis wird ignoriert: " + e.getMessage());
            return Optional.empty();
        }
    }

    // ---- Aktivität

    @Override
    public void putActivity(UUID player, ActivityStamp stamp, Duration ttl) {
        setWithTtl(keys.activity(player), json.encode(stamp), ttl);
    }

    @Override
    public Optional<ActivityStamp> activity(UUID player) {
        return Optional.ofNullable(json.activity(exec(() -> client.get(keys.activity(player)))));
    }

    // ---- Sperren

    @Override
    public Optional<LockToken> tryLock(String name, Duration lease) {
        String token = UUID.randomUUID().toString();
        return tryLock(name, lease, token) ? Optional.of(new LockToken(name, token, true)) : Optional.empty();
    }

    /** {@code SET lock:{name} token NX PX lease}. */
    boolean tryLock(String name, Duration lease, String token) {
        String reply = exec(() -> client.set(keys.lock(name), token,
                SetParams.setParams().nx().px(Leases.millis(lease))));
        return "OK".equalsIgnoreCase(reply);
    }

    @Override
    public boolean unlock(LockToken token) {
        return unlock(token.name(), token.token());
    }

    /** Compare-and-Delete: gibt nur frei, wenn die Sperre noch dieses Token trägt. */
    boolean unlock(String name, String token) {
        return asLong(exec(() -> client.eval(COMPARE_AND_DELETE, List.of(keys.lock(name)), List.of(token)))) == 1L;
    }

    // ---- Leader

    @Override
    public boolean tryAcquireOrRenewLeadership(String role, String instanceId, Duration lease) {
        return asLong(exec(() -> client.eval(ACQUIRE_OR_RENEW, List.of(keys.leader(role)),
                List.of(instanceId, Long.toString(Leases.millis(lease)))))) == 1L;
    }

    @Override
    public void releaseLeadership(String role, String instanceId) {
        exec(() -> client.eval(COMPARE_AND_DELETE, List.of(keys.leader(role)), List.of(instanceId)));
    }

    // ---- Instanzen

    @Override
    public void heartbeat(InstanceInfo info, Duration ttl) {
        setWithTtl(keys.instance(info.instanceId()), json.encode(info), ttl);
    }

    /** {@code SCAN instance:*} und {@code MGET} in Blöcken; zwischenzeitlich abgelaufene Einträge fehlen. */
    @Override
    public List<InstanceInfo> liveInstances() {
        return exec(() -> {
            LinkedHashSet<String> found = new LinkedHashSet<>();
            ScanParams params = new ScanParams().match(keys.instancePattern()).count(SCAN_COUNT);
            String cursor = ScanParams.SCAN_POINTER_START;
            do {
                ScanResult<String> page = client.scan(cursor, params);
                found.addAll(page.getResult());
                cursor = page.getCursor();
            } while (!ScanParams.SCAN_POINTER_START.equals(cursor));

            List<String> all = new ArrayList<>(found);
            List<InstanceInfo> result = new ArrayList<>(all.size());
            for (int from = 0; from < all.size(); from += MGET_BATCH) {
                List<String> batch = all.subList(from, Math.min(all.size(), from + MGET_BATCH));
                for (String value : client.mget(batch.toArray(String[]::new))) {
                    InstanceInfo info = json.instance(value);
                    if (info != null) {
                        result.add(info);
                    }
                }
            }
            result.sort(Comparator.comparing(InstanceInfo::instanceId));
            return List.copyOf(result);
        });
    }

    // ---- Übergabe

    @Override
    public void putHandoff(UUID player, HandoffRequest request, Duration ttl) {
        setWithTtl(keys.handoff(player), json.encode(request), ttl);
    }

    @Override
    public Optional<HandoffRequest> takeHandoff(UUID player) {
        Object reply = exec(() -> client.eval(GET_AND_DELETE, List.of(keys.handoff(player)), List.of()));
        return Optional.ofNullable(json.handoff(asString(reply)));
    }

    // ---- Pub/Sub

    @Override
    public void publish(SharedEvent event) {
        String payload = json.encode(event);
        exec(() -> client.publish(keys.eventChannel(), payload));
    }

    /** Registriert den Empfänger und startet beim ersten Aufruf den Abo-Thread (keine I/O auf dem Aufrufer). */
    @Override
    public void subscribe(Consumer<SharedEvent> listener) {
        events.add(listener);
        synchronized (subscriberLock) {
            if (closed || subscriberThread != null) {
                return;
            }
            Thread thread = new Thread(this::subscriberLoop, "BuildReplay-RedisPubSub");
            thread.setDaemon(true);
            subscriberThread = thread;
            thread.start();
        }
    }

    /** {@code true}, solange der Kanal abonniert ist (Events anderer Instanzen kommen an). */
    boolean subscriptionActive() {
        Subscriber current = subscriber;
        return current != null && current.active;
    }

    /** Wie oft der Kanal bisher erfolgreich abonniert wurde (steigt bei jedem Neuabo, für Tests). */
    int subscriptionCount() {
        return subscriptions.get();
    }

    /**
     * Prüft das Abo auf halboffene Verbindungen: sendet ein PING über die Abo-Verbindung und baut sie neu auf,
     * wenn seit {@code silenceLimit} kein PONG mehr kam. Vom Gesundheitscheck regelmäßig aufgerufen.
     */
    void maintainSubscriber(Duration silenceLimit) {
        Subscriber current = subscriber;
        if (closed || current == null || !current.active) {
            return;
        }
        long silentNanos = System.nanoTime() - current.lastPongNanos;
        if (silentNanos > silenceLimit.toNanos()) {
            logger.warning("Redis-Pub/Sub antwortet seit " + Duration.ofNanos(silentNanos).toSeconds()
                    + " s nicht - Abo wird neu aufgebaut");
            current.active = false;
            dropSubscriberConnection();
            return;
        }
        try {
            current.ping();
        } catch (RuntimeException e) {
            logger.fine("PING über das Redis-Abo fehlgeschlagen (" + describe(e) + ") - Abo wird neu aufgebaut");
            current.active = false;
            dropSubscriberConnection();
        }
    }

    private void subscriberLoop() {
        long backoff = SUBSCRIBE_BACKOFF_START_MS;
        while (!closed) {
            Subscriber current = new Subscriber();
            try (Jedis connection = new Jedis(address, clientConfig)) {
                subscriberConnection = connection;
                subscriber = current;
                if (closed) {
                    break;
                }
                connection.subscribe(current, keys.eventChannel());
            } catch (RuntimeException e) {
                if (!closed) {
                    onSubscriptionFailure(current.wasActive, e);
                }
            } finally {
                current.active = false;
                subscriber = null;
                subscriberConnection = null;
            }
            if (current.wasActive) {
                backoff = SUBSCRIBE_BACKOFF_START_MS;
            }
            if (closed) {
                break;
            }
            try {
                if (closeSignal.await(backoff, TimeUnit.MILLISECONDS)) {
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            backoff = Math.min(SUBSCRIBE_BACKOFF_MAX_MS, backoff * 2);
        }
    }

    private void onSubscriptionFailure(boolean wasActive, RuntimeException error) {
        if (wasActive || !subscriptionFailureLogged) {
            subscriptionFailureLogged = true;
            logger.warning((wasActive ? "Redis-Pub/Sub-Verbindung verloren" : "Redis-Pub/Sub nicht verbunden")
                    + " (" + describe(error) + ") - neuer Versuch im Hintergrund");
        } else {
            logger.fine("Redis-Pub/Sub weiterhin nicht verbunden: " + describe(error));
        }
    }

    private void dropSubscriberConnection() {
        Jedis connection = subscriberConnection;
        if (connection != null) {
            try {
                connection.close();
            } catch (RuntimeException ignored) {
                // Verbindung ist ohnehin defekt
            }
        }
    }

    /** Ein Abo pro Verbindung; nach Verbindungsverlust wird ein neues erzeugt. */
    private final class Subscriber extends JedisPubSub {
        volatile boolean active;
        volatile boolean wasActive;
        volatile long lastPongNanos = System.nanoTime();

        @Override
        public void onSubscribe(String channel, int subscribedChannels) {
            lastPongNanos = System.nanoTime();
            active = true;
            wasActive = true;
            subscriptions.incrementAndGet();
            if (everSubscribed && subscriptionFailureLogged) {
                logger.info("Redis-Pub/Sub wieder verbunden (" + description + ")");
            } else {
                logger.fine("Redis-Pub/Sub abonniert: " + channel);
            }
            everSubscribed = true;
            subscriptionFailureLogged = false;
        }

        @Override
        public void onUnsubscribe(String channel, int subscribedChannels) {
            active = false;
        }

        @Override
        public void onPong(String pattern) {
            lastPongNanos = System.nanoTime();
        }

        @Override
        public void onMessage(String channel, String message) {
            lastPongNanos = System.nanoTime();
            SharedEvent event = json.event(message);
            if (event != null) {
                events.dispatch(event);
            }
        }
    }

    // ---- Hilfsfunktionen

    private void setWithTtl(String key, String value, Duration ttl) {
        exec(() -> client.set(key, value, SetParams.setParams().px(Leases.millis(ttl))));
    }

    private <T> T exec(java.util.function.Supplier<T> command) {
        try {
            T result = command.get();
            lastSuccess = clock.instant();
            return result;
        } catch (RuntimeException e) {
            lastError = describe(e);
            throw e;
        }
    }

    private static long asLong(@Nullable Object reply) {
        if (reply instanceof Long value) {
            return value;
        }
        if (reply instanceof Number value) {
            return value.longValue();
        }
        if (reply instanceof Boolean value) {
            return value ? 1L : 0L;
        }
        return 0L;
    }

    private static @Nullable String asString(@Nullable Object reply) {
        if (reply instanceof String value) {
            return value;
        }
        if (reply instanceof byte[] value) {
            return new String(value, StandardCharsets.UTF_8);
        }
        return null;
    }

    /** Fehlerbeschreibung ohne Zugangsdaten (Jedis-Meldungen enthalten keine Passwörter). */
    static String describe(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause
                && (cause.getMessage() == null || cause.getMessage().startsWith("Failed to connect")
                || cause.getMessage().startsWith("Could not get a resource"))) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    static String clientName(String instanceId) {
        String cleaned = ("BuildReplay-" + instanceId).replaceAll("[^A-Za-z0-9_.:#-]", "_");
        return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
    }

    @Override
    public void close() {
        Thread thread;
        synchronized (subscriberLock) {
            if (closed) {
                return;
            }
            closed = true;
            thread = subscriberThread;
        }
        closeSignal.countDown();
        Subscriber current = subscriber;
        if (current != null && current.active) {
            try {
                current.unsubscribe();
            } catch (RuntimeException ignored) {
                // Verbindung bereits weg - wird unten geschlossen
            }
        }
        if (thread != null) {
            joinQuietly(thread, 500);
            if (thread.isAlive()) {
                dropSubscriberConnection();
                thread.interrupt();
                joinQuietly(thread, 1_500);
            }
            if (thread.isAlive()) {
                logger.log(Level.FINE, "Redis-Pub/Sub-Thread beendet sich nicht rechtzeitig (Daemon)");
            }
        }
        events.close();
        try {
            client.close();
        } catch (RuntimeException e) {
            logger.fine("Schließen des Redis-Pools fehlgeschlagen: " + describe(e));
        }
    }

    private static void joinQuietly(Thread thread, long millis) {
        try {
            thread.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
