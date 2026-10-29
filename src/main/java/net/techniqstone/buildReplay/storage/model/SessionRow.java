package net.techniqstone.buildReplay.storage.model;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Zeile aus {@code <prefix>sessions}. {@code sessionKey} ist bei SimpleCloud-Sitzungen die SimpleCloud-Session-ID,
 * bei vorläufigen Sitzungen {@code "prov-" + UUID}.
 */
public record SessionRow(
        long id,
        String sessionKey,
        @Nullable String cloudSessionId,
        UUID playerUuid,
        @Nullable String playerName,
        SessionSource source,
        SessionStatus status,
        @Nullable Instant loginTime,
        @Nullable Instant logoutTime,
        @Nullable Long durationSeconds,
        @Nullable Long mergedInto,
        Instant createdAt,
        Instant updatedAt) {
}
