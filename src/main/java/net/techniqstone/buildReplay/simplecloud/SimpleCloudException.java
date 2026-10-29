package net.techniqstone.buildReplay.simplecloud;

/** Fehler beim Zugriff auf SimpleCloud (REST oder Java-API). */
public final class SimpleCloudException extends RuntimeException {

    public enum Reason {
        /** base-url, network-id oder network-secret fehlen. */
        NOT_CONFIGURED,
        /** 401/403 - falsche Netzwerk-ID oder falsches Secret. */
        UNAUTHORIZED,
        NOT_FOUND,
        /** 400/422 - z. B. ungültige Parameter. */
        INVALID_REQUEST,
        /** 429. */
        RATE_LIMITED,
        /** Zeitüberschreitung, Verbindungsfehler, 408/502/503/504. */
        UNAVAILABLE,
        /** Circuit-Breaker offen - Anfrage gar nicht erst gesendet. */
        CIRCUIT_OPEN,
        /** Antwort nicht lesbar oder unvollständig. */
        INVALID_RESPONSE,
        UNKNOWN
    }

    private final Reason reason;
    private final int statusCode;

    public SimpleCloudException(Reason reason, int statusCode, String message) {
        super(message);
        this.reason = reason;
        this.statusCode = statusCode;
    }

    public SimpleCloudException(Reason reason, int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.statusCode = statusCode;
    }

    public Reason reason() {
        return reason;
    }

    /** HTTP-Status oder -1, wenn keine Antwort vorlag. */
    public int statusCode() {
        return statusCode;
    }

    /** Ob ein erneuter Versuch sinnvoll ist. */
    public boolean retryable() {
        return reason == Reason.UNAVAILABLE || reason == Reason.RATE_LIMITED || reason == Reason.UNKNOWN;
    }
}
