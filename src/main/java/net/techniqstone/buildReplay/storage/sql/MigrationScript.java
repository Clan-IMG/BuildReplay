package net.techniqstone.buildReplay.storage.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Versioniertes Migrationsskript {@code db/migration/V<version>__<beschreibung>.sql}. {@code {prefix}} im Text wird
 * durch das Tabellenpräfix ersetzt; mehrere Anweisungen werden durch {@code ;} getrennt. Kommentare
 * ({@code -- ...}, {@code # ...}, {@code /* ... * /}) und Semikolons in Zeichenketten werden beim Aufteilen beachtet.
 */
record MigrationScript(int version, String description, String fileName, String body) {

    private static final Pattern FILE_NAME = Pattern.compile("V(\\d+)__([A-Za-z0-9_\\-]+)\\.sql");
    static final String PREFIX_PLACEHOLDER = "{prefix}";

    MigrationScript {
        if (version < 1) {
            throw new IllegalArgumentException("Migrationsversion muss >= 1 sein: " + fileName);
        }
    }

    /** Liest Version und Beschreibung aus dem Dateinamen ({@code V1__baseline.sql} → 1, "baseline"). */
    static MigrationScript parse(String fileName, String body) {
        Matcher m = FILE_NAME.matcher(fileName);
        if (!m.matches()) {
            throw new IllegalArgumentException("Ungültiger Migrationsdateiname: " + fileName
                    + " (erwartet V<Nummer>__<Beschreibung>.sql)");
        }
        return new MigrationScript(Integer.parseInt(m.group(1)), m.group(2).replace('_', ' '), fileName, body);
    }

    /** Ausführbare Anweisungen in Reihenfolge, mit eingesetztem Präfix. */
    List<String> statements(String prefix) {
        return split(body.replace(PREFIX_PLACEHOLDER, prefix));
    }

    /** Teilt ein Skript an {@code ;} außerhalb von Zeichenketten und Kommentaren; Kommentare entfallen. */
    static List<String> split(String script) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int length = script.length();
        int i = 0;
        while (i < length) {
            char c = script.charAt(i);
            char next = i + 1 < length ? script.charAt(i + 1) : '\0';
            if (c == '-' && next == '-' && (i + 2 >= length || Character.isWhitespace(script.charAt(i + 2)))
                    || c == '#') {
                // Zeilenkommentar bis zum Zeilenende überspringen
                while (i < length && script.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && next == '*') {
                int end = script.indexOf("*/", i + 2);
                i = end < 0 ? length : end + 2;
                current.append(' ');
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                int start = i;
                i++;
                while (i < length) {
                    char q = script.charAt(i);
                    if (q == '\\' && c != '`' && i + 1 < length) {
                        i += 2;
                        continue;
                    }
                    if (q == c) {
                        if (i + 1 < length && script.charAt(i + 1) == c) {
                            i += 2; // verdoppeltes Anführungszeichen
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                current.append(script, start, Math.min(i, length));
                continue;
            }
            if (c == ';') {
                addStatement(result, current);
                i++;
                continue;
            }
            current.append(c);
            i++;
        }
        addStatement(result, current);
        return List.copyOf(result);
    }

    private static void addStatement(List<String> result, StringBuilder current) {
        String statement = current.toString().strip();
        if (!statement.isEmpty()) {
            result.add(statement);
        }
        current.setLength(0);
    }
}
