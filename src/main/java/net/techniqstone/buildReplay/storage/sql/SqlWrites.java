package net.techniqstone.buildReplay.storage.sql;

import com.google.gson.Gson;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Logger;
import net.techniqstone.buildReplay.session.NetworkSession;
import net.techniqstone.buildReplay.storage.model.RecordingStatus;
import net.techniqstone.buildReplay.storage.model.RecordingUpsert;
import net.techniqstone.buildReplay.storage.model.SegmentUpsert;
import net.techniqstone.buildReplay.storage.model.SessionSource;
import net.techniqstone.buildReplay.storage.model.SessionStatus;
import net.techniqstone.buildReplay.storage.model.SessionUpsert;
import net.techniqstone.buildReplay.storage.model.VisitStart;
import org.jspecify.annotations.Nullable;

/**
 * Führt einen {@link WriteOp} auf einer Verbindung aus. Der Aufrufer öffnet die Transaktion (READ COMMITTED),
 * committet und wiederholt bei Deadlocks; jeder Vorgang ist über seinen natürlichen Schlüssel idempotent.
 *
 * <p>Sperrreihenfolge (gegen Deadlocks zwischen Servern): erst die Spielerzeile, dann die Sitzungszeile des
 * Schlüssels, dann die Zielsitzung, auf die {@code merged_into} zeigt, zuletzt Kindzeilen. Sitzungszeilen werden
 * mit {@code SELECT ... FOR UPDATE} gesperrt, solange Kindzeilen geschrieben werden - eine gleichzeitige
 * Zusammenführung wartet deshalb, bis der Aufenthalt/Abschnitt eingetragen ist, und hängt ihn dann mit um.
 */
final class SqlWrites {

    private static final int MAX_MERGE_HOPS = 16;
    private static final int CLOUD_ID_LENGTH = 36;
    private static final String TERMINAL_RECORDING_STATES = "'" + RecordingStatus.FINALIZED + "', '"
            + RecordingStatus.DISCARDED + "', '" + RecordingStatus.INTERRUPTED + "', '" + RecordingStatus.FAILED + "'";

    /** Gesperrte Sitzungszeile. */
    record LockedSession(long id, String sessionKey, UUID playerUuid, SessionState state, @Nullable Long mergedInto) {
    }

    private final SqlTables t;
    private final Gson gson;
    private final Logger logger;

    SqlWrites(SqlTables tables, Gson gson, Logger logger) {
        this.t = tables;
        this.gson = gson;
        this.logger = logger;
    }

    void apply(Connection c, WriteOp op, Instant now) throws SQLException {
        switch (op) {
            case WriteOp.UpsertPlayer p -> upsertPlayer(c, p.playerUuid(), p.name(), p.seenAt());
            case WriteOp.UpsertSession s -> upsertSession(c, s.session(), now);
            case WriteOp.CloseSession s -> closeSession(c, s.sessionKey(), s.logoutTime(), s.durationSeconds(), now);
            case WriteOp.MergeProvisional m -> mergeProvisional(c, m.provisionalKey(), m.target(), now);
            case WriteOp.StartVisit v -> startVisit(c, v.visit(), now);
            case WriteOp.EndVisit v -> endVisit(c, v);
            case WriteOp.UpsertRecording r -> upsertRecording(c, r.recording(), now);
            case WriteOp.FinishRecording r -> finishRecording(c, r, now);
            case WriteOp.UpsertSegment s -> upsertSegment(c, s.segment(), now);
            case WriteOp.UpdateSegmentStatus s -> updateSegmentStatus(c, s, now);
        }
    }

    // ------------------------------------------------------------------ Spieler

    void upsertPlayer(Connection c, UUID player, String name, Instant seenAt) throws SQLException {
        // Jede Zuweisung liest nur Spalten, die rechts von ihr (oder gar nicht) zugewiesen werden: so ist das
        // Ergebnis gleich, ob MariaDB links-nach-rechts oder simultan (SIMULTANEOUS_ASSIGNMENT) auswertet.
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + t.players()
                + " (player_uuid, last_name, first_seen, last_seen) VALUES (?, ?, ?, ?)"
                + " ON DUPLICATE KEY UPDATE"
                + " last_name = IF(last_name IS NULL OR VALUES(last_seen) >= last_seen, VALUES(last_name), last_name),"
                + " first_seen = LEAST(first_seen, VALUES(first_seen)),"
                + " last_seen = GREATEST(last_seen, VALUES(last_seen))")) {
            SqlValues.setUuid(ps, 1, player);
            ps.setString(2, SqlValues.clipRequired(name, 16));
            SqlValues.setInstant(ps, 3, seenAt);
            SqlValues.setInstant(ps, 4, seenAt);
            ps.executeUpdate();
        }
    }

    /**
     * Legt eine Platzhalterzeile an, falls der Spieler noch unbekannt ist, und ergänzt einen fehlenden Namen.
     * Bewusst {@code ON DUPLICATE KEY UPDATE} statt {@code INSERT IGNORE} + {@code UPDATE}: so wird die Spielerzeile
     * sofort exklusiv gesperrt (erster Schritt der Sperrreihenfolge) statt erst geteilt und dann exklusiv - dieses
     * Hochstufen führte bei mehreren Servern zu Deadlocks.
     */
    void ensurePlayer(Connection c, UUID player, @Nullable String name, Instant seenAt) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + t.players()
                + " (player_uuid, last_name, first_seen, last_seen) VALUES (?, ?, ?, ?)"
                + " ON DUPLICATE KEY UPDATE last_name = COALESCE(last_name, VALUES(last_name))")) {
            SqlValues.setUuid(ps, 1, player);
            SqlValues.setString(ps, 2, SqlValues.clip(name, 16));
            SqlValues.setInstant(ps, 3, seenAt);
            SqlValues.setInstant(ps, 4, seenAt);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ Sitzungen

    void upsertSession(Connection c, SessionUpsert u, Instant now) throws SQLException {
        ensurePlayer(c, u.playerUuid(), null, firstNonNull(u.loginTime(), u.logoutTime(), now));
        String cloud = cloudIdFor(u.sessionKey(), u.cloudSessionId(), u.source());
        SessionState initial = SessionState.initial(cloud, u.source(), u.loginTime(), u.logoutTime(),
                u.durationSeconds());
        LockedSession row = resolveOrCreate(c, u.sessionKey(), u.playerUuid(), initial, now);
        applyMerge(c, row, cloud, u.source(), u.loginTime(), u.logoutTime(), u.durationSeconds(), now);
    }

    void closeSession(Connection c, String key, Instant logout, @Nullable Long duration, Instant now)
            throws SQLException {
        LockedSession row = findLocked(c, key);
        if (row == null) {
            // Ohne Spieler-UUID lässt sich keine Platzhaltersitzung anlegen; ohne Sitzung gibt es nichts zu schließen.
            logger.fine("Sitzung " + key + " unbekannt - Logout wird nicht gespeichert");
            return;
        }
        row = follow(c, row);
        applyMerge(c, row, null, row.state().source(), null, logout, duration, now);
    }

    void mergeProvisional(Connection c, String provisionalKey, SessionUpsert target, Instant now) throws SQLException {
        if (provisionalKey.equals(target.sessionKey())) {
            logger.warning("Zusammenführung von " + provisionalKey + " in sich selbst ignoriert");
            upsertSession(c, target, now);
            return;
        }
        ensurePlayer(c, target.playerUuid(), null, firstNonNull(target.loginTime(), target.logoutTime(), now));
        LockedSession provisional = lockBy(c, "session_key = ?", provisionalKey);
        if (provisional == null) {
            // Später eintreffende Schreibvorgänge anderer Server für diesen Schlüssel sollen der Weiterleitung folgen.
            insertSession(c, provisionalKey, target.playerUuid(),
                    new SessionState(null, SessionSource.PROVISIONAL, SessionStatus.ACTIVE, null, null, null), now);
            provisional = lockBy(c, "session_key = ?", provisionalKey);
            if (provisional == null) {
                throw new SQLException("Vorläufige Sitzung " + provisionalKey + " ließ sich nicht anlegen");
            }
        }
        if (provisional.state().cloudSessionId() != null) {
            // Die SimpleCloud-ID gehört nach der Zusammenführung allein der Zielsitzung.
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + t.sessions()
                    + " SET cloud_session_id = NULL WHERE id = ?")) {
                ps.setLong(1, provisional.id());
                ps.executeUpdate();
            }
        }
        String cloud = cloudIdFor(target.sessionKey(), target.cloudSessionId(), target.source());
        SessionState initial = SessionState.initial(cloud, target.source(), target.loginTime(), target.logoutTime(),
                target.durationSeconds());
        LockedSession merged = resolveOrCreate(c, target.sessionKey(), target.playerUuid(), initial, now);
        if (merged.id() == provisional.id()) {
            logger.warning("Zusammenführung von " + provisionalKey + " in " + target.sessionKey()
                    + " würde einen Kreis bilden - übersprungen");
            return;
        }
        applyMerge(c, merged, cloud, target.source(), target.loginTime(), target.logoutTime(),
                target.durationSeconds(), now);

        long from = provisional.id();
        long to = merged.id();
        int visits = moveChildren(c, t.visits(), from, to);
        int recordings = moveChildren(c, t.recordings(), from, to);
        int segments = moveChildren(c, t.segments(), from, to);
        try (PreparedStatement ps = c.prepareStatement("UPDATE " + t.sessions()
                + " SET merged_into = ? WHERE merged_into = ? AND id <> ?")) {
            ps.setLong(1, to);
            ps.setLong(2, from);
            ps.setLong(3, to);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("UPDATE " + t.sessions()
                + " SET status = ?, merged_into = ?, updated_at = ? WHERE id = ?")) {
            ps.setString(1, SessionStatus.MERGED.name());
            ps.setLong(2, to);
            SqlValues.setInstant(ps, 3, now);
            ps.setLong(4, from);
            ps.executeUpdate();
        }
        logger.fine("Vorläufige Sitzung " + provisionalKey + " in " + target.sessionKey() + " zusammengeführt ("
                + visits + " Aufenthalte, " + recordings + " Aufnahmen, " + segments + " Abschnitte)");
    }

    private int moveChildren(Connection c, String table, long from, long to) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE " + table + " SET session_id = ? WHERE session_id = ?")) {
            ps.setLong(1, to);
            ps.setLong(2, from);
            return ps.executeUpdate();
        }
    }

    /**
     * Sperrt die Sitzung zum Schlüssel (legt sie bei Bedarf an) und folgt {@code merged_into} bis zur Zielsitzung.
     * Neue Zeilen erhalten {@code initial}; die Aufrufer wenden danach ihre Zusammenführung an.
     */
    LockedSession resolveOrCreate(Connection c, String key, UUID player, SessionState initial, Instant now)
            throws SQLException {
        LockedSession row = findLocked(c, key);
        if (row == null) {
            insertSession(c, key, player, initial, now);
            row = findLocked(c, key);
            if (row == null) {
                throw new SQLException("Sitzung " + key + " ließ sich nicht anlegen");
            }
        }
        return follow(c, row);
    }

    /** Platzhalter für einen unbekannten Schlüssel aus einem Aufenthalt/einer Aufnahme/einem Abschnitt. */
    static SessionState placeholder(String key) {
        if (isProvisional(key)) {
            return new SessionState(null, SessionSource.PROVISIONAL, SessionStatus.ACTIVE, null, null, null);
        }
        return new SessionState(fitsCloudId(key) ? key : null, SessionSource.SIMPLECLOUD, SessionStatus.ACTIVE,
                null, null, null);
    }

    private @Nullable LockedSession findLocked(Connection c, String key) throws SQLException {
        LockedSession row = lockBy(c, "session_key = ?", key);
        if (row == null && !isProvisional(key) && fitsCloudId(key)) {
            row = lockBy(c, "cloud_session_id = ?", key);
        }
        return row;
    }

    private LockedSession follow(Connection c, LockedSession start) throws SQLException {
        LockedSession current = start;
        Set<Long> seen = new HashSet<>();
        for (int hop = 0; current.mergedInto() != null && hop < MAX_MERGE_HOPS; hop++) {
            if (!seen.add(current.id())) {
                logger.warning("Kreis in merged_into bei Sitzung " + current.sessionKey() + " - Weiterleitung endet dort");
                break;
            }
            LockedSession next = lockBy(c, "id = ?", current.mergedInto());
            if (next == null) {
                break;
            }
            current = next;
        }
        return current;
    }

    private @Nullable LockedSession lockBy(Connection c, String condition, Object value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id, session_key, cloud_session_id, player_uuid, source,"
                + " status, login_time, logout_time, duration_seconds, merged_into FROM " + t.sessions()
                + " WHERE " + condition + " FOR UPDATE")) {
            ps.setObject(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                SessionState state = new SessionState(
                        rs.getString("cloud_session_id"),
                        SqlValues.enumValue(SessionSource.class, rs.getString("source"), SessionSource.PROVISIONAL),
                        SqlValues.enumValue(SessionStatus.class, rs.getString("status"), SessionStatus.ACTIVE),
                        SqlValues.instant(rs, "login_time"),
                        SqlValues.instant(rs, "logout_time"),
                        SqlValues.nullableLong(rs, "duration_seconds"));
                return new LockedSession(rs.getLong("id"), rs.getString("session_key"),
                        SqlValues.uuid(rs, "player_uuid"), state, SqlValues.nullableLong(rs, "merged_into"));
            }
        }
    }

    private void insertSession(Connection c, String key, UUID player, SessionState state, Instant now)
            throws SQLException {
        String cloud = state.cloudSessionId();
        if (cloud != null && cloudIdTaken(c, cloud, -1)) {
            cloud = null;
        }
        // "id = id": bei einem gleichzeitig eingefügten Duplikat nur die Zeile exklusiv sperren, nichts ändern.
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + t.sessions()
                + " (session_key, cloud_session_id, player_uuid, source, status, login_time, logout_time,"
                + " duration_seconds, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                + " ON DUPLICATE KEY UPDATE id = id")) {
            ps.setString(1, key);
            SqlValues.setString(ps, 2, cloud);
            SqlValues.setUuid(ps, 3, player);
            ps.setString(4, state.source().name());
            ps.setString(5, state.status().name());
            SqlValues.setInstant(ps, 6, state.loginTime());
            SqlValues.setInstant(ps, 7, state.logoutTime());
            SqlValues.setLong(ps, 8, state.durationSeconds());
            SqlValues.setInstant(ps, 9, now);
            SqlValues.setInstant(ps, 10, now);
            ps.executeUpdate();
        }
    }

    private void applyMerge(Connection c, LockedSession row, @Nullable String cloud, SessionSource source,
                            @Nullable Instant login, @Nullable Instant logout, @Nullable Long duration, Instant now)
            throws SQLException {
        SessionState merged = row.state().merge(cloud, source, login, logout, duration);
        String newCloud = merged.cloudSessionId();
        if (newCloud != null && !newCloud.equals(row.state().cloudSessionId()) && cloudIdTaken(c, newCloud, row.id())) {
            merged = merged.withCloudSessionId(row.state().cloudSessionId());
        }
        try (PreparedStatement ps = c.prepareStatement("UPDATE " + t.sessions()
                + " SET cloud_session_id = ?, source = ?, status = ?, login_time = ?, logout_time = ?,"
                + " duration_seconds = ?, updated_at = ? WHERE id = ?")) {
            SqlValues.setString(ps, 1, merged.cloudSessionId());
            ps.setString(2, merged.source().name());
            ps.setString(3, merged.status().name());
            SqlValues.setInstant(ps, 4, merged.loginTime());
            SqlValues.setInstant(ps, 5, merged.logoutTime());
            SqlValues.setLong(ps, 6, merged.durationSeconds());
            SqlValues.setInstant(ps, 7, now);
            ps.setLong(8, row.id());
            ps.executeUpdate();
        }
    }

    private boolean cloudIdTaken(Connection c, String cloud, long exceptId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM " + t.sessions()
                + " WHERE cloud_session_id = ? AND id <> ?")) {
            ps.setString(1, cloud);
            ps.setLong(2, exceptId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    // ------------------------------------------------------------------ Aufenthalte

    void startVisit(Connection c, VisitStart v, Instant now) throws SQLException {
        ensurePlayer(c, v.playerUuid(), v.playerName(), v.joinedAt());
        LockedSession session = resolveOrCreate(c, v.sessionKey(), v.playerUuid(), placeholder(v.sessionKey()), now);
        try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO " + t.visits()
                + " (visit_key, session_id, player_uuid, server_name, instance_id, joined_at) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, v.visitKey());
            ps.setLong(2, session.id());
            SqlValues.setUuid(ps, 3, v.playerUuid());
            ps.setString(4, SqlValues.clipRequired(v.serverName(), 64));
            ps.setString(5, SqlValues.clipRequired(v.instanceId(), 64));
            SqlValues.setInstant(ps, 6, v.joinedAt());
            ps.executeUpdate();
        }
    }

    private void endVisit(Connection c, WriteOp.EndVisit v) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE " + t.visits()
                + " SET left_at = ?, end_reason = ? WHERE visit_key = ? AND left_at IS NULL")) {
            SqlValues.setInstant(ps, 1, v.leftAt());
            ps.setString(2, v.reason().name());
            ps.setString(3, v.visitKey());
            if (ps.executeUpdate() == 0) {
                logger.fine("Aufenthalt " + v.visitKey() + " unbekannt oder bereits beendet");
            }
        }
    }

    // ------------------------------------------------------------------ Aufnahmen

    void upsertRecording(Connection c, RecordingUpsert r, Instant now) throws SQLException {
        ensurePlayer(c, r.playerUuid(), r.playerName(), r.startedAt());
        Long sessionId = null;
        if (r.sessionKey() != null) {
            sessionId = resolveOrCreate(c, r.sessionKey(), r.playerUuid(), placeholder(r.sessionKey()), now).id();
        }
        // Ein abgeschlossener Status wird nie durch eine (wiederholte) Anlage überschrieben.
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + t.recordings()
                + " (raw_name, session_id, player_uuid, player_name, server_name, instance_id, world_name, started_at,"
                + " status, relevant_actions, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)"
                + " ON DUPLICATE KEY UPDATE"
                + " status = IF(status IN (" + TERMINAL_RECORDING_STATES + "), status, VALUES(status)),"
                + " session_id = COALESCE(VALUES(session_id), session_id),"
                + " world_name = COALESCE(VALUES(world_name), world_name),"
                + " player_name = VALUES(player_name),"
                + " updated_at = VALUES(updated_at)")) {
            ps.setString(1, r.rawName());
            SqlValues.setLong(ps, 2, sessionId);
            SqlValues.setUuid(ps, 3, r.playerUuid());
            ps.setString(4, SqlValues.clipRequired(r.playerName(), 16));
            ps.setString(5, SqlValues.clipRequired(r.serverName(), 64));
            ps.setString(6, SqlValues.clipRequired(r.instanceId(), 64));
            SqlValues.setString(ps, 7, SqlValues.clip(r.worldName(), 128));
            SqlValues.setInstant(ps, 8, r.startedAt());
            ps.setString(9, r.status().name());
            SqlValues.setInstant(ps, 10, now);
            ps.executeUpdate();
        }
    }

    private void finishRecording(Connection c, WriteOp.FinishRecording f, Instant now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE " + t.recordings()
                + " SET status = ?, ended_at = COALESCE(?, ended_at), relevant_actions = ?, error = ?, updated_at = ?"
                + " WHERE raw_name = ?")) {
            ps.setString(1, f.status().name());
            SqlValues.setInstant(ps, 2, f.endedAt());
            ps.setInt(3, Math.max(0, f.relevantActions()));
            SqlValues.setString(ps, 4, SqlValues.clip(f.error(), 500));
            SqlValues.setInstant(ps, 5, now);
            ps.setString(6, f.rawName());
            if (ps.executeUpdate() == 0) {
                logger.fine("Aufnahme " + f.rawName() + " unbekannt - Abschluss nicht gespeichert");
            }
        }
    }

    // ------------------------------------------------------------------ Abschnitte

    void upsertSegment(Connection c, SegmentUpsert s, Instant now) throws SQLException {
        ensurePlayer(c, s.playerUuid(), s.playerName(), s.startedAt());
        LockedSession session = resolveOrCreate(c, s.sessionKey(), s.playerUuid(), placeholder(s.sessionKey()), now);
        Long recordingId = s.rawName() == null ? null : recordingId(c, s.rawName());
        // DELETED ist endgültig; SAVING überschreibt keinen späteren Zustand (z. B. bei erneut eingespielter Outbox).
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + t.segments()
                + " (replay_name, session_id, recording_id, player_uuid, player_name, server_name, world_name,"
                + " storage_backend, storage_ref, started_at, ended_at, first_action_at, last_action_at, duration_ticks,"
                + " pre_roll_ms, post_roll_ms, relevant_actions, action_summary, status, size_bytes, created_at,"
                + " updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                + " ON DUPLICATE KEY UPDATE"
                + " status = CASE WHEN status = 'DELETED' THEN status"
                + " WHEN VALUES(status) = 'SAVING' AND status <> 'SAVING' THEN status ELSE VALUES(status) END,"
                + " session_id = VALUES(session_id),"
                + " recording_id = COALESCE(VALUES(recording_id), recording_id),"
                + " player_name = VALUES(player_name),"
                + " server_name = VALUES(server_name),"
                + " world_name = COALESCE(VALUES(world_name), world_name),"
                + " storage_backend = VALUES(storage_backend),"
                + " storage_ref = COALESCE(VALUES(storage_ref), storage_ref),"
                + " started_at = VALUES(started_at),"
                + " ended_at = VALUES(ended_at),"
                + " first_action_at = VALUES(first_action_at),"
                + " last_action_at = VALUES(last_action_at),"
                + " duration_ticks = VALUES(duration_ticks),"
                + " pre_roll_ms = VALUES(pre_roll_ms),"
                + " post_roll_ms = VALUES(post_roll_ms),"
                + " relevant_actions = VALUES(relevant_actions),"
                + " action_summary = VALUES(action_summary),"
                + " size_bytes = COALESCE(VALUES(size_bytes), size_bytes),"
                + " updated_at = VALUES(updated_at)")) {
            int i = 1;
            ps.setString(i++, s.replayName());
            ps.setLong(i++, session.id());
            SqlValues.setLong(ps, i++, recordingId);
            SqlValues.setUuid(ps, i++, s.playerUuid());
            ps.setString(i++, SqlValues.clipRequired(s.playerName(), 16));
            ps.setString(i++, SqlValues.clipRequired(s.serverName(), 64));
            SqlValues.setString(ps, i++, SqlValues.clip(s.worldName(), 128));
            ps.setString(i++, SqlValues.clipRequired(s.storageBackend(), 16));
            SqlValues.setString(ps, i++, SqlValues.clip(s.storageRef(), 255));
            SqlValues.setInstant(ps, i++, s.startedAt());
            SqlValues.setInstant(ps, i++, s.endedAt());
            SqlValues.setInstant(ps, i++, s.firstActionAt());
            SqlValues.setInstant(ps, i++, s.lastActionAt());
            ps.setInt(i++, s.durationTicks());
            ps.setInt(i++, s.preRollMillis());
            ps.setInt(i++, s.postRollMillis());
            ps.setInt(i++, s.relevantActions());
            ps.setString(i++, gson.toJson(new TreeMap<>(s.actionSummary())));
            ps.setString(i++, s.status().name());
            SqlValues.setLong(ps, i++, s.sizeBytes());
            SqlValues.setInstant(ps, i++, now);
            SqlValues.setInstant(ps, i, now);
            ps.executeUpdate();
        }
    }

    private @Nullable Long recordingId(Connection c, String rawName) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM " + t.recordings() + " WHERE raw_name = ?")) {
            ps.setString(1, rawName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private void updateSegmentStatus(Connection c, WriteOp.UpdateSegmentStatus u, Instant now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE " + t.segments()
                + " SET status = ?, note = COALESCE(?, note), updated_at = ?"
                + " WHERE replay_name = ? AND (status <> 'DELETED' OR ? = 'DELETED')")) {
            ps.setString(1, u.status().name());
            SqlValues.setString(ps, 2, SqlValues.clip(u.note(), 500));
            SqlValues.setInstant(ps, 3, now);
            ps.setString(4, u.replayName());
            ps.setString(5, u.status().name());
            if (ps.executeUpdate() == 0) {
                logger.fine("Abschnitt " + u.replayName() + " unbekannt oder bereits gelöscht - Status "
                        + u.status() + " nicht gespeichert");
            }
        }
    }

    // ------------------------------------------------------------------ Hilfen

    static boolean isProvisional(String key) {
        return key.startsWith(NetworkSession.PROVISIONAL_PREFIX);
    }

    private static boolean fitsCloudId(String id) {
        return id.length() <= CLOUD_ID_LENGTH;
    }

    /** SimpleCloud-ID einer Sitzung: angegeben, sonst der Schlüssel selbst bei bestätigten Sitzungen. */
    static @Nullable String cloudIdFor(String key, @Nullable String cloudSessionId, SessionSource source) {
        String cloud = cloudSessionId;
        if (cloud == null && source == SessionSource.SIMPLECLOUD && !isProvisional(key)) {
            cloud = key;
        }
        return cloud != null && fitsCloudId(cloud) && !isProvisional(cloud) ? cloud : null;
    }

    private static Instant firstNonNull(@Nullable Instant a, @Nullable Instant b, Instant fallback) {
        return a != null ? a : b != null ? b : fallback;
    }
}
