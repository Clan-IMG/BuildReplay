package net.techniqstone.buildReplay.storage.model;

import java.time.Instant;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Aggregierte Abschnittsdaten einer Sitzung für Listen. Gelöschte Abschnitte (DELETED) zählen nicht mit;
 * {@code firstSegmentAt} ist der früheste Beginn, {@code lastSegmentAt} das späteste Ende eines Abschnitts.
 */
public record SessionStats(
        int segments,
        int availableSegments,
        long totalDurationTicks,
        Set<String> servers,
        @Nullable Instant firstSegmentAt,
        @Nullable Instant lastSegmentAt) {

    public static final SessionStats EMPTY = new SessionStats(0, 0, 0, Set.of(), null, null);

    public SessionStats {
        servers = Set.copyOf(servers);
    }
}
