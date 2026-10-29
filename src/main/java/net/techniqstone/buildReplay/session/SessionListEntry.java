package net.techniqstone.buildReplay.session;

import java.time.Instant;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Eine Zeile in {@code /buildreplay sessions}. */
public record SessionListEntry(
        String ref,
        @Nullable String cloudSessionId,
        @Nullable Long dbSessionId,
        Instant loginTime,
        @Nullable Instant logoutTime,
        long durationSeconds,
        boolean active,
        boolean provisional,
        int segments,
        int availableSegments,
        Set<String> servers) {

    public SessionListEntry {
        servers = Set.copyOf(servers);
    }
}
