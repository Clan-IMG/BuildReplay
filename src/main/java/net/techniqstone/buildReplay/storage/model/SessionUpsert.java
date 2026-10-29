package net.techniqstone.buildReplay.storage.model;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Idempotentes Anlegen/Aktualisieren einer Sitzung über {@code sessionKey}. Zusammenführung bei Konflikten:
 * {@code login_time} = frühester bekannter Wert, {@code logout_time}/{@code duration_seconds} werden nur gesetzt,
 * nie durch {@code null} überschrieben; mit Logout wird der Status CLOSED.
 */
public record SessionUpsert(
        String sessionKey,
        @Nullable String cloudSessionId,
        UUID playerUuid,
        SessionSource source,
        @Nullable Instant loginTime,
        @Nullable Instant logoutTime,
        @Nullable Long durationSeconds) {
}
