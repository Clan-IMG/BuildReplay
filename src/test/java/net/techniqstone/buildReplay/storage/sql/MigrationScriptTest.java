package net.techniqstone.buildReplay.storage.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

class MigrationScriptTest {

    @Test
    void splitsStatementsOnSemicolonsOutsideStringsAndComments() {
        String script = """
                -- Kommentar; mit Semikolon
                CREATE TABLE a (x VARCHAR(10) DEFAULT 'a;b');
                /* Block; Kommentar */ INSERT INTO a VALUES ("c;d");
                # noch ein Kommentar;
                INSERT INTO a VALUES ('it''s;fine')
                """;
        List<String> statements = MigrationScript.split(script);
        assertEquals(3, statements.size(), statements.toString());
        assertEquals("CREATE TABLE a (x VARCHAR(10) DEFAULT 'a;b')", statements.get(0));
        assertTrue(statements.get(1).endsWith("INSERT INTO a VALUES (\"c;d\")"), statements.get(1));
        assertEquals("INSERT INTO a VALUES ('it''s;fine')", statements.get(2));
    }

    @Test
    void ignoresEmptyStatementsAndTrailingSemicolons() {
        assertEquals(List.of("SELECT 1", "SELECT 2"), MigrationScript.split(";;SELECT 1;\n;\nSELECT 2;\n"));
    }

    @Test
    void readsVersionAndDescriptionFromTheFileName() {
        MigrationScript script = MigrationScript.parse("V12__add_storage_columns.sql", "SELECT 1");
        assertEquals(12, script.version());
        assertEquals("add storage columns", script.description());
    }

    @Test
    void rejectsFileNamesWithoutVersion() {
        assertThrows(IllegalArgumentException.class, () -> MigrationScript.parse("baseline.sql", ""));
        assertThrows(IllegalArgumentException.class, () -> MigrationScript.parse("V0__zero.sql", ""));
        assertThrows(IllegalArgumentException.class, () -> MigrationScript.parse("V1_single_underscore.sql", ""));
    }

    @Test
    void baselineCreatesAllTablesWithThePrefixApplied() {
        SchemaMigrator migrator = new SchemaMigrator(new SqlTables("br_"), getClass().getClassLoader(),
                Clock.systemUTC(), new TestSupport.CapturingLog().logger());
        List<MigrationScript> scripts = migrator.scripts();
        assertEquals(1, scripts.get(0).version());
        assertEquals(scripts.get(scripts.size() - 1).version(), migrator.latestVersion());
        List<String> statements = scripts.get(0).statements("br_");
        for (String table : List.of("players", "sessions", "server_visits", "recordings", "segments")) {
            assertTrue(statements.stream().anyMatch(s -> s.startsWith("CREATE TABLE IF NOT EXISTS br_" + table + " (")),
                    "Tabelle " + table + " fehlt");
        }
        assertFalse(String.join("\n", statements).contains("{prefix}"));
        assertTrue(statements.stream().allMatch(s -> s.contains("ENGINE = InnoDB") && s.contains("utf8mb4")));
    }

    @Test
    void everyListedScriptFollowsTheNamingScheme() {
        for (String name : SchemaMigrator.SCRIPTS) {
            MigrationScript.parse(name, "");
        }
    }

    @Test
    void rejectsTablePrefixesThatAreNotIdentifiers() {
        assertThrows(IllegalArgumentException.class, () -> new SqlTables("br; DROP TABLE x"));
        assertThrows(IllegalArgumentException.class, () -> new SqlTables("a".repeat(33)));
        assertEquals("players", new SqlTables("").players());
    }
}
