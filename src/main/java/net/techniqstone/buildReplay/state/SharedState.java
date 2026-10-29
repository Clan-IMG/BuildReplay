package net.techniqstone.buildReplay.state;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Netzwerkweiter, kurzlebiger Zustand und Koordination zwischen BuildReplay-Instanzen (Redis).
 *
 * <p>Redis hält nur Zustand, der verloren gehen darf: dauerhafte Daten liegen in MariaDB, die Sitzungsidentität
 * kommt von SimpleCloud. Ist Redis nicht erreichbar, fällt die Implementierung transparent auf einen lokalen
 * In-Memory-Zustand zurück ({@link SharedStateHealth#usingLocalFallback()}): Sperren und Leader gelten dann nur
 * lokal, Korrektheit sichern weiterhin die eindeutigen Schlüssel in MariaDB. Keine Methode wirft bei
 * Verbindungsfehlern; alle Methoden sind threadsicher und blockieren höchstens bis zum Redis-Timeout - daher nie
 * auf dem Hauptthread aufrufen.
 */
public interface SharedState extends AutoCloseable {

    /** {@code true}, solange Redis verbunden ist (Zustand wird netzwerkweit geteilt). */
    boolean isDistributed();

    SharedStateHealth health();

    // ---- Sitzungscache
    Optional<CachedSession> cachedSession(UUID player);

    void cacheSession(UUID player, CachedSession session, Duration ttl);

    void evictSession(UUID player);

    // ---- Präsenz (Serverwechsel-Erkennung)
    void markPresent(UUID player, PresenceInfo presence, Duration ttl);

    /** Spieler hat diesen Server verlassen; Eintrag bleibt {@code grace} lang mit online=false sichtbar. */
    void markLeft(UUID player, PresenceInfo presence, Duration grace);

    Optional<PresenceInfo> presence(UUID player);

    // ---- Aufnahmestatus
    void putRecordingState(UUID player, RecordingState state, Duration ttl);

    /** Entfernt den Status nur, wenn er noch zu {@code rawName} gehört (fremde Aufnahmen bleiben unberührt). */
    void clearRecordingState(UUID player, String rawName);

    Optional<RecordingState> recordingState(UUID player);

    // ---- Kurzlebige Aktivitätsinfos
    void putActivity(UUID player, ActivityStamp stamp, Duration ttl);

    Optional<ActivityStamp> activity(UUID player);

    // ---- Verteilte Sperren (SET NX PX, Freigabe per Compare-and-Delete)
    Optional<LockToken> tryLock(String name, Duration lease);

    boolean unlock(LockToken token);

    // ---- Leader-Wahl für netzwerkweite Aufgaben (Online-Abgleich, Sitzungsabgleich)
    /** Erwirbt bzw. verlängert die Leader-Rolle; {@code true}, wenn diese Instanz danach Leader ist. */
    boolean tryAcquireOrRenewLeadership(String role, String instanceId, Duration lease);

    void releaseLeadership(String role, String instanceId);

    // ---- Instanz-Herzschlag
    void heartbeat(InstanceInfo info, Duration ttl);

    List<InstanceInfo> liveInstances();

    // ---- Wiedergabe-Übergabe an einen anderen Server
    void putHandoff(UUID player, HandoffRequest request, Duration ttl);

    /** Holt und löscht den Auftrag atomar (höchstens ein Server führt ihn aus). */
    Optional<HandoffRequest> takeHandoff(UUID player);

    // ---- Pub/Sub
    void publish(SharedEvent event);

    /** Empfänger werden auf einem Hintergrundthread aufgerufen, eigene Events inklusive. */
    void subscribe(Consumer<SharedEvent> listener);

    @Override
    void close();
}
