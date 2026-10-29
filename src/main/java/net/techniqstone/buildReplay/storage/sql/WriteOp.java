package net.techniqstone.buildReplay.storage.sql;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import net.techniqstone.buildReplay.storage.model.RecordingStatus;
import net.techniqstone.buildReplay.storage.model.RecordingUpsert;
import net.techniqstone.buildReplay.storage.model.SegmentStatus;
import net.techniqstone.buildReplay.storage.model.SegmentUpsert;
import net.techniqstone.buildReplay.storage.model.SessionUpsert;
import net.techniqstone.buildReplay.storage.model.VisitEndReason;
import net.techniqstone.buildReplay.storage.model.VisitStart;
import org.jspecify.annotations.Nullable;

/**
 * Ein eingereihter Schreibvorgang. Jeder Typ trägt ein stabiles Kürzel ({@link #type()}), unter dem er in der
 * Outbox-Datei gespeichert wird - Kürzel nie umbenennen, sonst sind gesicherte Einträge nicht mehr lesbar.
 */
sealed interface WriteOp {

    /** Maximale Länge der natürlichen Schlüssel (Spaltenbreite); längere würden still abgeschnitten. */
    int MAX_SESSION_KEY = 64;
    int MAX_VISIT_KEY = 128;
    int MAX_REPLAY_NAME = 40;

    String type();

    /** Kurzbeschreibung für Protokolle (keine Geheimnisse, keine vollständigen Nutzdaten). */
    String describe();

    /** Prüft Pflichtfelder (auch nach dem Laden aus der Outbox) und liefert {@code null} oder den Grund. */
    @Nullable String invalidReason();

    record UpsertPlayer(UUID playerUuid, String name, Instant seenAt) implements WriteOp {
        @Override
        public String type() {
            return "player";
        }

        @Override
        public String describe() {
            return "Spieler " + playerUuid + " aktualisieren";
        }

        @Override
        public @Nullable String invalidReason() {
            return missing(playerUuid, name, seenAt);
        }
    }

    record UpsertSession(SessionUpsert session) implements WriteOp {
        @Override
        public String type() {
            return "session";
        }

        @Override
        public String describe() {
            return "Sitzung " + (session == null ? "?" : session.sessionKey()) + " anlegen/aktualisieren";
        }

        @Override
        public @Nullable String invalidReason() {
            return sessionProblem(session);
        }
    }

    record CloseSession(String sessionKey, Instant logoutTime, @Nullable Long durationSeconds) implements WriteOp {
        @Override
        public String type() {
            return "session-close";
        }

        @Override
        public String describe() {
            return "Sitzung " + sessionKey + " schließen";
        }

        @Override
        public @Nullable String invalidReason() {
            String missing = missing(sessionKey, logoutTime);
            return missing != null ? missing : keyLength("sessionKey", sessionKey, MAX_SESSION_KEY);
        }
    }

    record MergeProvisional(String provisionalKey, SessionUpsert target) implements WriteOp {
        @Override
        public String type() {
            return "session-merge";
        }

        @Override
        public String describe() {
            return "Vorläufige Sitzung " + provisionalKey + " in " + (target == null ? "?" : target.sessionKey())
                    + " zusammenführen";
        }

        @Override
        public @Nullable String invalidReason() {
            String missing = missing(provisionalKey);
            if (missing != null) {
                return missing;
            }
            String length = keyLength("provisionalKey", provisionalKey, MAX_SESSION_KEY);
            return length != null ? length : sessionProblem(target);
        }
    }

    record StartVisit(VisitStart visit) implements WriteOp {
        @Override
        public String type() {
            return "visit-start";
        }

        @Override
        public String describe() {
            return "Aufenthalt " + (visit == null ? "?" : visit.visitKey()) + " beginnen";
        }

        @Override
        public @Nullable String invalidReason() {
            if (visit == null) {
                return "visit fehlt";
            }
            String missing = missing(visit.visitKey(), visit.sessionKey(), visit.playerUuid(), visit.playerName(),
                    visit.serverName(), visit.instanceId(), visit.joinedAt());
            if (missing != null) {
                return missing;
            }
            String length = keyLength("visitKey", visit.visitKey(), MAX_VISIT_KEY);
            return length != null ? length : keyLength("sessionKey", visit.sessionKey(), MAX_SESSION_KEY);
        }
    }

    record EndVisit(String visitKey, Instant leftAt, VisitEndReason reason) implements WriteOp {
        @Override
        public String type() {
            return "visit-end";
        }

        @Override
        public String describe() {
            return "Aufenthalt " + visitKey + " beenden";
        }

        @Override
        public @Nullable String invalidReason() {
            return missing(visitKey, leftAt, reason);
        }
    }

    record UpsertRecording(RecordingUpsert recording) implements WriteOp {
        @Override
        public String type() {
            return "recording";
        }

        @Override
        public String describe() {
            return "Aufnahme " + (recording == null ? "?" : recording.rawName()) + " anlegen";
        }

        @Override
        public @Nullable String invalidReason() {
            if (recording == null) {
                return "recording fehlt";
            }
            String missing = missing(recording.rawName(), recording.playerUuid(), recording.playerName(),
                    recording.serverName(), recording.instanceId(), recording.startedAt(), recording.status());
            if (missing != null) {
                return missing;
            }
            String length = keyLength("rawName", recording.rawName(), MAX_REPLAY_NAME);
            if (length != null || recording.sessionKey() == null) {
                return length;
            }
            return keyLength("sessionKey", recording.sessionKey(), MAX_SESSION_KEY);
        }
    }

    record FinishRecording(String rawName, RecordingStatus status, @Nullable Instant endedAt, int relevantActions,
                           @Nullable String error) implements WriteOp {
        @Override
        public String type() {
            return "recording-finish";
        }

        @Override
        public String describe() {
            return "Aufnahme " + rawName + " abschließen (" + status + ")";
        }

        @Override
        public @Nullable String invalidReason() {
            return missing(rawName, status);
        }
    }

    record UpsertSegment(SegmentUpsert segment) implements WriteOp {
        @Override
        public String type() {
            return "segment";
        }

        @Override
        public String describe() {
            return "Abschnitt " + (segment == null ? "?" : segment.replayName()) + " anlegen/aktualisieren";
        }

        @Override
        public @Nullable String invalidReason() {
            if (segment == null) {
                return "segment fehlt";
            }
            String missing = missing(segment.replayName(), segment.sessionKey(), segment.playerUuid(),
                    segment.playerName(), segment.serverName(), segment.storageBackend(), segment.startedAt(),
                    segment.endedAt(), segment.firstActionAt(), segment.lastActionAt(), segment.actionSummary(),
                    segment.status());
            if (missing != null) {
                return missing;
            }
            String length = keyLength("replayName", segment.replayName(), MAX_REPLAY_NAME);
            return length != null ? length : keyLength("sessionKey", segment.sessionKey(), MAX_SESSION_KEY);
        }
    }

    record UpdateSegmentStatus(String replayName, SegmentStatus status, @Nullable String note) implements WriteOp {
        @Override
        public String type() {
            return "segment-status";
        }

        @Override
        public String describe() {
            return "Abschnitt " + replayName + " auf " + status + " setzen";
        }

        @Override
        public @Nullable String invalidReason() {
            return missing(replayName, status);
        }
    }

    private static @Nullable String sessionProblem(@Nullable SessionUpsert session) {
        if (session == null) {
            return "session fehlt";
        }
        String missing = missing(session.sessionKey(), session.playerUuid(), session.source());
        return missing != null ? missing : keyLength("sessionKey", session.sessionKey(), MAX_SESSION_KEY);
    }

    private static @Nullable String missing(@Nullable Object... values) {
        for (int i = 0; i < values.length; i++) {
            if (Objects.isNull(values[i])) {
                return "Pflichtfeld Nr. " + (i + 1) + " fehlt";
            }
        }
        return null;
    }

    private static @Nullable String keyLength(String name, String key, int max) {
        return key.length() > max ? name + " länger als " + max + " Zeichen: " + key : null;
    }
}
