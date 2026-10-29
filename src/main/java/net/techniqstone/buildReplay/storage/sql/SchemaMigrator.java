package net.techniqstone.buildReplay.storage.sql;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Wendet die versionierten Skripte aus {@code db/migration} an und protokolliert sie in
 * {@code <prefix>schema_version}. Mehrere Server, die gleichzeitig starten, werden über die MariaDB-Sperre
 * {@code GET_LOCK('<prefix>migrate', 30)} serialisiert; wer die Sperre bekommt, liest den Stand erneut, sodass jede
 * Version genau einmal angewendet wird. Ein zweiter Lauf ist ein No-op.
 *
 * <p>Neue Versionen werden als weitere Datei ({@code V2__...sql}) angelegt und in {@link #SCRIPTS} eingetragen
 * (Ressourcen in einem Plugin-Jar lassen sich nicht zuverlässig auflisten). Weil DDL in MariaDB nicht
 * transaktional ist, müssen Anweisungen wiederholbar sein ({@code IF NOT EXISTS}, {@code ADD COLUMN IF NOT EXISTS}).
 */
final class SchemaMigrator {

    /** Alle Migrationsskripte in {@code db/migration}, aufsteigend. */
    static final List<String> SCRIPTS = List.of("V1__baseline.sql");
    static final String RESOURCE_DIR = "db/migration/";
    static final int LOCK_TIMEOUT_SECONDS = 30;

    private final SqlTables tables;
    private final ClassLoader resources;
    private final Clock clock;
    private final Logger logger;
    private volatile List<MigrationScript> loaded;

    SchemaMigrator(SqlTables tables, ClassLoader resources, Clock clock, Logger logger) {
        this.tables = tables;
        this.resources = resources;
        this.clock = clock;
        this.logger = logger;
    }

    /** Höchste Version, die dieses Plugin kennt. */
    int latestVersion() {
        List<MigrationScript> scripts = scripts();
        return scripts.isEmpty() ? 0 : scripts.get(scripts.size() - 1).version();
    }

    /** Lädt und prüft die Skripte (einmalig). Fehlende Dateien sind ein Paketierungsfehler. */
    List<MigrationScript> scripts() {
        List<MigrationScript> result = loaded;
        if (result != null) {
            return result;
        }
        List<MigrationScript> scripts = new ArrayList<>();
        Set<Integer> versions = new HashSet<>();
        for (String name : SCRIPTS) {
            String path = RESOURCE_DIR + name;
            try (InputStream in = resources.getResourceAsStream(path)) {
                if (in == null) {
                    throw new IllegalStateException("Migrationsskript fehlt im Plugin: " + path);
                }
                MigrationScript script = MigrationScript.parse(name, new String(in.readAllBytes(), StandardCharsets.UTF_8));
                if (!versions.add(script.version())) {
                    throw new IllegalStateException("Doppelte Migrationsversion " + script.version());
                }
                scripts.add(script);
            } catch (IOException e) {
                throw new IllegalStateException("Migrationsskript " + path + " nicht lesbar: " + e.getMessage(), e);
            }
        }
        scripts.sort(Comparator.comparingInt(MigrationScript::version));
        result = List.copyOf(scripts);
        loaded = result;
        return result;
    }

    /**
     * Bringt das Schema auf den neuesten Stand und liefert die erreichte Version. Läuft im Autocommit-Modus auf der
     * übergebenen Verbindung (die Sperre gehört zur Verbindung).
     */
    int migrate(Connection connection) throws SQLException {
        List<MigrationScript> scripts = scripts();
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(true);
        try {
            acquireLock(connection);
            try {
                return applyPending(connection, scripts);
            } finally {
                releaseLock(connection);
            }
        } finally {
            try {
                connection.setAutoCommit(autoCommit);
            } catch (SQLException ignored) {
                // Verbindung ist ohnehin unbrauchbar
            }
        }
    }

    private int applyPending(Connection connection, List<MigrationScript> scripts) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + tables.schemaVersion() + " ("
                    + "version INT NOT NULL, "
                    + "description VARCHAR(200) NOT NULL, "
                    + "applied_at DATETIME(3) NOT NULL, "
                    + "PRIMARY KEY (version)"
                    + ") ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin");
        }
        int current = currentVersion(connection);
        for (MigrationScript script : scripts) {
            if (script.version() <= current) {
                continue;
            }
            List<String> statements = script.statements(tables.prefix());
            try (Statement st = connection.createStatement()) {
                for (String sql : statements) {
                    st.execute(sql);
                }
            } catch (SQLException e) {
                throw new SQLException("Migration V" + script.version() + " (" + script.description()
                        + ") fehlgeschlagen: " + e.getMessage(), e.getSQLState(), e.getErrorCode(), e);
            }
            try (PreparedStatement ps = connection.prepareStatement("INSERT IGNORE INTO " + tables.schemaVersion()
                    + " (version, description, applied_at) VALUES (?, ?, ?)")) {
                ps.setInt(1, script.version());
                ps.setString(2, SqlValues.clipRequired(script.description(), 200));
                SqlValues.setInstant(ps, 3, clock.instant());
                ps.executeUpdate();
            }
            logger.info("Datenbankschema auf Version " + script.version() + " aktualisiert (" + script.description()
                    + ", Präfix '" + tables.prefix() + "')");
            current = script.version();
        }
        int latest = scripts.isEmpty() ? 0 : scripts.get(scripts.size() - 1).version();
        if (current > latest) {
            logger.warning("Datenbankschema hat Version " + current + ", dieses Plugin kennt nur bis " + latest
                    + " - wurde BuildReplay herabgestuft? Es wird ohne Migration weitergearbeitet.");
        }
        return current;
    }

    int currentVersion(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(version), 0) FROM " + tables.schemaVersion())) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private void acquireLock(Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT GET_LOCK(?, ?)")) {
            ps.setString(1, tables.migrationLock());
            ps.setInt(2, LOCK_TIMEOUT_SECONDS);
            try (ResultSet rs = ps.executeQuery()) {
                Object result = rs.next() ? rs.getObject(1) : null;
                if (!(result instanceof Number n) || n.intValue() != 1) {
                    throw new SQLException("Migrationssperre '" + tables.migrationLock() + "' nicht innerhalb von "
                            + LOCK_TIMEOUT_SECONDS + " s erhalten (migriert gerade ein anderer Server?)");
                }
            }
        }
    }

    private void releaseLock(Connection connection) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            ps.setString(1, tables.migrationLock());
            ps.executeQuery().close();
        } catch (SQLException e) {
            // Die Sperre endet spätestens mit der Verbindung.
            logger.log(Level.FINE, "Migrationssperre konnte nicht freigegeben werden: " + e.getMessage());
        }
    }
}
