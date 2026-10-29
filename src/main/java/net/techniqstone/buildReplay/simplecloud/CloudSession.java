package net.techniqstone.buildReplay.simplecloud;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Netzwerkweite Spielersitzung aus {@code GET /v0/players/sessions} bzw. {@code /sessions/current}.
 * Eine Sitzung entspricht einer Proxy-Verbindung und bleibt über Serverwechsel gleich ({@code id}).
 * Bei aktiven Sitzungen fehlen {@code logout_time} und {@code duration_seconds} in der Antwort.
 */
public record CloudSession(
        String id,
        UUID playerId,
        Instant loginTime,
        @Nullable Instant logoutTime,
        @Nullable Long durationSeconds) {

    public CloudSession {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(loginTime, "loginTime");
    }

    public boolean active() {
        return logoutTime == null;
    }

    /** Dauer in Sekunden; bei aktiven Sitzungen bis {@code now}. */
    public long durationSecondsAt(Instant now) {
        if (durationSeconds != null) {
            return durationSeconds;
        }
        Instant end = logoutTime != null ? logoutTime : now;
        return Math.max(0, end.getEpochSecond() - loginTime.getEpochSecond());
    }

    /** Ob {@code time} in diese Sitzung fällt ({@code slack} Toleranz an beiden Rändern). */
    public boolean covers(Instant time, java.time.Duration slack) {
        Instant start = loginTime.minus(slack);
        return !time.isBefore(start) && (logoutTime == null || !time.isAfter(logoutTime.plus(slack)));
    }
}
