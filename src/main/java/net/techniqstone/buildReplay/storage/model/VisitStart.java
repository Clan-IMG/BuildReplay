package net.techniqstone.buildReplay.storage.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Beginn eines Serveraufenthalts. {@code visitKey} ist idempotent, z. B. {@code instanceId + ":" + uuid + ":" +
 * joinedAt.toEpochMilli()}. Existiert die Sitzung {@code sessionKey} noch nicht, legt der Store eine vorläufige an.
 */
public record VisitStart(
        String visitKey,
        String sessionKey,
        UUID playerUuid,
        String playerName,
        String serverName,
        String instanceId,
        Instant joinedAt) {
}
