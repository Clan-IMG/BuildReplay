package net.techniqstone.buildReplay.storage.sql;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransactionRollbackException;
import java.sql.SQLTransientConnectionException;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.jspecify.annotations.Nullable;

/** Einordnung von MariaDB-Fehlern: Verbindung weg, kurz wiederholbar oder dauerhaft. */
final class SqlErrors {

    static final int LOCK_WAIT_TIMEOUT = 1205;
    static final int DEADLOCK = 1213;

    /**
     * Fehlercodes, bei denen der Server (vorübergehend) nicht nutzbar ist: Verbindungsabbruch, Shutdown,
     * zu viele Verbindungen, Zugriff verweigert, Server nur lesbar (z. B. während eines Failovers).
     */
    private static final Set<Integer> UNAVAILABLE_CODES = Set.of(
            1040, // ER_CON_COUNT_ERROR
            1045, // ER_ACCESS_DENIED_ERROR
            1053, // ER_SERVER_SHUTDOWN
            1203, // ER_TOO_MANY_USER_CONNECTIONS
            1290, // ER_OPTION_PREVENTS_STATEMENT (--read-only)
            1836, // ER_READ_ONLY_MODE
            1927, // ER_CONNECTION_KILLED
            2002, 2003, 2006, 2013); // Client: keine Verbindung / Server weg / Verbindung verloren

    private SqlErrors() {
    }

    /** Server nicht erreichbar oder Verbindung verloren - der Vorgang bleibt in der Warteschlange. */
    static boolean isConnectionProblem(Throwable error) {
        for (Throwable t = error; t != null; t = next(t)) {
            if (t instanceof SQLTransientConnectionException
                    || t instanceof SQLNonTransientConnectionException
                    || t instanceof SQLRecoverableException) {
                return true;
            }
            if (t instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && (state.startsWith("08") || state.equals("70100"))) {
                    return true;
                }
                if (UNAVAILABLE_CODES.contains(sql.getErrorCode())) {
                    return true;
                }
            }
            if (t instanceof java.io.IOException) {
                return true;
            }
        }
        return false;
    }

    /** Deadlock, Lock-Wait-Timeout oder ein durch gleichzeitiges Einfügen verletzter Schlüssel: Transaktion wiederholen. */
    static boolean isRetryable(Throwable error) {
        for (Throwable t = error; t != null; t = next(t)) {
            if (t instanceof SQLTransactionRollbackException || t instanceof SQLIntegrityConstraintViolationException) {
                return true;
            }
            if (t instanceof SQLException sql) {
                int code = sql.getErrorCode();
                if (code == DEADLOCK || code == LOCK_WAIT_TIMEOUT) {
                    return true;
                }
                String state = sql.getSQLState();
                if (state != null && (state.equals("40001") || state.startsWith("23"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Kurze, protokollierbare Beschreibung ohne Stacktrace. Das Passwort wird zur Sicherheit unkenntlich gemacht,
     * falls ein Treiber es je in eine Meldung übernehmen sollte.
     */
    static String describe(Throwable error, @Nullable String secret) {
        Throwable t = error;
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        while (t.getCause() != null && t.getCause() != t && (t.getMessage() == null || t.getMessage().isBlank())) {
            t = t.getCause();
        }
        String message = t.getMessage();
        String text = t.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message.strip());
        int newline = text.indexOf('\n');
        if (newline > 0) {
            text = text.substring(0, newline);
        }
        if (secret != null && !secret.isEmpty()) {
            text = text.replace(secret, "***");
        }
        return text.length() > 300 ? text.substring(0, 300) + "..." : text;
    }

    private static @Nullable Throwable next(Throwable t) {
        if (t instanceof SQLException sql) {
            SQLException chained = sql.getNextException();
            if (chained != null && chained != t) {
                return chained;
            }
        }
        Throwable cause = t.getCause();
        return cause == t ? null : cause;
    }
}
