package net.techniqstone.buildReplay.command;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Einfache Abklingzeit pro Spieler, damit Listen- und Detailabfragen nicht im Sekundentakt SimpleCloud/MariaDB treffen. */
public final class Cooldowns {

    private static final int CLEANUP_THRESHOLD = 256;

    private final Clock clock;
    private final Duration duration;
    private final Map<UUID, Instant> lastUse = new ConcurrentHashMap<>();

    public Cooldowns(Clock clock, Duration duration) {
        this.clock = clock;
        this.duration = duration;
    }

    /** {@code true} und Zeitpunkt merken, wenn die Abklingzeit abgelaufen ist; sonst {@code false}. */
    public boolean tryAcquire(UUID player) {
        Instant now = clock.instant();
        if (lastUse.size() > CLEANUP_THRESHOLD) {
            lastUse.values().removeIf(at -> !at.plus(duration).isAfter(now));
        }
        boolean[] allowed = {false};
        lastUse.compute(player, (id, previous) -> {
            if (previous == null || !previous.plus(duration).isAfter(now)) {
                allowed[0] = true;
                return now;
            }
            return previous;
        });
        return allowed[0];
    }
}
