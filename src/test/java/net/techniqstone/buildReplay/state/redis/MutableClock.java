package net.techniqstone.buildReplay.state.redis;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** Von Hand weiterstellbare Uhr für TTL-Tests. */
final class MutableClock extends Clock {

    private final AtomicReference<Instant> now;

    MutableClock(Instant start) {
        this.now = new AtomicReference<>(start);
    }

    void advance(Duration duration) {
        now.updateAndGet(current -> current.plus(duration));
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
