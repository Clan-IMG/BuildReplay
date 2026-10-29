package net.techniqstone.buildReplay.storage.model;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Aufenthalt eines Spielers auf einem Server innerhalb einer Netzwerksitzung ({@code <prefix>server_visits}). */
public record VisitRow(
        long id,
        String visitKey,
        long sessionId,
        UUID playerUuid,
        String serverName,
        String instanceId,
        Instant joinedAt,
        @Nullable Instant leftAt,
        @Nullable VisitEndReason endReason) {
}
