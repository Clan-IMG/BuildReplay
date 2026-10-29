package net.techniqstone.buildReplay.storage.sql;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import net.techniqstone.buildReplay.storage.model.SessionSource;
import net.techniqstone.buildReplay.storage.model.SessionStatus;
import org.jspecify.annotations.Nullable;

/**
 * Zusammenführbare Felder einer Sitzungszeile und die Regeln dafür. Reine Logik ohne Datenbank: der Schreib-Thread
 * sperrt die Zeile ({@code SELECT ... FOR UPDATE}), wendet {@link #merge} an und schreibt nur bei Änderungen zurück.
 * Dadurch ist das Ergebnis unabhängig von der Reihenfolge, in der mehrere Server dieselbe Sitzung aktualisieren.
 */
record SessionState(
        @Nullable String cloudSessionId,
        SessionSource source,
        SessionStatus status,
        @Nullable Instant loginTime,
        @Nullable Instant logoutTime,
        @Nullable Long durationSeconds) {

    SessionState {
        loginTime = millis(loginTime);
        logoutTime = millis(logoutTime);
    }

    /**
     * Regeln: {@code cloud_session_id} bleibt, wenn bereits gesetzt (sonst der neue Wert); {@code login_time} ist der
     * früheste bekannte Wert; {@code logout_time} der späteste und wird nie durch {@code null} gelöscht; die Dauer
     * kommt von dem Logout, der gewinnt (oder füllt eine fehlende); SIMPLECLOUD gewinnt gegen PROVISIONAL; mit
     * bekanntem Logout wird der Status CLOSED, MERGED wird nie zurückgestuft.
     */
    SessionState merge(@Nullable String newCloudSessionId, SessionSource newSource, @Nullable Instant newLogin,
                       @Nullable Instant newLogout, @Nullable Long newDuration) {
        Instant login = millis(newLogin);
        Instant logout = millis(newLogout);

        String cloud = cloudSessionId != null ? cloudSessionId : newCloudSessionId;
        Instant mergedLogin = earliest(loginTime, login);
        boolean logoutWins = logout != null && (logoutTime == null || !logout.isBefore(logoutTime));
        Instant mergedLogout = logoutWins ? logout : logoutTime;
        Long mergedDuration = newDuration != null && (logoutWins || durationSeconds == null)
                ? newDuration : durationSeconds;
        SessionSource mergedSource = source == SessionSource.SIMPLECLOUD || newSource == SessionSource.SIMPLECLOUD
                ? SessionSource.SIMPLECLOUD : SessionSource.PROVISIONAL;
        SessionStatus mergedStatus;
        if (status == SessionStatus.MERGED) {
            mergedStatus = SessionStatus.MERGED;
        } else if (mergedLogout != null) {
            mergedStatus = SessionStatus.CLOSED;
        } else {
            mergedStatus = status;
        }
        return new SessionState(cloud, mergedSource, mergedStatus, mergedLogin, mergedLogout, mergedDuration);
    }

    /** Ausgangszustand einer neu angelegten Zeile. */
    static SessionState initial(@Nullable String cloudSessionId, SessionSource source, @Nullable Instant login,
                                @Nullable Instant logout, @Nullable Long duration) {
        return new SessionState(cloudSessionId, source, logout != null ? SessionStatus.CLOSED : SessionStatus.ACTIVE,
                login, logout, duration);
    }

    SessionState withCloudSessionId(@Nullable String cloud) {
        return new SessionState(cloud, source, status, loginTime, logoutTime, durationSeconds);
    }

    private static @Nullable Instant earliest(@Nullable Instant a, @Nullable Instant b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isBefore(b) ? a : b;
    }

    private static @Nullable Instant millis(@Nullable Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MILLIS);
    }
}
