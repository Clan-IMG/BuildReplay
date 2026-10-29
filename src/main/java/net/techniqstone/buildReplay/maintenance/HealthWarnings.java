package net.techniqstone.buildReplay.maintenance;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import net.techniqstone.buildReplay.config.PluginConfig;
import net.techniqstone.buildReplay.recording.EngineStatus;
import net.techniqstone.buildReplay.recording.RecordingStats;
import net.techniqstone.buildReplay.session.SessionServiceStats;
import net.techniqstone.buildReplay.simplecloud.ClientHealth;
import net.techniqstone.buildReplay.state.SharedStateHealth;
import net.techniqstone.buildReplay.storage.StoreHealth;
import org.jspecify.annotations.Nullable;

/** Leitet aus den Komponentenzuständen verständliche Warnungen für {@code /buildreplay status} ab (rein, testbar). */
public final class HealthWarnings {

    /** Ab diesem Füllstand der Outbox wird gewarnt. */
    static final double OUTBOX_WARN_RATIO = 0.5;
    /** Ab diesem Füllstand wird dringend gewarnt (bei voller Outbox gehen Schreibvorgänge verloren). */
    static final double OUTBOX_CRITICAL_RATIO = 0.8;
    /** Ab so vielen ausstehenden Zerlegungen wird gewarnt. */
    static final int PENDING_FINALIZATIONS_WARN = 10;

    private static final Pattern UNAUTHORIZED =
            Pattern.compile("(?i)(\\b401\\b|\\b403\\b|unauthori[sz]ed|forbidden|credential)");

    private HealthWarnings() {
    }

    /**
     * @param ioMeasured ob die I/O-seitigen Zustände (SimpleCloud, MariaDB, Redis) schon einmal abgefragt wurden;
     *                   sonst werden für sie keine Warnungen abgeleitet
     */
    public static List<String> evaluate(PluginConfig config, ClientHealth cloud, boolean javaApiAvailable,
                                        StoreHealth database, SharedStateHealth redis, EngineStatus engine,
                                        RecordingStats recordings, SessionServiceStats sessions,
                                        boolean ioMeasured) {
        List<String> warnings = new ArrayList<>();
        if (!config.recording().enabled()) {
            warnings.add("Aufnahme ist deaktiviert (recording.enabled: false).");
        }
        engineWarnings(config, engine, warnings);
        if (ioMeasured) {
            databaseWarnings(config, database, warnings);
            redisWarnings(config, redis, warnings);
            simpleCloudWarnings(config, cloud, javaApiAvailable, warnings);
        } else {
            warnings.add("Zustand von MariaDB, Redis und SimpleCloud wird noch ermittelt.");
        }
        if (sessions.provisionalSessions() > 0) {
            warnings.add(sessions.provisionalSessions() + " vorläufige Sitzung(en) warten auf den Abgleich mit "
                    + "SimpleCloud.");
        }
        if (recordings.failures() > 0) {
            warnings.add(recordings.failures() + " Aufnahmefehler seit dem Start (Details in der Serverkonsole).");
        }
        if (recordings.pendingFinalizations() >= PENDING_FINALIZATIONS_WARN) {
            warnings.add(recordings.pendingFinalizations() + " Aufnahmen warten auf das Zerlegen in Abschnitte.");
        }
        return List.copyOf(warnings);
    }

    private static void engineWarnings(PluginConfig config, EngineStatus engine, List<String> warnings) {
        if (!engine.installed()) {
            warnings.add("AdvancedReplay ist nicht installiert - keine Aufnahme und keine Wiedergabe möglich.");
            return;
        }
        if (!engine.available()) {
            warnings.add("AdvancedReplay ist nicht verfügbar - Aufnahmen sind pausiert.");
            return;
        }
        if (!engine.playbackCompatible()) {
            String reason = engine.incompatibilityReason();
            warnings.add("Wiedergabe mit AdvancedReplay " + engine.advancedReplayVersion() + " nicht kompatibel"
                    + (reason == null || reason.isBlank() ? "" : ": " + reason)
                    + " - Aufnahmen laufen weiter, Abspielen ist gesperrt.");
        }
        if (!engine.clientSideBlockChanges() && !config.playback().forceClientSideBlockChanges()) {
            warnings.add("AdvancedReplay 'real_changes' ist aktiv und playback.force-client-side-block-changes aus - "
                    + "Wiedergaben verändern die echte Welt!");
        }
        long rotationSeconds = config.recording().rotationInterval().toSeconds();
        if (engine.maxLengthSeconds() > 0 && engine.maxLengthSeconds() <= rotationSeconds) {
            warnings.add("AdvancedReplay max_length (" + engine.maxLengthSeconds() + " s) ist nicht größer als "
                    + "recording.rotation-minutes (" + rotationSeconds / 60 + " min) - AdvancedReplay beendet "
                    + "Aufnahmen vorzeitig.");
        }
    }

    private static void databaseWarnings(PluginConfig config, StoreHealth db, List<String> warnings) {
        if (!db.enabled()) {
            warnings.add("MariaDB ist deaktiviert - Sitzungen und Abschnitte werden nicht dauerhaft gespeichert.");
            return;
        }
        if (!db.connected()) {
            warnings.add("MariaDB nicht erreichbar" + suffix(db.lastError())
                    + " - Schreibvorgänge werden in der Outbox gesichert.");
        }
        int max = Math.max(1, config.resilience().outboxMaxEntries());
        long pending = (long) db.queuedWrites() + db.persistedOutboxEntries();
        double ratio = pending / (double) max;
        if (ratio >= OUTBOX_CRITICAL_RATIO) {
            warnings.add("Outbox zu " + percent(ratio) + " gefüllt (" + pending + "/" + max + ") - bei voller "
                    + "Outbox gehen Schreibvorgänge verloren.");
        } else if (ratio >= OUTBOX_WARN_RATIO) {
            warnings.add("Outbox füllt sich: " + percent(ratio) + " (" + pending + "/" + max + ").");
        }
        if (db.droppedWrites() > 0) {
            warnings.add(db.droppedWrites() + " Schreibvorgänge wurden verworfen (Outbox voll).");
        }
    }

    private static void redisWarnings(PluginConfig config, SharedStateHealth redis, List<String> warnings) {
        if (!redis.enabled()) {
            if (config.playback().crossServer()) {
                warnings.add("Redis ist deaktiviert - Serverwechsel-Erkennung und serverübergreifende Wiedergabe "
                        + "funktionieren nur eingeschränkt.");
            }
            return;
        }
        if (redis.usingLocalFallback() || !redis.connected()) {
            warnings.add("Redis nicht erreichbar" + suffix(redis.lastError()) + " - lokaler Ersatzzustand aktiv "
                    + "(Sperren und Leader gelten nur auf dieser Instanz).");
        }
    }

    private static void simpleCloudWarnings(PluginConfig config, ClientHealth cloud, boolean javaApiAvailable,
                                            List<String> warnings) {
        if (!cloud.configured()) {
            warnings.add("SimpleCloud ist nicht konfiguriert (simplecloud.network-id/network-secret) - Sitzungen "
                    + "werden nur vorläufig erfasst.");
        } else if (unauthorized(cloud)) {
            warnings.add("SimpleCloud lehnt die Anmeldung ab (401/403) - simplecloud.network-id und "
                    + "network-secret prüfen.");
        } else if (cloud.circuitOpen()) {
            warnings.add("SimpleCloud nicht erreichbar (Circuit-Breaker offen)" + suffix(cloud.lastError())
                    + " - neue Sitzungen werden vorläufig erfasst.");
        } else if (failing(cloud.lastSuccess(), cloud.lastFailure())) {
            warnings.add("Letzte SimpleCloud-Anfrage fehlgeschlagen" + suffix(cloud.lastError()) + ".");
        }
        if (config.simpleCloud().useJavaApi() && config.playback().crossServer() && !javaApiAvailable) {
            warnings.add("SimpleCloud-Java-API nicht verfügbar - für Wiedergaben anderer Server wird der Spieler "
                    + "nicht automatisch verbunden.");
        }
    }

    /** Ob der letzte (noch aktuelle) SimpleCloud-Fehler eine abgelehnte Anmeldung war. */
    public static boolean unauthorized(ClientHealth cloud) {
        String error = cloud.lastError();
        return error != null && failing(cloud.lastSuccess(), cloud.lastFailure())
                && UNAUTHORIZED.matcher(error).find();
    }

    /** Letzter Fehlschlag jünger als der letzte Erfolg (oder nie ein Erfolg). */
    public static boolean failing(@Nullable Instant lastSuccess, @Nullable Instant lastFailure) {
        return lastFailure != null && (lastSuccess == null || lastFailure.isAfter(lastSuccess));
    }

    private static String suffix(@Nullable String error) {
        if (error == null || error.isBlank()) {
            return "";
        }
        String trimmed = error.strip();
        return " (" + (trimmed.length() > 120 ? trimmed.substring(0, 120) + "…" : trimmed) + ")";
    }

    private static String percent(double ratio) {
        return String.format(Locale.GERMANY, "%.0f %%", Math.min(100.0, ratio * 100.0));
    }
}
