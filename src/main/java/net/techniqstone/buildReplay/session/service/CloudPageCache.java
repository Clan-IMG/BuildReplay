package net.techniqstone.buildReplay.session.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.techniqstone.buildReplay.simplecloud.CloudSessionPage;
import net.techniqstone.buildReplay.simplecloud.SimpleCloudClient;

/**
 * Kurzzeit-Cache für {@code GET /v0/players/sessions} je (Spieler, offset, limit). Gleichzeitige gleiche Anfragen
 * teilen sich einen HTTP-Aufruf; Fehler werden nicht zwischengespeichert.
 */
final class CloudPageCache {

    private static final int MAX_ENTRIES = 512;

    private record Key(UUID player, int offset, int limit) {
    }

    private record Entry(CompletableFuture<CloudSessionPage> page, Instant expiresAt) {
    }

    private final SimpleCloudClient cloud;
    private final Clock clock;
    private final Supplier<Duration> ttl;
    private final Map<Key, Entry> entries = new ConcurrentHashMap<>();

    CloudPageCache(SimpleCloudClient cloud, Clock clock, Supplier<Duration> ttl) {
        this.cloud = cloud;
        this.clock = clock;
        this.ttl = ttl;
    }

    CompletableFuture<CloudSessionPage> page(UUID player, int offset, int limit) {
        Duration lifetime = ttl.get();
        if (lifetime.isZero() || lifetime.isNegative()) {
            return Futures.call(() -> cloud.listSessions(player, limit, offset));
        }
        Key key = new Key(player, offset, limit);
        Instant now = clock.instant();
        Entry existing = entries.get(key);
        if (existing != null && !existing.page().isCompletedExceptionally()
                && (!existing.page().isDone() || now.isBefore(existing.expiresAt()))) {
            return existing.page();
        }
        if (entries.size() >= MAX_ENTRIES) {
            entries.values().removeIf(entry -> entry.page().isDone() && !now.isBefore(entry.expiresAt()));
            if (entries.size() >= MAX_ENTRIES) {
                entries.clear();
            }
        }
        CompletableFuture<CloudSessionPage> page = Futures.call(() -> cloud.listSessions(player, limit, offset));
        Entry created = new Entry(page, now.plus(lifetime));
        entries.put(key, created);
        page.whenComplete((value, error) -> {
            if (error != null) {
                entries.remove(key, created);
            }
        });
        return page;
    }

    /** Verwirft alle Seiten eines Spielers (z. B. nach einem Sitzungsende). */
    void invalidate(UUID player) {
        entries.keySet().removeIf(key -> key.player().equals(player));
    }
}
