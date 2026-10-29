package net.techniqstone.buildReplay.session;

import java.util.List;
import java.util.UUID;

/**
 * Eine Seite der Sitzungsliste (Standard: letzte 14 Tage). Datenquelle ist SimpleCloud (maßgeblich für Login,
 * Logout, Dauer), ergänzt um die Abschnittsdaten aus MariaDB; bei SimpleCloud-Ausfall nur MariaDB.
 */
public record SessionListPage(
        UUID player,
        int page,
        int pageSize,
        boolean hasNext,
        int totalInWindow,
        Source source,
        List<SessionListEntry> entries,
        List<String> warnings) {

    public enum Source { SIMPLECLOUD, DATABASE, SIMPLECLOUD_ONLY }

    public SessionListPage {
        entries = List.copyOf(entries);
        warnings = List.copyOf(warnings);
    }
}
