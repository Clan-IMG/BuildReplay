package net.techniqstone.buildReplay.recording.service.fakes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.techniqstone.buildReplay.activity.ActivityObserver;
import net.techniqstone.buildReplay.activity.ActivitySnapshot;
import net.techniqstone.buildReplay.activity.ActivityTracking;
import net.techniqstone.buildReplay.activity.ActivityType;

/** Aktivitätserkennung, die nur ihre Aufrufe protokolliert. */
public final class FakeActivityTracking implements ActivityTracking {

    public final List<ActivityObserver> observers = new ArrayList<>();
    public final List<String> calls;

    public FakeActivityTracking(List<String> calls) {
        this.calls = calls;
    }

    @Override
    public void addObserver(ActivityObserver observer) {
        observers.add(observer);
    }

    @Override
    public void record(UUID player, ActivityType type, Instant at) {
        calls.add("activity.record:" + type);
    }

    @Override
    public void start(UUID player, Instant at) {
        calls.add("activity.start");
    }

    @Override
    public void forget(UUID player) {
        calls.add("activity.forget");
    }

    @Override
    public Optional<ActivitySnapshot> snapshot(UUID player) {
        return Optional.empty();
    }
}
