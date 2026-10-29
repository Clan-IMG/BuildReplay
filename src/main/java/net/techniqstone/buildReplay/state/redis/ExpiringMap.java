package net.techniqstone.buildReplay.state.redis;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Threadsichere Map mit Ablaufzeit pro Eintrag. Abgelaufene Einträge verschwinden lazy beim Zugriff (gemessen mit
 * der übergebenen {@link Clock}) und über {@link #purgeExpired()}. Alle bedingten Operationen sind pro Schlüssel
 * atomar ({@link ConcurrentHashMap#compute}).
 */
final class ExpiringMap<K, V> {

    private record Entry<V>(V value, Instant expiresAt) {
        boolean alive(Instant now) {
            return expiresAt.isAfter(now);
        }
    }

    private final Clock clock;
    private final ConcurrentHashMap<K, Entry<V>> entries = new ConcurrentHashMap<>();

    ExpiringMap(Clock clock) {
        this.clock = clock;
    }

    void put(K key, V value, Duration ttl) {
        Instant now = clock.instant();
        entries.put(key, new Entry<>(value, expiry(now, ttl)));
    }

    Optional<V> get(K key) {
        Entry<V> entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (!entry.alive(clock.instant())) {
            entries.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    void remove(K key) {
        entries.remove(key);
    }

    /** Entfernt den Eintrag atomar und liefert ihn, falls er noch gültig war. */
    Optional<V> take(K key) {
        Entry<V> entry = entries.remove(key);
        if (entry == null || !entry.alive(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    /**
     * Schreibt den Wert nur, wenn {@code condition} für den aktuell gültigen Wert ({@code null} = keiner) zutrifft.
     *
     * @return {@code true}, wenn geschrieben wurde
     */
    boolean putIf(K key, V value, Duration ttl, Predicate<@Nullable V> condition) {
        Instant now = clock.instant();
        boolean[] written = {false};
        entries.compute(key, (k, current) -> {
            boolean alive = current != null && current.alive(now);
            if (condition.test(alive ? current.value() : null)) {
                written[0] = true;
                return new Entry<>(value, expiry(now, ttl));
            }
            return alive ? current : null;
        });
        return written[0];
    }

    /**
     * Entfernt den Eintrag nur, wenn er noch gültig ist und {@code condition} zutrifft.
     *
     * @return {@code true}, wenn ein gültiger Eintrag entfernt wurde
     */
    boolean removeIf(K key, Predicate<V> condition) {
        Instant now = clock.instant();
        boolean[] removed = {false};
        entries.computeIfPresent(key, (k, current) -> {
            if (!current.alive(now)) {
                return null;
            }
            if (condition.test(current.value())) {
                removed[0] = true;
                return null;
            }
            return current;
        });
        return removed[0];
    }

    List<V> aliveValues() {
        Instant now = clock.instant();
        List<V> result = new ArrayList<>();
        for (Entry<V> entry : entries.values()) {
            if (entry.alive(now)) {
                result.add(entry.value());
            }
        }
        return result;
    }

    /** Entfernt alle abgelaufenen Einträge (nur Einträge, die nicht zwischenzeitlich ersetzt wurden). */
    void purgeExpired() {
        Instant now = clock.instant();
        entries.entrySet().removeIf(e -> !e.getValue().alive(now));
    }

    void clear() {
        entries.clear();
    }

    /** Anzahl gespeicherter Einträge inklusive noch nicht entfernter abgelaufener (für Tests). */
    int rawSize() {
        return entries.size();
    }

    private static Instant expiry(Instant now, Duration ttl) {
        return now.plusMillis(Leases.millis(ttl));
    }
}
