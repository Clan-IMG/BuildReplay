package net.techniqstone.buildReplay.recording.service.fakes;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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

/** Gemeinsamer Zustand im Speicher (wie Redis-Ausfall-Fallback), mit Protokoll der Übergaben und Events. */
public final class FakeSharedState implements SharedState {

    public final Map<UUID, RecordingState> recordingStates = new HashMap<>();
    public final List<SharedEvent> published = new ArrayList<>();
    public final Map<UUID, HandoffRequest> handoffs = new HashMap<>();
    public final Map<UUID, Duration> handoffTtls = new HashMap<>();
    public final List<String> calls = new ArrayList<>();

    @Override
    public boolean isDistributed() {
        return false;
    }

    @Override
    public SharedStateHealth health() {
        return new SharedStateHealth(false, false, true, null, null);
    }

    @Override
    public Optional<CachedSession> cachedSession(UUID player) {
        return Optional.empty();
    }

    @Override
    public void cacheSession(UUID player, CachedSession session, Duration ttl) {
        calls.add("cacheSession");
    }

    @Override
    public void evictSession(UUID player) {
        calls.add("evictSession");
    }

    @Override
    public void markPresent(UUID player, PresenceInfo presence, Duration ttl) {
        calls.add("markPresent");
    }

    @Override
    public void markLeft(UUID player, PresenceInfo presence, Duration grace) {
        calls.add("markLeft");
    }

    @Override
    public Optional<PresenceInfo> presence(UUID player) {
        return Optional.empty();
    }

    @Override
    public void putRecordingState(UUID player, RecordingState state, Duration ttl) {
        calls.add("putRecordingState:" + state.rawName());
        recordingStates.put(player, state);
    }

    @Override
    public void clearRecordingState(UUID player, String rawName) {
        calls.add("clearRecordingState:" + rawName);
        RecordingState current = recordingStates.get(player);
        if (current != null && current.rawName().equals(rawName)) {
            recordingStates.remove(player);
        }
    }

    @Override
    public Optional<RecordingState> recordingState(UUID player) {
        return Optional.ofNullable(recordingStates.get(player));
    }

    @Override
    public void putActivity(UUID player, ActivityStamp stamp, Duration ttl) {
        calls.add("putActivity");
    }

    @Override
    public Optional<ActivityStamp> activity(UUID player) {
        return Optional.empty();
    }

    @Override
    public Optional<LockToken> tryLock(String name, Duration lease) {
        return Optional.of(new LockToken(name, "token", false));
    }

    @Override
    public boolean unlock(LockToken token) {
        return true;
    }

    @Override
    public boolean tryAcquireOrRenewLeadership(String role, String instanceId, Duration lease) {
        return true;
    }

    @Override
    public void releaseLeadership(String role, String instanceId) {
        // nichts
    }

    @Override
    public void heartbeat(InstanceInfo info, Duration ttl) {
        calls.add("heartbeat");
    }

    @Override
    public List<InstanceInfo> liveInstances() {
        return List.of();
    }

    @Override
    public void putHandoff(UUID player, HandoffRequest request, Duration ttl) {
        calls.add("putHandoff:" + request.targetServer());
        handoffs.put(player, request);
        handoffTtls.put(player, ttl);
    }

    @Override
    public Optional<HandoffRequest> takeHandoff(UUID player) {
        calls.add("takeHandoff");
        handoffTtls.remove(player);
        return Optional.ofNullable(handoffs.remove(player));
    }

    @Override
    public void publish(SharedEvent event) {
        published.add(event);
    }

    @Override
    public void subscribe(Consumer<SharedEvent> listener) {
        // nichts
    }

    @Override
    public void close() {
        // nichts
    }
}
