package net.techniqstone.buildReplay.maintenance;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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

/** Lokaler {@link SharedState} für Tests: Sperren, Events, Herzschlag und Präsenz werden protokolliert. */
final class FakeSharedState implements SharedState {

    record Beat(InstanceInfo info, Duration ttl) {
    }

    record Presence(UUID player, PresenceInfo info, Duration ttl) {
    }

    /** Von "anderen Instanzen" gehaltene Sperren. */
    final Set<String> heldElsewhere = ConcurrentHashMap.newKeySet();
    final Set<String> held = ConcurrentHashMap.newKeySet();
    final List<String> acquired = new CopyOnWriteArrayList<>();
    final List<SharedEvent> published = new CopyOnWriteArrayList<>();
    final List<Beat> beats = new CopyOnWriteArrayList<>();
    final List<Presence> presenceWrites = new CopyOnWriteArrayList<>();
    final Map<UUID, PresenceInfo> presence = new ConcurrentHashMap<>();
    final List<String> threadViolations = new CopyOnWriteArrayList<>();

    private void io(String method) {
        if (SyncTaskRunner.onMainThread()) {
            threadViolations.add(method + " auf dem Hauptthread");
        }
    }

    @Override
    public boolean isDistributed() {
        return false;
    }

    @Override
    public SharedStateHealth health() {
        io("health");
        return new SharedStateHealth(true, true, false, null, null);
    }

    @Override
    public Optional<CachedSession> cachedSession(UUID player) {
        return Optional.empty();
    }

    @Override
    public void cacheSession(UUID player, CachedSession session, Duration ttl) {
    }

    @Override
    public void evictSession(UUID player) {
    }

    @Override
    public void markPresent(UUID player, PresenceInfo info, Duration ttl) {
        io("markPresent");
        presenceWrites.add(new Presence(player, info, ttl));
        presence.put(player, info);
    }

    @Override
    public void markLeft(UUID player, PresenceInfo info, Duration grace) {
        presence.put(player, info);
    }

    @Override
    public Optional<PresenceInfo> presence(UUID player) {
        io("presence");
        return Optional.ofNullable(presence.get(player));
    }

    @Override
    public void putRecordingState(UUID player, RecordingState state, Duration ttl) {
    }

    @Override
    public void clearRecordingState(UUID player, String rawName) {
    }

    @Override
    public Optional<RecordingState> recordingState(UUID player) {
        return Optional.empty();
    }

    @Override
    public void putActivity(UUID player, ActivityStamp stamp, Duration ttl) {
    }

    @Override
    public Optional<ActivityStamp> activity(UUID player) {
        return Optional.empty();
    }

    @Override
    public Optional<LockToken> tryLock(String name, Duration lease) {
        io("tryLock");
        if (heldElsewhere.contains(name) || !held.add(name)) {
            return Optional.empty();
        }
        acquired.add(name);
        return Optional.of(new LockToken(name, UUID.randomUUID().toString(), false));
    }

    @Override
    public boolean unlock(LockToken token) {
        return held.remove(token.name());
    }

    @Override
    public boolean tryAcquireOrRenewLeadership(String role, String instanceId, Duration lease) {
        return true;
    }

    @Override
    public void releaseLeadership(String role, String instanceId) {
    }

    @Override
    public void heartbeat(InstanceInfo info, Duration ttl) {
        io("heartbeat");
        beats.add(new Beat(info, ttl));
    }

    @Override
    public List<InstanceInfo> liveInstances() {
        return beats.stream().map(Beat::info).toList();
    }

    @Override
    public void putHandoff(UUID player, HandoffRequest request, Duration ttl) {
    }

    @Override
    public Optional<HandoffRequest> takeHandoff(UUID player) {
        return Optional.empty();
    }

    @Override
    public void publish(SharedEvent event) {
        io("publish");
        published.add(event);
    }

    @Override
    public void subscribe(Consumer<SharedEvent> listener) {
    }

    @Override
    public void close() {
    }
}
