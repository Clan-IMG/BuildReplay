package net.techniqstone.buildReplay.storage;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.techniqstone.buildReplay.storage.model.RecordingRow;
import net.techniqstone.buildReplay.storage.model.RecordingStatus;
import net.techniqstone.buildReplay.storage.model.RecordingUpsert;
import net.techniqstone.buildReplay.storage.model.SegmentRow;
import net.techniqstone.buildReplay.storage.model.SegmentStatus;
import net.techniqstone.buildReplay.storage.model.SegmentUpsert;
import net.techniqstone.buildReplay.storage.model.SessionRow;
import net.techniqstone.buildReplay.storage.model.SessionStats;
import net.techniqstone.buildReplay.storage.model.SessionUpsert;
import net.techniqstone.buildReplay.storage.model.VisitEndReason;
import net.techniqstone.buildReplay.storage.model.VisitRow;
import net.techniqstone.buildReplay.storage.model.VisitStart;
import org.jspecify.annotations.Nullable;

/**
 * Dauerhafte Ablage in MariaDB.
 *
 * <p><b>Schreiben</b>: alle Schreibmethoden kehren sofort zurück. Die Vorgänge werden in Aufrufreihenfolge von
 * genau einem Schreib-Thread ausgeführt, sind über natürliche Schlüssel idempotent (sessionKey, visitKey,
 * rawName, replayName) und landen bei einem DB-Ausfall in einer begrenzten, auf Platte gesicherten Outbox, die
 * nach der Wiederverbindung abgearbeitet wird. Dadurch erzeugen Wiederholungen, Serverwechsel und Neustarts keine
 * Duplikate. Fremdschlüssel werden zur Ausführungszeit über die natürlichen Schlüssel aufgelöst; fehlt die
 * referenzierte Sitzung, wird eine vorläufige angelegt.
 *
 * <p><b>Lesen</b>: blockierend, nie auf dem Hauptthread. Ist MariaDB deaktiviert oder nicht erreichbar, wird
 * {@link StorageUnavailableException} geworfen.
 */
public interface ReplayStore extends AutoCloseable {

    boolean isEnabled();

    StoreHealth health();

    // ------------------------------------------------------------------ Schreiben (asynchron, idempotent)

    void upsertPlayer(UUID playerUuid, String name, Instant seenAt);

    void upsertSession(SessionUpsert session);

    /**
     * Setzt Logout-Zeit und Dauer und den Status CLOSED (überschreibt eine bekannte Logout-Zeit nicht mit einer
     * früheren). Für einen unbekannten Schlüssel passiert nichts: ohne Spieler lässt sich keine Sitzung anlegen.
     */
    void closeSession(String sessionKey, Instant logoutTime, @Nullable Long durationSeconds);

    /**
     * Führt eine vorläufige Sitzung in eine SimpleCloud-Sitzung über: legt {@code target} an bzw. aktualisiert sie,
     * hängt Aufenthalte, Aufnahmen und Abschnitte um und markiert die vorläufige Sitzung als MERGED (eine
     * Transaktion).
     */
    void mergeProvisionalSession(String provisionalKey, SessionUpsert target);

    void startVisit(VisitStart visit);

    void endVisit(String visitKey, Instant leftAt, VisitEndReason reason);

    /**
     * Legt eine Aufnahme an bzw. aktualisiert sie: ein erneuter Aufruf ergänzt einen bisher fehlenden
     * Sitzungsschlüssel und übernimmt den Status, außer die Aufnahme ist bereits abgeschlossen (FINALIZED,
     * DISCARDED, INTERRUPTED, FAILED).
     */
    void upsertRecording(RecordingUpsert recording);

    /**
     * Setzt den Status einer Aufnahme - auch den Zwischenstatus FINALIZING während der Zerlegung. Für einen
     * unbekannten Rohnamen passiert nichts.
     */
    void finishRecording(String rawName, RecordingStatus status, @Nullable Instant endedAt, int relevantActions,
                         @Nullable String error);

    void upsertSegment(SegmentUpsert segment);

    void updateSegmentStatus(String replayName, SegmentStatus status, @Nullable String note);

    /** Schließt ab, sobald alle bisher eingereihten Schreibvorgänge ausgeführt oder in der Outbox gesichert sind. */
    CompletableFuture<Void> flush();

    // ------------------------------------------------------------------ Lesen (blockierend)

    /**
     * Sitzung zum {@code session_key} oder - falls keiner passt - zur SimpleCloud-Session-ID. Eine zusammengeführte
     * Sitzung wird so geliefert, wie sie gespeichert ist (Status MERGED, {@code mergedInto} gesetzt).
     */
    Optional<SessionRow> findSessionByKey(String sessionKey);

    Optional<SessionRow> findSessionById(long sessionId);

    /**
     * Sitzungen eines Spielers zu einer Kurzreferenz: Präfix der SimpleCloud-Session-ID (z. B. "59dc83e5") oder
     * {@code "p" + Präfix} für vorläufige Sitzungen ({@code session_key} {@code "prov-..."}). MERGED wird ignoriert.
     */
    List<SessionRow> findSessionsByRef(UUID playerUuid, String ref, int limit);

    /**
     * Sitzungen eines Spielers, deren Login (ersatzweise Anlagezeit) nicht vor {@code since} liegt, neueste zuerst
     * (ohne MERGED). {@link #countSessionsOfPlayer} zählt dieselbe Menge.
     */
    List<SessionRow> sessionsOfPlayer(UUID playerUuid, Instant since, int limit, int offset);

    int countSessionsOfPlayer(UUID playerUuid, Instant since);

    /**
     * Abschnittsstatistik je SimpleCloud-Session-ID (fehlende Sitzungen fehlen in der Map, bekannte ohne Abschnitte
     * liefern {@link SessionStats#EMPTY}).
     */
    Map<String, SessionStats> statsByCloudSessionIds(Collection<String> cloudSessionIds);

    Map<Long, SessionStats> statsBySessionIds(Collection<Long> sessionIds);

    /** Sitzungen mit Status ACTIVE (für den Abgleich mit SimpleCloud), älteste Aktualisierung zuerst. */
    List<SessionRow> activeSessions(int limit);

    /** Vorläufige, noch nicht zusammengeführte Sitzungen. */
    List<SessionRow> provisionalSessions(int limit);

    List<VisitRow> visitsOfSession(long sessionId);

    /** Offene Aufenthalte (ohne left_at) auf einem Server - für die Wiederherstellung nach einem Absturz. */
    List<VisitRow> openVisits(String serverName);

    /** Abschnitte einer Sitzung in zeitlicher Reihenfolge (ohne DELETED). */
    List<SegmentRow> segmentsOfSession(long sessionId);

    Optional<SegmentRow> findSegment(long segmentId);

    Optional<SegmentRow> findSegmentByReplayName(String replayName);

    List<RecordingRow> recordingsByStatus(String serverName, Set<RecordingStatus> statuses, int limit);

    /** Abschnitte dieses Servers, die vor {@code olderThan} endeten und noch nicht gelöscht sind. */
    List<SegmentRow> segmentsForRetention(String serverName, Instant olderThan, int limit);

    /** Anzahl nicht gelöschter Abschnitte eines Spielers. */
    long countSegmentsOfPlayer(UUID playerUuid);

    /** Älteste nicht gelöschte Abschnitte eines Spielers auf diesem Server (für das Pro-Spieler-Limit). */
    List<SegmentRow> oldestSegmentsOfPlayer(UUID playerUuid, String serverName, int limit);

    @Override
    void close();
}
