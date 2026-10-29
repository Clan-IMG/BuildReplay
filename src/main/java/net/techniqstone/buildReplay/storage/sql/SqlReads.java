package net.techniqstone.buildReplay.storage.sql;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.techniqstone.buildReplay.storage.model.RecordingRow;
import net.techniqstone.buildReplay.storage.model.RecordingStatus;
import net.techniqstone.buildReplay.storage.model.SegmentRow;
import net.techniqstone.buildReplay.storage.model.SegmentStatus;
import net.techniqstone.buildReplay.storage.model.SessionRow;
import net.techniqstone.buildReplay.storage.model.SessionSource;
import net.techniqstone.buildReplay.storage.model.SessionStats;
import net.techniqstone.buildReplay.storage.model.SessionStatus;
import net.techniqstone.buildReplay.storage.model.VisitEndReason;
import net.techniqstone.buildReplay.storage.model.VisitRow;
import org.jspecify.annotations.Nullable;

/** Lesende Abfragen und die Abbildung der Zeilen auf die Modell-Records. Alle Methoden blockieren. */
final class SqlReads {

    private static final int MAX_LIMIT = 10_000;
    private static final int IN_CHUNK = 500;
    private static final Pattern HEX = Pattern.compile("[0-9a-f]*");
    private static final Type SUMMARY_TYPE = TypeToken.getParameterized(Map.class, String.class, Integer.class).getType();

    private final SqlTables t;
    private final Gson gson;
    private final String sessionSelect;
    private final String visitSelect;
    private final String recordingSelect;
    private final String segmentSelect;

    SqlReads(SqlTables tables, Gson gson) {
        this.t = tables;
        this.gson = gson;
        this.sessionSelect = "SELECT s.id, s.session_key, s.cloud_session_id, s.player_uuid, p.last_name, s.source,"
                + " s.status, s.login_time, s.logout_time, s.duration_seconds, s.merged_into, s.created_at,"
                + " s.updated_at FROM " + t.sessions() + " s LEFT JOIN " + t.players()
                + " p ON p.player_uuid = s.player_uuid";
        this.visitSelect = "SELECT id, visit_key, session_id, player_uuid, server_name, instance_id, joined_at, left_at,"
                + " end_reason FROM " + t.visits();
        this.recordingSelect = "SELECT id, raw_name, session_id, player_uuid, player_name, server_name, instance_id,"
                + " world_name, started_at, ended_at, status, relevant_actions, error FROM " + t.recordings();
        this.segmentSelect = "SELECT id, replay_name, session_id, recording_id, player_uuid, player_name, server_name,"
                + " world_name, storage_backend, storage_ref, started_at, ended_at, first_action_at, last_action_at,"
                + " duration_ticks, pre_roll_ms, post_roll_ms, relevant_actions, action_summary, status, size_bytes,"
                + " note, created_at FROM " + t.segments();
    }

    // ------------------------------------------------------------------ Sitzungen

    Optional<SessionRow> sessionByKey(Connection c, String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sessionSelect
                + " WHERE s.session_key = ? OR s.cloud_session_id = ? ORDER BY (s.session_key = ?) DESC, s.id LIMIT 1")) {
            ps.setString(1, key);
            ps.setString(2, key);
            ps.setString(3, key);
            return first(ps, this::session);
        }
    }

    Optional<SessionRow> sessionById(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sessionSelect + " WHERE s.id = ?")) {
            ps.setLong(1, id);
            return first(ps, this::session);
        }
    }

    /**
     * Kurzreferenz: {@code "p" + Präfix} sucht vorläufige Schlüssel ({@code prov-...}), sonst Präfix der
     * SimpleCloud-ID bzw. des Schlüssels. Bindestriche und Groß-/Kleinschreibung werden ignoriert.
     */
    List<SessionRow> sessionsByRef(Connection c, UUID player, String ref, int limit) throws SQLException {
        String normalized = ref.strip().toLowerCase(Locale.ROOT).replace("-", "");
        if (normalized.isEmpty() || limit <= 0) {
            return List.of();
        }
        String condition;
        String pattern;
        boolean provisional = normalized.startsWith("p");
        if (provisional) {
            String hex = normalized.substring(1);
            if (!HEX.matcher(hex).matches()) {
                return List.of();
            }
            pattern = "prov" + hex + "%";
            condition = "LOWER(REPLACE(s.session_key, '-', '')) LIKE ?";
        } else {
            if (!HEX.matcher(normalized).matches()) {
                return List.of();
            }
            pattern = normalized + "%";
            condition = "(LOWER(REPLACE(s.session_key, '-', '')) LIKE ?"
                    + " OR LOWER(REPLACE(COALESCE(s.cloud_session_id, ''), '-', '')) LIKE ?)";
        }
        try (PreparedStatement ps = c.prepareStatement(sessionSelect + " WHERE s.player_uuid = ? AND s.status <> 'MERGED'"
                + " AND " + condition + " ORDER BY COALESCE(s.login_time, s.created_at) DESC, s.id DESC LIMIT ?")) {
            int i = 1;
            SqlValues.setUuid(ps, i++, player);
            ps.setString(i++, pattern);
            if (!provisional) {
                ps.setString(i++, pattern);
            }
            ps.setInt(i, clampLimit(limit));
            return all(ps, this::session);
        }
    }

    List<SessionRow> sessionsOfPlayer(Connection c, UUID player, Instant since, int limit, int offset)
            throws SQLException {
        if (limit <= 0) {
            return List.of();
        }
        try (PreparedStatement ps = c.prepareStatement(sessionSelect + " WHERE s.player_uuid = ? AND s.status <> 'MERGED'"
                + " AND COALESCE(s.login_time, s.created_at) >= ?"
                + " ORDER BY COALESCE(s.login_time, s.created_at) DESC, s.id DESC LIMIT ? OFFSET ?")) {
            SqlValues.setUuid(ps, 1, player);
            SqlValues.setInstant(ps, 2, since);
            ps.setInt(3, clampLimit(limit));
            ps.setInt(4, Math.max(0, offset));
            return all(ps, this::session);
        }
    }

    int countSessionsOfPlayer(Connection c, UUID player, Instant since) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + t.sessions() + " s"
                + " WHERE s.player_uuid = ? AND s.status <> 'MERGED' AND COALESCE(s.login_time, s.created_at) >= ?")) {
            SqlValues.setUuid(ps, 1, player);
            SqlValues.setInstant(ps, 2, since);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    List<SessionRow> activeSessions(Connection c, int limit) throws SQLException {
        if (limit <= 0) {
            return List.of();
        }
        try (PreparedStatement ps = c.prepareStatement(sessionSelect
                + " WHERE s.status = 'ACTIVE' ORDER BY s.updated_at ASC, s.id ASC LIMIT ?")) {
            ps.setInt(1, clampLimit(limit));
            return all(ps, this::session);
        }
    }

    List<SessionRow> provisionalSessions(Connection c, int limit) throws SQLException {
        if (limit <= 0) {
            return List.of();
        }
        try (PreparedStatement ps = c.prepareStatement(sessionSelect
                + " WHERE s.source = 'PROVISIONAL' AND s.status <> 'MERGED' ORDER BY s.created_at ASC, s.id ASC LIMIT ?")) {
            ps.setInt(1, clampLimit(limit));
            return all(ps, this::session);
        }
    }

    /**
     * Statistik je angefragter SimpleCloud-ID. Treffer über {@code cloud_session_id} oder {@code session_key}
     * (SimpleCloud liefert UUIDs in Kleinbuchstaben, verglichen wird exakt und damit über die Indizes);
     * vorhandene Sitzungen ohne Abschnitte erscheinen mit {@link SessionStats#EMPTY}, unbekannte fehlen.
     */
    Map<String, SessionStats> statsByCloudSessionIds(Connection c, Collection<String> cloudSessionIds)
            throws SQLException {
        Map<String, String> requested = new LinkedHashMap<>();
        for (String id : cloudSessionIds) {
            if (id != null && !id.isBlank()) {
                requested.putIfAbsent(id.strip().toLowerCase(Locale.ROOT), id);
            }
        }
        Map<String, StatsBuilder> builders = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>(requested.keySet());
        for (int from = 0; from < keys.size(); from += IN_CHUNK) {
            List<String> chunk = keys.subList(from, Math.min(keys.size(), from + IN_CHUNK));
            String in = SqlValues.placeholders(chunk.size());
            try (PreparedStatement ps = c.prepareStatement(statsSelect()
                    + " WHERE s.status <> 'MERGED' AND (s.cloud_session_id IN (" + in + ")"
                    + " OR s.session_key IN (" + in + "))" + statsGroup())) {
                int i = 1;
                for (String key : chunk) {
                    ps.setString(i++, key);
                }
                for (String key : chunk) {
                    ps.setString(i++, key);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String cloud = rs.getString("cloud_session_id");
                        String sessionKey = rs.getString("session_key");
                        String match = cloud != null && requested.containsKey(cloud.toLowerCase(Locale.ROOT))
                                ? cloud.toLowerCase(Locale.ROOT) : sessionKey.toLowerCase(Locale.ROOT);
                        String original = requested.get(match);
                        if (original != null) {
                            builders.computeIfAbsent(original, k -> new StatsBuilder()).add(rs);
                        }
                    }
                }
            }
        }
        Map<String, SessionStats> result = new LinkedHashMap<>();
        builders.forEach((key, builder) -> result.put(key, builder.build()));
        return result;
    }

    Map<Long, SessionStats> statsBySessionIds(Connection c, Collection<Long> sessionIds) throws SQLException {
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(sessionIds));
        ids.removeIf(java.util.Objects::isNull);
        Map<Long, StatsBuilder> builders = new LinkedHashMap<>();
        for (int from = 0; from < ids.size(); from += IN_CHUNK) {
            List<Long> chunk = ids.subList(from, Math.min(ids.size(), from + IN_CHUNK));
            try (PreparedStatement ps = c.prepareStatement(statsSelect()
                    + " WHERE s.id IN (" + SqlValues.placeholders(chunk.size()) + ")" + statsGroup())) {
                int i = 1;
                for (Long id : chunk) {
                    ps.setLong(i++, id);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        builders.computeIfAbsent(rs.getLong("id"), k -> new StatsBuilder()).add(rs);
                    }
                }
            }
        }
        Map<Long, SessionStats> result = new HashMap<>();
        builders.forEach((key, builder) -> result.put(key, builder.build()));
        return result;
    }

    private String statsSelect() {
        // Eine Zeile je (Sitzung, Server); gelöschte Abschnitte zählen nicht.
        return "SELECT s.id, s.session_key, s.cloud_session_id, g.server_name, COUNT(g.id) AS segment_count,"
                + " COALESCE(SUM(g.status = 'AVAILABLE'), 0) AS available_count,"
                + " COALESCE(SUM(g.duration_ticks), 0) AS total_ticks,"
                + " MIN(g.started_at) AS first_at, MAX(g.ended_at) AS last_at"
                + " FROM " + t.sessions() + " s LEFT JOIN " + t.segments() + " g"
                + " ON g.session_id = s.id AND g.status <> 'DELETED'";
    }

    private static String statsGroup() {
        return " GROUP BY s.id, s.session_key, s.cloud_session_id, g.server_name";
    }

    /** Summiert die Zeilen je Server zu einer Sitzungsstatistik. */
    private static final class StatsBuilder {
        private int segments;
        private int available;
        private long ticks;
        private final Set<String> servers = new HashSet<>();
        private @Nullable Instant first;
        private @Nullable Instant last;

        void add(ResultSet rs) throws SQLException {
            int count = rs.getInt("segment_count");
            if (count == 0) {
                return;
            }
            segments += count;
            available += rs.getInt("available_count");
            ticks += rs.getLong("total_ticks");
            String server = rs.getString("server_name");
            if (server != null) {
                servers.add(server);
            }
            Instant firstAt = SqlValues.instant(rs, "first_at");
            Instant lastAt = SqlValues.instant(rs, "last_at");
            if (firstAt != null && (first == null || firstAt.isBefore(first))) {
                first = firstAt;
            }
            if (lastAt != null && (last == null || lastAt.isAfter(last))) {
                last = lastAt;
            }
        }

        SessionStats build() {
            return segments == 0 ? SessionStats.EMPTY
                    : new SessionStats(segments, available, ticks, servers, first, last);
        }
    }

    // ------------------------------------------------------------------ Aufenthalte

    List<VisitRow> visitsOfSession(Connection c, long sessionId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(visitSelect + " WHERE session_id = ? ORDER BY joined_at, id")) {
            ps.setLong(1, sessionId);
            return all(ps, this::visit);
        }
    }

    List<VisitRow> openVisits(Connection c, String serverName) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(visitSelect
                + " WHERE server_name = ? AND left_at IS NULL ORDER BY joined_at, id")) {
            ps.setString(1, serverName);
            return all(ps, this::visit);
        }
    }

    // ------------------------------------------------------------------ Abschnitte und Aufnahmen

    List<SegmentRow> segmentsOfSession(Connection c, long sessionId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(segmentSelect
                + " WHERE session_id = ? AND status <> 'DELETED' ORDER BY started_at, id")) {
            ps.setLong(1, sessionId);
            return all(ps, this::segment);
        }
    }

    Optional<SegmentRow> segmentById(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(segmentSelect + " WHERE id = ?")) {
            ps.setLong(1, id);
            return first(ps, this::segment);
        }
    }

    Optional<SegmentRow> segmentByReplayName(Connection c, String replayName) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(segmentSelect + " WHERE replay_name = ?")) {
            ps.setString(1, replayName);
            return first(ps, this::segment);
        }
    }

    List<RecordingRow> recordingsByStatus(Connection c, String serverName, Set<RecordingStatus> statuses, int limit)
            throws SQLException {
        if (statuses.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<RecordingStatus> list = new ArrayList<>(statuses);
        try (PreparedStatement ps = c.prepareStatement(recordingSelect + " WHERE server_name = ? AND status IN ("
                + SqlValues.placeholders(list.size()) + ") ORDER BY started_at, id LIMIT ?")) {
            int i = 1;
            ps.setString(i++, serverName);
            for (RecordingStatus status : list) {
                ps.setString(i++, status.name());
            }
            ps.setInt(i, clampLimit(limit));
            return all(ps, this::recording);
        }
    }

    List<SegmentRow> segmentsForRetention(Connection c, String serverName, Instant olderThan, int limit)
            throws SQLException {
        if (limit <= 0) {
            return List.of();
        }
        try (PreparedStatement ps = c.prepareStatement(segmentSelect + " WHERE server_name = ? AND status <> 'DELETED'"
                + " AND ended_at < ? ORDER BY ended_at, id LIMIT ?")) {
            ps.setString(1, serverName);
            SqlValues.setInstant(ps, 2, olderThan);
            ps.setInt(3, clampLimit(limit));
            return all(ps, this::segment);
        }
    }

    long countSegmentsOfPlayer(Connection c, UUID player) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + t.segments()
                + " WHERE player_uuid = ? AND status <> 'DELETED'")) {
            SqlValues.setUuid(ps, 1, player);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    List<SegmentRow> oldestSegmentsOfPlayer(Connection c, UUID player, String serverName, int limit)
            throws SQLException {
        if (limit <= 0) {
            return List.of();
        }
        try (PreparedStatement ps = c.prepareStatement(segmentSelect + " WHERE player_uuid = ? AND server_name = ?"
                + " AND status <> 'DELETED' ORDER BY started_at, id LIMIT ?")) {
            SqlValues.setUuid(ps, 1, player);
            ps.setString(2, serverName);
            ps.setInt(3, clampLimit(limit));
            return all(ps, this::segment);
        }
    }

    // ------------------------------------------------------------------ Abbildung

    private SessionRow session(ResultSet rs) throws SQLException {
        return new SessionRow(
                rs.getLong("id"),
                rs.getString("session_key"),
                rs.getString("cloud_session_id"),
                SqlValues.uuid(rs, "player_uuid"),
                rs.getString("last_name"),
                SqlValues.enumValue(SessionSource.class, rs.getString("source"), SessionSource.PROVISIONAL),
                SqlValues.enumValue(SessionStatus.class, rs.getString("status"), SessionStatus.ACTIVE),
                SqlValues.instant(rs, "login_time"),
                SqlValues.instant(rs, "logout_time"),
                SqlValues.nullableLong(rs, "duration_seconds"),
                SqlValues.nullableLong(rs, "merged_into"),
                SqlValues.requiredInstant(rs, "created_at"),
                SqlValues.requiredInstant(rs, "updated_at"));
    }

    private VisitRow visit(ResultSet rs) throws SQLException {
        return new VisitRow(
                rs.getLong("id"),
                rs.getString("visit_key"),
                rs.getLong("session_id"),
                SqlValues.uuid(rs, "player_uuid"),
                rs.getString("server_name"),
                rs.getString("instance_id"),
                SqlValues.requiredInstant(rs, "joined_at"),
                SqlValues.instant(rs, "left_at"),
                SqlValues.nullableEnum(VisitEndReason.class, rs.getString("end_reason")));
    }

    private RecordingRow recording(ResultSet rs) throws SQLException {
        return new RecordingRow(
                rs.getLong("id"),
                rs.getString("raw_name"),
                SqlValues.nullableLong(rs, "session_id"),
                SqlValues.uuid(rs, "player_uuid"),
                rs.getString("player_name"),
                rs.getString("server_name"),
                rs.getString("instance_id"),
                rs.getString("world_name"),
                SqlValues.requiredInstant(rs, "started_at"),
                SqlValues.instant(rs, "ended_at"),
                SqlValues.enumValue(RecordingStatus.class, rs.getString("status"), RecordingStatus.FAILED),
                rs.getInt("relevant_actions"),
                rs.getString("error"));
    }

    private SegmentRow segment(ResultSet rs) throws SQLException {
        return new SegmentRow(
                rs.getLong("id"),
                rs.getString("replay_name"),
                rs.getLong("session_id"),
                SqlValues.nullableLong(rs, "recording_id"),
                SqlValues.uuid(rs, "player_uuid"),
                rs.getString("player_name"),
                rs.getString("server_name"),
                rs.getString("world_name"),
                rs.getString("storage_backend"),
                rs.getString("storage_ref"),
                SqlValues.requiredInstant(rs, "started_at"),
                SqlValues.requiredInstant(rs, "ended_at"),
                SqlValues.requiredInstant(rs, "first_action_at"),
                SqlValues.requiredInstant(rs, "last_action_at"),
                rs.getInt("duration_ticks"),
                rs.getInt("pre_roll_ms"),
                rs.getInt("post_roll_ms"),
                rs.getInt("relevant_actions"),
                summary(rs.getString("action_summary")),
                SqlValues.enumValue(SegmentStatus.class, rs.getString("status"), SegmentStatus.FAILED),
                SqlValues.nullableLong(rs, "size_bytes"),
                rs.getString("note"),
                SqlValues.requiredInstant(rs, "created_at"));
    }

    /** JSON {@code {"BLOCK_PLACE": 12, ...}}; unlesbare Werte ergeben eine leere Zusammenfassung. */
    Map<String, Integer> summary(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Integer> parsed = gson.fromJson(json, SUMMARY_TYPE);
            if (parsed == null) {
                return Map.of();
            }
            Map<String, Integer> clean = new LinkedHashMap<>();
            parsed.forEach((key, value) -> {
                if (key != null && value != null) {
                    clean.put(key, value);
                }
            });
            return clean;
        } catch (JsonParseException | ClassCastException e) {
            return Map.of();
        }
    }

    // ------------------------------------------------------------------ Hilfen

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private static <T> Optional<T> first(PreparedStatement ps, RowMapper<T> mapper) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            return rs.next() ? Optional.of(mapper.map(rs)) : Optional.empty();
        }
    }

    private static <T> List<T> all(PreparedStatement ps, RowMapper<T> mapper) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            List<T> result = new ArrayList<>();
            while (rs.next()) {
                result.add(mapper.map(rs));
            }
            return List.copyOf(result);
        }
    }

    private static int clampLimit(int limit) {
        return Math.max(0, Math.min(MAX_LIMIT, limit));
    }
}
