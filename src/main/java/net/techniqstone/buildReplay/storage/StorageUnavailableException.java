package net.techniqstone.buildReplay.storage;

/** MariaDB ist deaktiviert oder gerade nicht erreichbar. Lesende Aufrufer zeigen dann eine verständliche Meldung. */
public final class StorageUnavailableException extends RuntimeException {

    public StorageUnavailableException(String message) {
        super(message);
    }

    public StorageUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
