package net.techniqstone.buildReplay.storage.sql;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Umrechnung zwischen Java- und Spaltenwerten. Zeitpunkte liegen als {@code DATETIME(3)} in UTC in der Datenbank
 * und werden ohne Zeitzonenumrechnung des Treibers über {@link LocalDateTime} übertragen; Millisekunden werden in
 * Java abgeschnitten, damit gelesene Werte exakt den geschriebenen entsprechen.
 */
final class SqlValues {

    private SqlValues() {
    }

    static @Nullable LocalDateTime toDb(@Nullable Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }

    static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    static Instant requiredInstant(ResultSet rs, String column) throws SQLException {
        Instant value = instant(rs, column);
        if (value == null) {
            throw new SQLException("Spalte " + column + " ist unerwartet NULL");
        }
        return value;
    }

    static @Nullable Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    static void setInstant(PreparedStatement ps, int index, @Nullable Instant instant) throws SQLException {
        if (instant == null) {
            ps.setNull(index, Types.TIMESTAMP);
        } else {
            ps.setObject(index, toDb(instant));
        }
    }

    static void setLong(PreparedStatement ps, int index, @Nullable Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    static void setString(PreparedStatement ps, int index, @Nullable String value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    static void setUuid(PreparedStatement ps, int index, UUID uuid) throws SQLException {
        ps.setString(index, uuid.toString());
    }

    static UUID uuid(ResultSet rs, String column) throws SQLException {
        return UUID.fromString(rs.getString(column));
    }

    /** Kürzt Freitext auf die Spaltenlänge (Fehlertexte, Notizen, Namen). Nie für natürliche Schlüssel verwenden. */
    static @Nullable String clip(@Nullable String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    static String clipRequired(String value, int maxLength) {
        String clipped = clip(value, maxLength);
        return clipped == null ? "" : clipped;
    }

    /** Liest einen Enum-Wert tolerant (unbekannte Werte einer neueren Plugin-Version ergeben {@code fallback}). */
    static <E extends Enum<E>> E enumValue(Class<E> type, @Nullable String value, E fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    static <E extends Enum<E>> @Nullable E nullableEnum(Class<E> type, @Nullable String value) {
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** {@code ?, ?, ?} für IN-Listen. */
    static String placeholders(int count) {
        StringBuilder sb = new StringBuilder(count * 3);
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('?');
        }
        return sb.toString();
    }
}
