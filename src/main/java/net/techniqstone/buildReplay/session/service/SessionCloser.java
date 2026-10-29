package net.techniqstone.buildReplay.session.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.techniqstone.buildReplay.server.ServerIdentity;
import net.techniqstone.buildReplay.session.NetworkSession;
import net.techniqstone.buildReplay.simplecloud.CloudSession;
import net.techniqstone.buildReplay.state.CachedSession;
import net.techniqstone.buildReplay.state.SharedEvent;
import net.techniqstone.buildReplay.state.SharedState;
import net.techniqstone.buildReplay.storage.ReplayStore;
import net.techniqstone.buildReplay.storage.model.SessionSource;
import org.jspecify.annotations.Nullable;

/**
 * Schließt Netzwerksitzungen: Logout und Dauer in MariaDB, {@link SharedEvent#SESSION_CLOSED} an die anderen
 * Instanzen, Sitzungscache leeren. Wiederholte Aufrufe (doppelte Events, Abgleich) sind unschädlich; das Event
 * wird pro Sitzung nur einmal veröffentlicht.
 *
 * <p>Alle Methoden außer {@link #closeLatest} blockieren (Redis) und laufen nur auf dem I/O-Pool.
 */
final class SessionCloser {

    /** Toleranz beim Vergleich von Login-/Logout-Zeitpunkten verschiedener Quellen. */
    static final Duration TIME_SLACK = Duration.ofSeconds(10);
    private static final int PUBLISHED_MEMORY = 2_048;

    enum Outcome { CLOSED, STILL_ACTIVE, NO_SESSION }

    record Result(Outcome outcome, @Nullable CloudSession latest) {
    }

    private final ServerIdentity identity;
    private final CloudCalls cloud;
    private final ReplayStore store;
    private final SharedState shared;
    private final Logger logger;
    private final java.util.concurrent.Executor io;
    private final Set<String> published = Collections.newSetFromMap(Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > PUBLISHED_MEMORY;
                }
            }));

    SessionCloser(ServerIdentity identity, CloudCalls cloud, ReplayStore store, SharedState shared, Logger logger,
                  java.util.concurrent.Executor io) {
        this.identity = identity;
        this.cloud = cloud;
        this.store = store;
        this.shared = shared;
        this.logger = logger;
        this.io = io;
    }

    /**
     * Holt die neueste SimpleCloud-Sitzung des Spielers und schließt sie, wenn sie beendet ist. Schlägt fehl, wenn
     * SimpleCloud nicht antwortet.
     */
    CompletableFuture<Result> closeLatest(UUID player) {
        return cloud.listSessions(player, 1, 0).thenApplyAsync(page -> {
            if (page.sessions().isEmpty()) {
                return new Result(Outcome.NO_SESSION, null);
            }
            CloudSession latest = page.sessions().getFirst();
            if (latest.active()) {
                return new Result(Outcome.STILL_ACTIVE, latest);
            }
            closeKnown(player, latest.id(), latest.logoutTime(), durationOf(latest));
            return new Result(Outcome.CLOSED, latest);
        }, io);
    }

    /** Schließt eine Sitzung mit bekanntem Logout (idempotent). */
    void closeKnown(UUID player, String sessionKey, @Nullable Instant logoutTime, @Nullable Long durationSeconds) {
        if (logoutTime == null) {
            return;
        }
        try {
            store.closeSession(sessionKey, logoutTime, durationSeconds);
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Sitzung " + NetworkSession.shortRef(sessionKey)
                    + " konnte nicht als beendet gespeichert werden", e);
        }
        if (published.add(sessionKey)) {
            Map<String, String> data = new LinkedHashMap<>();
            data.put("player", player.toString());
            data.put("sessionKey", sessionKey);
            data.put("logoutTime", logoutTime.toString());
            if (durationSeconds != null) {
                data.put("durationSeconds", Long.toString(durationSeconds));
            }
            try {
                shared.publish(new SharedEvent(SharedEvent.SESSION_CLOSED, identity.instanceId(), data));
            } catch (RuntimeException e) {
                logger.log(Level.FINE, "SESSION_CLOSED konnte nicht veröffentlicht werden", e);
            }
            logger.fine("Netzwerksitzung " + NetworkSession.shortRef(sessionKey) + " von " + player
                    + " beendet (Logout " + logoutTime + ")");
        }
        evictIfMatches(player, sessionKey);
    }

    /**
     * Rückfall ohne SimpleCloud: schließt die zwischengespeicherte Sitzung mit dem Zeitpunkt aus dem
     * Disconnect-Event, sofern sie plausibel dazu passt.
     */
    boolean closeFromCache(UUID player, Instant logoutTime, long eventDurationSeconds) {
        Optional<CachedSession> cached = cachedSession(player);
        if (cached.isEmpty()) {
            return false;
        }
        CachedSession session = cached.get();
        Instant login = session.loginTime();
        if (login != null && login.isAfter(logoutTime.plus(TIME_SLACK))) {
            // neuere Sitzung im Cache - das Event gehört zu einer älteren Verbindung
            return false;
        }
        Long duration;
        if (session.source() == SessionSource.SIMPLECLOUD && eventDurationSeconds >= 0) {
            if (login != null && Duration.between(login.plusSeconds(eventDurationSeconds), logoutTime).abs()
                    .compareTo(Duration.ofMinutes(1)) > 0) {
                logger.fine("Disconnect-Event von " + player + " passt nicht zur zwischengespeicherten Sitzung "
                        + NetworkSession.shortRef(session.sessionKey()) + " - wird dem Abgleich überlassen");
                return false;
            }
            duration = eventDurationSeconds;
        } else {
            duration = login != null ? Math.max(0L, Duration.between(login, logoutTime).toSeconds()) : null;
        }
        closeKnown(player, session.sessionKey(), logoutTime, duration);
        return true;
    }

    /** Leert den Sitzungscache, wenn er noch auf {@code sessionKeyOrCloudId} zeigt. */
    void evictIfMatches(UUID player, @Nullable String sessionKeyOrCloudId) {
        if (sessionKeyOrCloudId == null) {
            return;
        }
        Optional<CachedSession> cached = cachedSession(player);
        if (cached.isPresent() && (sessionKeyOrCloudId.equals(cached.get().sessionKey())
                || sessionKeyOrCloudId.equals(cached.get().cloudSessionId()))) {
            try {
                shared.evictSession(player);
            } catch (RuntimeException e) {
                logger.log(Level.FINE, "Sitzungscache von " + player + " konnte nicht geleert werden", e);
            }
        }
    }

    static @Nullable Long durationOf(CloudSession session) {
        if (session.durationSeconds() != null) {
            return session.durationSeconds();
        }
        Instant logout = session.logoutTime();
        return logout == null ? null : Math.max(0L, logout.getEpochSecond() - session.loginTime().getEpochSecond());
    }

    private Optional<CachedSession> cachedSession(UUID player) {
        try {
            return shared.cachedSession(player);
        } catch (RuntimeException e) {
            logger.log(Level.FINE, "Sitzungscache von " + player + " nicht lesbar", e);
            return Optional.empty();
        }
    }
}
