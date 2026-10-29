package net.techniqstone.buildReplay.state;

import java.time.Instant;
import net.techniqstone.buildReplay.storage.model.SessionSource;
import org.jspecify.annotations.Nullable;

/** Netzwerkweit zwischengespeicherte Zuordnung Spieler → aktuelle Netzwerksitzung. */
public record CachedSession(
        String sessionKey,
        @Nullable String cloudSessionId,
        SessionSource source,
        @Nullable Instant loginTime,
        String resolvedBy,
        Instant resolvedAt) {
}
