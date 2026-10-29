package net.techniqstone.buildReplay.storage.sql;

import java.util.regex.Pattern;

/**
 * Tabellennamen mit Präfix. Das Präfix stammt aus der Konfiguration (bereits auf {@code [A-Za-z0-9_]{0,32}}
 * geprüft) und wird hier zur Sicherheit erneut validiert, weil es direkt in SQL-Text eingesetzt wird.
 */
record SqlTables(String prefix) {

    private static final Pattern VALID_PREFIX = Pattern.compile("[A-Za-z0-9_]{0,32}");

    SqlTables {
        if (prefix == null || !VALID_PREFIX.matcher(prefix).matches()) {
            throw new IllegalArgumentException("Ungültiges Tabellenpräfix: " + prefix);
        }
    }

    String players() {
        return prefix + "players";
    }

    String sessions() {
        return prefix + "sessions";
    }

    String visits() {
        return prefix + "server_visits";
    }

    String recordings() {
        return prefix + "recordings";
    }

    String segments() {
        return prefix + "segments";
    }

    String schemaVersion() {
        return prefix + "schema_version";
    }

    /** Name der MariaDB-Sperre, die gleichzeitige Migrationen mehrerer Server serialisiert. */
    String migrationLock() {
        return prefix + "migrate";
    }
}
