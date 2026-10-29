package net.techniqstone.buildReplay.session.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
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

/**
 * Redis im Speicher mit Ablaufzeiten über die Testuhr. Mehrere Instanzen eines Tests teilen sich ein Objekt
 * (verteilt) oder haben je ein eigenes (lokaler Rückfall). {@link #setBroken(boolean)} lässt jede Methode werfen -
 * strenger als der Vertrag, um die Robustheit der Aufrufer zu prüfen.
 */
final class FakeSharedState implements SharedState {

    private record Expiring<T>(T value, Instant expiresAt) {
    }

    private final Clock clock;
    private final Map<UUID, Expiring<CachedSession>> sessions = new HashMap<>();
    private final Map<UUID, Expiring<PresenceInfo>> presence = new HashMap<>();
    private final Map<String, Expiring<String>> locks = new HashMap<>();
    private final Map<String, Expiring<String>> leaders = new HashMap<>();
    private final Map<UUID, Expiring<HandoffRequest>> handoffs = new HashMap<>();
    private final List<SharedEvent> published = new CopyOnWriteArrayList<>();
    private final List<Consumer<SharedEvent>> listeners = new CopyOnWriteArrayList<>();
    private final boolean distributed;
    private volatile boolean broken;
    private long tokens;

    FakeSharedState(Clock clock, boolean distributed) {
        this.clock = clock;
        this.distributed = distributed;
    }

    void setBroken(boolean broken) {
        this.broken = broken;
    }

    List<SharedEvent> published() {
        return List.copyOf(published);
    }

    List<SharedEvent> published(String type) {
        return published.stream().filter(event -> event.type().equals(type)).toList();
    }

    /** Leader-Rolle gezielt vergeben (z. B. an eine andere Instanz). */
    synchronized void forceLeader(String role, String instanceId, Duration lease) {
        leaders.put(role, new Expiring<>(instanceId, clock.instant().plus(lease)));
    }

    // ------------------------------------------------------------------ SharedState

    @Override
    public boolean isDistributed() {
        return distributed;
    }

    @Override
    public SharedStateHealth health() {
        return new SharedStateHealth(true, distributed, !distributed, null, null);
    }

    @Override
    public synchronized Optional<CachedSession> cachedSession(UUID player) {
        check();
        return valid(sessions.get(player));
    }

    @Override
    public synchronized void cacheSession(UUID player, CachedSession session, Duration ttl) {
        check();
        sessions.put(player, new Expiring<>(session, clock.instant().plus(ttl)));
    }

    @Override
    public synchronized void evictSession(UUID player) {
        check();
        sessions.remove(player);
    }

    @Override
    public synchronized void markPresent(UUID player, PresenceInfo info, Duration ttl) {
        check();
        presence.put(player, new Expiring<>(info, clock.instant().plus(ttl)));
    }

    @Override
    public synchronized void markLeft(UUID player, PresenceInfo info, Duration grace) {
        check();
        presence.put(player, new Expiring<>(info, clock.instant().plus(grace)));
    }

    @Override
    public synchronized Optional<PresenceInfo> presence(UUID player) {
        check();
        return valid(presence.get(player));
    }

    @Override
    public void putRecordingState(UUID player, RecordingState state, Duration ttl) {
        check();
    }

    @Override
    public void clearRecordingState(UUID player, String rawName) {
        check();
    }

    @Override
    public Optional<RecordingState> recordingState(UUID player) {
        check();
        return Optional.empty();
    }

    @Override
    public void putActivity(UUID player, ActivityStamp stamp, Duration ttl) {
        check();
    }

    @Override
    public Optional<ActivityStamp> activity(UUID player) {
        check();
        return Optional.empty();
    }

    @Override
    public synchronized Optional<LockToken> tryLock(String name, Duration lease) {
        check();
        if (valid(locks.get(name)).isPresent()) {
            return Optional.empty();
        }
        String token = "t" + (++tokens);
        locks.put(name, new Expiring<>(token, clock.instant().plus(lease)));
        return Optional.of(new LockToken(name, token, distributed));
    }

    @Override
    public synchronized boolean unlock(LockToken token) {
        check();
        Optional<String> holder = valid(locks.get(token.name()));
        if (holder.isPresent() && holder.get().equals(token.token())) {
            locks.remove(token.name());
            return true;
        }
        return false;
    }

    @Override
    public synchronized boolean tryAcquireOrRenewLeadership(String role, String instanceId, Duration lease) {
        check();
        Optional<String> holder = valid(leaders.get(role));
        if (holder.isEmpty() || holder.get().equals(instanceId)) {
            leaders.put(role, new Expiring<>(instanceId, clock.instant().plus(lease)));
            return true;
        }
        return false;
    }

    @Override
    public synchronized void releaseLeadership(String role, String instanceId) {
        check();
        Optional<String> holder = valid(leaders.get(role));
        if (holder.isPresent() && holder.get().equals(instanceId)) {
            leaders.remove(role);
        }
    }

    @Override
    public void heartbeat(InstanceInfo info, Duration ttl) {
        check();
    }

    @Override
    public List<InstanceInfo> liveInstances() {
        check();
        return new ArrayList<>();
    }

    @Override
    public synchronized void putHandoff(UUID player, HandoffRequest request, Duration ttl) {
        check();
        handoffs.put(player, new Expiring<>(request, clock.instant().plus(ttl)));
    }

    @Override
    public synchronized Optional<HandoffRequest> takeHandoff(UUID player) {
        check();
        return valid(handoffs.remove(player));
    }

    @Override
    public void publish(SharedEvent event) {
        check();
        published.add(event);
        listeners.forEach(listener -> listener.accept(event));
    }

    @Override
    public void subscribe(Consumer<SharedEvent> listener) {
        check();
        listeners.add(listener);
    }

    @Override
    public void close() {
        // nichts zu schließen
    }

    private <T> Optional<T> valid(Expiring<T> entry) {
        if (entry == null || !clock.instant().isBefore(entry.expiresAt())) {
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    private void check() {
        if (broken) {
            throw new IllegalStateException("Test: gemeinsamer Zustand defekt");
        }
    }
}
