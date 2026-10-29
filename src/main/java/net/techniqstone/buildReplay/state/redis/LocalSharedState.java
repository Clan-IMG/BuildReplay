package net.techniqstone.buildReplay.state.redis;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
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

/**
 * {@link SharedState} im Arbeitsspeicher dieser Instanz: Redis deaktiviert oder Ausweichzustand während eines
 * Redis-Ausfalls. Semantik wie {@link RedisSharedState}, aber nur für diese Instanz: Sperren und Leader gelten
 * lokal, Pub/Sub stellt nur an die eigenen Empfänger zu. Lebensdauern werden mit der übergebenen {@link Clock}
 * gemessen (Ablauf lazy beim Zugriff, abgelaufene Einträge werden zusätzlich regelmäßig beim Schreiben
 * aufgeräumt). Threadsicher; kein Aufruf blockiert. Eignet sich auch als {@link SharedState}-Fake in Tests anderer
 * Module.
 */
public final class LocalSharedState implements SharedState {

    private static final Duration PURGE_INTERVAL = Duration.ofSeconds(30);

    private final Clock clock;
    private final boolean enabled;
    private final @Nullable String lastError;
    private final ExpiringMap<UUID, CachedSession> sessions;
    private final ExpiringMap<UUID, PresenceInfo> presences;
    private final ExpiringMap<UUID, RecordingState> recordings;
    private final ExpiringMap<UUID, ActivityStamp> activities;
    private final ExpiringMap<String, String> locks;
    private final ExpiringMap<String, String> leaders;
    private final ExpiringMap<String, InstanceInfo> instances;
    private final ExpiringMap<UUID, HandoffRequest> handoffs;
    private final EventDispatcher events;
    private volatile Instant nextPurge;
    private volatile boolean closed;

    /** Lokaler Zustand ohne Redis ({@code health().enabled() == false}). */
    public LocalSharedState(Clock clock, Logger logger) {
        this(clock, logger, false, null);
    }

    /**
     * @param enabled   was {@link #health()} als {@code enabled} meldet (Redis konfiguriert, aber nicht nutzbar)
     * @param lastError Grund, warum nur lokal gearbeitet wird (ohne Geheimnisse)
     */
    LocalSharedState(Clock clock, Logger logger, boolean enabled, @Nullable String lastError) {
        this.clock = clock;
        this.enabled = enabled;
        this.lastError = lastError;
        this.sessions = new ExpiringMap<>(clock);
        this.presences = new ExpiringMap<>(clock);
        this.recordings = new ExpiringMap<>(clock);
        this.activities = new ExpiringMap<>(clock);
        this.locks = new ExpiringMap<>(clock);
        this.leaders = new ExpiringMap<>(clock);
        this.instances = new ExpiringMap<>(clock);
        this.handoffs = new ExpiringMap<>(clock);
        this.events = new EventDispatcher("BuildReplay-LocalEvents", logger);
        this.nextPurge = clock.instant().plus(PURGE_INTERVAL);
    }

    @Override
    public boolean isDistributed() {
        return false;
    }

    @Override
    public SharedStateHealth health() {
        return new SharedStateHealth(enabled, false, true, null, lastError);
    }

    // ---- Sitzungscache

    @Override
    public Optional<CachedSession> cachedSession(UUID player) {
        return sessions.get(player);
    }

    @Override
    public void cacheSession(UUID player, CachedSession session, Duration ttl) {
        sessions.put(player, session, ttl);
        maybePurge();
    }

    @Override
    public void evictSession(UUID player) {
        sessions.remove(player);
    }

    // ---- Präsenz

    @Override
    public void markPresent(UUID player, PresenceInfo presence, Duration ttl) {
        presences.put(player, presence, ttl);
        maybePurge();
    }

    /**
     * Überschreibt nur einen fehlenden oder eigenen Eintrag (gleiche {@code instanceId}): Hat sich der Spieler
     * inzwischen auf einem anderen Server angemeldet, bleibt dessen neuerer Eintrag stehen.
     */
    @Override
    public void markLeft(UUID player, PresenceInfo presence, Duration grace) {
        PresenceInfo offline = offline(presence);
        presences.putIf(player, offline, grace,
                current -> current == null || current.instanceId().equals(presence.instanceId()));
        maybePurge();
    }

    @Override
    public Optional<PresenceInfo> presence(UUID player) {
        return presences.get(player);
    }

    // ---- Aufnahmestatus

    @Override
    public void putRecordingState(UUID player, RecordingState state, Duration ttl) {
        recordings.put(player, state, ttl);
        maybePurge();
    }

    @Override
    public void clearRecordingState(UUID player, String rawName) {
        recordings.removeIf(player, current -> current.rawName().equals(rawName));
    }

    @Override
    public Optional<RecordingState> recordingState(UUID player) {
        return recordings.get(player);
    }

    // ---- Aktivität

    @Override
    public void putActivity(UUID player, ActivityStamp stamp, Duration ttl) {
        activities.put(player, stamp, ttl);
        maybePurge();
    }

    @Override
    public Optional<ActivityStamp> activity(UUID player) {
        return activities.get(player);
    }

    // ---- Sperren

    @Override
    public Optional<LockToken> tryLock(String name, Duration lease) {
        String token = UUID.randomUUID().toString();
        return tryLock(name, lease, token) ? Optional.of(new LockToken(name, token, false)) : Optional.empty();
    }

    /** Erwirbt die Sperre mit einem vorgegebenen Token (für {@link FailoverSharedState}). */
    boolean tryLock(String name, Duration lease, String token) {
        boolean acquired = locks.putIf(name, token, lease, current -> current == null);
        maybePurge();
        return acquired;
    }

    /** {@code true}, solange unter diesem Namen eine gültige lokale Sperre besteht. */
    boolean isLocked(String name) {
        return locks.get(name).isPresent();
    }

    @Override
    public boolean unlock(LockToken token) {
        return unlock(token.name(), token.token());
    }

    boolean unlock(String name, String token) {
        return locks.removeIf(name, current -> current.equals(token));
    }

    // ---- Leader

    @Override
    public boolean tryAcquireOrRenewLeadership(String role, String instanceId, Duration lease) {
        return leaders.putIf(role, instanceId, lease, current -> current == null || current.equals(instanceId));
    }

    @Override
    public void releaseLeadership(String role, String instanceId) {
        leaders.removeIf(role, current -> current.equals(instanceId));
    }

    // ---- Instanzen

    @Override
    public void heartbeat(InstanceInfo info, Duration ttl) {
        instances.put(info.instanceId(), info, ttl);
        maybePurge();
    }

    @Override
    public List<InstanceInfo> liveInstances() {
        return instances.aliveValues().stream()
                .sorted(Comparator.comparing(InstanceInfo::instanceId))
                .toList();
    }

    // ---- Übergabe

    @Override
    public void putHandoff(UUID player, HandoffRequest request, Duration ttl) {
        handoffs.put(player, request, ttl);
        maybePurge();
    }

    @Override
    public Optional<HandoffRequest> takeHandoff(UUID player) {
        return handoffs.take(player);
    }

    // ---- Pub/Sub

    /** Stellt das Event asynchron an alle Empfänger dieser Instanz zu (keine anderen Instanzen). */
    @Override
    public void publish(SharedEvent event) {
        if (!closed) {
            events.dispatch(event);
        }
    }

    @Override
    public void subscribe(Consumer<SharedEvent> listener) {
        events.add(listener);
    }

    // ---- Verwaltung

    /**
     * Verwirft nach einem überstandenen Redis-Ausfall den während des Ausfalls lokal gesammelten Zustand, damit er
     * bei einem späteren Ausfall nicht veraltet wieder auftaucht. Gehaltene Sperren bleiben bis zu ihrem Ablauf
     * bestehen (ihre Tokens sind noch in Gebrauch), ebenso die Herzschläge (immer nur die eigene Instanz).
     */
    void resetAfterOutage() {
        sessions.clear();
        presences.clear();
        recordings.clear();
        activities.clear();
        leaders.clear();
        handoffs.clear();
    }

    /** Räumt abgelaufene Einträge auf (normalerweise automatisch beim Schreiben, höchstens alle 30 s). */
    void purgeExpired() {
        sessions.purgeExpired();
        presences.purgeExpired();
        recordings.purgeExpired();
        activities.purgeExpired();
        locks.purgeExpired();
        leaders.purgeExpired();
        instances.purgeExpired();
        handoffs.purgeExpired();
    }

    /** Anzahl gespeicherter Einträge inklusive noch nicht aufgeräumter abgelaufener (für Tests). */
    int storedEntries() {
        return sessions.rawSize() + presences.rawSize() + recordings.rawSize() + activities.rawSize()
                + locks.rawSize() + leaders.rawSize() + instances.rawSize() + handoffs.rawSize();
    }

    private void maybePurge() {
        Instant now = clock.instant();
        if (now.isBefore(nextPurge)) {
            return;
        }
        synchronized (this) {
            if (now.isBefore(nextPurge)) {
                return;
            }
            nextPurge = now.plus(PURGE_INTERVAL);
        }
        purgeExpired();
    }

    static PresenceInfo offline(PresenceInfo presence) {
        return presence.online()
                ? new PresenceInfo(presence.serverName(), presence.instanceId(), presence.since(), false)
                : presence;
    }

    @Override
    public void close() {
        closed = true;
        events.close();
        purgeExpired();
    }
}
