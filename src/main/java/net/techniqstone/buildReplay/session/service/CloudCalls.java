package net.techniqstone.buildReplay.session.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Logger;
import net.techniqstone.buildReplay.simplecloud.CloudOnlinePlayer;
import net.techniqstone.buildReplay.simplecloud.CloudSession;
import net.techniqstone.buildReplay.simplecloud.CloudSessionPage;
import net.techniqstone.buildReplay.simplecloud.SimpleCloudClient;
import net.techniqstone.buildReplay.simplecloud.SimpleCloudException;

/**
 * Zählt die SimpleCloud-Aufrufe der Sitzungsverwaltung und protokolliert Ausfälle ohne Log-Flut: der erste Fehler
 * als Warnung, weitere nur mit FINE, die Erholung wieder als Info.
 */
final class CloudCalls {

    private final SimpleCloudClient cloud;
    private final Logger logger;
    private final AtomicLong lookups = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicBoolean degraded = new AtomicBoolean();

    CloudCalls(SimpleCloudClient cloud, Logger logger) {
        this.cloud = cloud;
        this.logger = logger;
    }

    boolean configured() {
        return cloud.isConfigured();
    }

    /** Ob der Circuit-Breaker des Clients gerade offen ist (dann wird gar nicht erst gefragt). */
    boolean circuitOpen() {
        try {
            return cloud.health().circuitOpen();
        } catch (RuntimeException e) {
            return false;
        }
    }

    CompletableFuture<Optional<CloudSession>> currentSession(UUID player) {
        return track(() -> cloud.currentSession(player));
    }

    CompletableFuture<CloudSessionPage> listSessions(UUID player, int limit, int offset) {
        return track(() -> cloud.listSessions(player, limit, offset));
    }

    CompletableFuture<List<CloudOnlinePlayer>> onlinePlayers() {
        return track(cloud::onlinePlayers);
    }

    long lookups() {
        return lookups.get();
    }

    long failures() {
        return failures.get();
    }

    /** Ob ein Fehler bedeutet, dass SimpleCloud gerade insgesamt nicht nutzbar ist (Lauf abbrechen). */
    static boolean outage(Throwable error) {
        Throwable cause = Futures.unwrap(error);
        if (cause instanceof SimpleCloudException sce) {
            return switch (sce.reason()) {
                case CIRCUIT_OPEN, UNAVAILABLE, RATE_LIMITED, UNAUTHORIZED, NOT_CONFIGURED -> true;
                default -> false;
            };
        }
        return true;
    }

    static String describe(Throwable error) {
        Throwable cause = Futures.unwrap(error);
        if (cause instanceof SimpleCloudException sce) {
            return sce.reason() + (sce.statusCode() > 0 ? " (HTTP " + sce.statusCode() + ")" : "")
                    + (sce.getMessage() != null ? ": " + sce.getMessage() : "");
        }
        return cause.getClass().getSimpleName() + (cause.getMessage() != null ? ": " + cause.getMessage() : "");
    }

    private <T> CompletableFuture<T> track(Supplier<CompletableFuture<T>> call) {
        lookups.incrementAndGet();
        return Futures.call(call).whenComplete((value, error) -> {
            if (error != null) {
                failures.incrementAndGet();
                if (degraded.compareAndSet(false, true)) {
                    logger.warning("SimpleCloud derzeit nicht nutzbar (" + describe(error) + ") - Sitzungen werden "
                            + "über den gemeinsamen Zustand oder vorläufig bestimmt und später abgeglichen");
                } else {
                    logger.fine("SimpleCloud-Aufruf fehlgeschlagen: " + describe(error));
                }
            } else if (degraded.compareAndSet(true, false)) {
                logger.info("SimpleCloud wieder erreichbar");
            }
        });
    }
}
