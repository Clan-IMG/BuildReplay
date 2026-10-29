package net.techniqstone.buildReplay.command;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import net.techniqstone.buildReplay.maintenance.MaintenanceException;
import net.techniqstone.buildReplay.session.AmbiguousReferenceException;
import net.techniqstone.buildReplay.simplecloud.SimpleCloudException;
import net.techniqstone.buildReplay.storage.StorageUnavailableException;

/** Ordnet Fehler aus asynchronen Abfragen einer verständlichen Meldung zu (rein, testbar). */
public final class CommandFailures {

    private static final int MAX_DEPTH = 12;

    public enum Kind {
        /** MariaDB deaktiviert oder nicht erreichbar. */
        STORAGE,
        /** SimpleCloud nicht erreichbar (oder Anmeldung abgelehnt). */
        SIMPLECLOUD,
        /** Mehrdeutige Sitzungsreferenz - Kandidaten anzeigen. */
        AMBIGUOUS,
        TIMEOUT,
        /** I/O-Warteschlange voll. */
        BUSY,
        /** Wartungsaufgabe nicht möglich - Nachricht direkt anzeigen. */
        MAINTENANCE,
        /** Unerwartet: SEVERE loggen, allgemeine Meldung. */
        UNEXPECTED
    }

    /** Einordnung plus die maßgebliche (ausgepackte) Ursache. */
    public record Failure(Kind kind, Throwable cause) {
    }

    private CommandFailures() {
    }

    /** Entfernt {@link CompletionException}/{@link ExecutionException}-Hüllen. */
    public static Throwable unwrap(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null && depth++ < MAX_DEPTH) {
            current = current.getCause();
        }
        return current;
    }

    /** Sucht in der Ursachenkette nach einem bekannten Fehlertyp. */
    public static Failure classify(Throwable error) {
        Throwable root = unwrap(error);
        Throwable current = root;
        for (int depth = 0; current != null && depth < MAX_DEPTH; depth++) {
            Kind kind = kindOf(current);
            if (kind != null) {
                return new Failure(kind, current);
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return new Failure(Kind.UNEXPECTED, root);
    }

    public static Text textFor(Kind kind) {
        return switch (kind) {
            case STORAGE -> CommandTexts.ERROR_STORAGE;
            case SIMPLECLOUD -> CommandTexts.ERROR_SIMPLECLOUD;
            case TIMEOUT -> CommandTexts.ERROR_TIMEOUT;
            case BUSY -> CommandTexts.ERROR_BUSY;
            case MAINTENANCE -> CommandTexts.ERROR_MAINTENANCE;
            case AMBIGUOUS, UNEXPECTED -> CommandTexts.ERROR_GENERIC;
        };
    }

    private static Kind kindOf(Throwable t) {
        if (t instanceof StorageUnavailableException) {
            return Kind.STORAGE;
        }
        if (t instanceof SimpleCloudException) {
            return Kind.SIMPLECLOUD;
        }
        if (t instanceof AmbiguousReferenceException) {
            return Kind.AMBIGUOUS;
        }
        if (t instanceof MaintenanceException) {
            return Kind.MAINTENANCE;
        }
        if (t instanceof TimeoutException) {
            return Kind.TIMEOUT;
        }
        if (t instanceof RejectedExecutionException) {
            return Kind.BUSY;
        }
        return null;
    }
}
