package net.techniqstone.buildReplay.session.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.techniqstone.buildReplay.simplecloud.ClientHealth;
import net.techniqstone.buildReplay.simplecloud.CloudOnlinePlayer;
import net.techniqstone.buildReplay.simplecloud.CloudSession;
import net.techniqstone.buildReplay.simplecloud.CloudSessionPage;
import net.techniqstone.buildReplay.simplecloud.SimpleCloudClient;
import net.techniqstone.buildReplay.simplecloud.SimpleCloudException;

/**
 * SimpleCloud im Speicher: Sitzungshistorie pro Spieler (neueste zuerst, wie die REST-API), Online-Liste aus den
 * aktiven Sitzungen. Kann ausfallen ({@link #setFailing}) und die ersten {@code n} {@code /sessions/current}-Abfragen
 * mit "nicht online" beantworten (verzögertes Anlegen beim Proxy-Login).
 */
final class FakeSimpleCloudClient implements SimpleCloudClient {

    private final Map<UUID, List<CloudSession>> sessions = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private final AtomicInteger currentCalls = new AtomicInteger();
    private final AtomicInteger listCalls = new AtomicInteger();
    private final AtomicInteger onlineCalls = new AtomicInteger();
    private final AtomicInteger emptyCurrentAnswers = new AtomicInteger();
    private volatile boolean configured = true;
    private volatile boolean failing;
    private volatile boolean circuitOpen;

    // ------------------------------------------------------------------ Steuerung

    synchronized CloudSession login(UUID player, String name, Instant at) {
        names.put(player, name);
        CloudSession session = new CloudSession(UUID.randomUUID().toString(), player, at, null, null);
        List<CloudSession> history = sessions.computeIfAbsent(player, key -> new ArrayList<>());
        history.add(session);
        history.sort(Comparator.comparing(CloudSession::loginTime).reversed());
        return session;
    }

    /** Fügt eine bereits beendete Sitzung hinzu (Historie). */
    synchronized CloudSession addClosed(UUID player, Instant login, Instant logout) {
        long duration = Math.max(0, Duration.between(login, logout).toSeconds());
        CloudSession session = new CloudSession(UUID.randomUUID().toString(), player, login, logout, duration);
        return add(player, session);
    }

    synchronized CloudSession add(UUID player, CloudSession session) {
        List<CloudSession> history = sessions.computeIfAbsent(player, key -> new ArrayList<>());
        history.add(session);
        history.sort(Comparator.comparing(CloudSession::loginTime).reversed());
        return session;
    }

    synchronized CloudSession logout(UUID player, Instant at) {
        List<CloudSession> history = sessions.getOrDefault(player, List.of());
        for (int i = 0; i < history.size(); i++) {
            CloudSession session = history.get(i);
            if (session.active()) {
                long duration = Math.max(0, Duration.between(session.loginTime(), at).toSeconds());
                CloudSession closed = new CloudSession(session.id(), player, session.loginTime(), at, duration);
                history.set(i, closed);
                return closed;
            }
        }
        throw new IllegalStateException("keine aktive Sitzung");
    }

    void setFailing(boolean failing) {
        this.failing = failing;
    }

    void setConfigured(boolean configured) {
        this.configured = configured;
    }

    void setCircuitOpen(boolean circuitOpen) {
        this.circuitOpen = circuitOpen;
    }

    /** Die nächsten {@code count} {@code currentSession}-Abfragen liefern "nicht online" (HTTP 404). */
    void answerCurrentEmpty(int count) {
        emptyCurrentAnswers.set(count);
    }

    int currentCalls() {
        return currentCalls.get();
    }

    int listCalls() {
        return listCalls.get();
    }

    int onlineCalls() {
        return onlineCalls.get();
    }

    int totalCalls() {
        return currentCalls.get() + listCalls.get() + onlineCalls.get();
    }

    // ------------------------------------------------------------------ SimpleCloudClient

    @Override
    public boolean isConfigured() {
        return configured;
    }

    @Override
    public CompletableFuture<CloudSessionPage> listSessions(UUID playerId, int limit, int offset) {
        listCalls.incrementAndGet();
        if (failing) {
            return unavailable();
        }
        if (limit < 1 || offset < 0) {
            return CompletableFuture.failedFuture(new SimpleCloudException(SimpleCloudException.Reason.INVALID_REQUEST,
                    400, "limit/offset ungültig"));
        }
        synchronized (this) {
            List<CloudSession> history = sessions.getOrDefault(playerId, List.of());
            int effectiveLimit = Math.min(limit, 100);
            int from = Math.min(offset, history.size());
            int to = Math.min(history.size(), from + effectiveLimit);
            List<CloudSession> slice = List.copyOf(history.subList(from, to));
            return CompletableFuture.completedFuture(new CloudSessionPage(slice, slice.size(), history.size()));
        }
    }

    @Override
    public CompletableFuture<Optional<CloudSession>> currentSession(UUID playerId) {
        currentCalls.incrementAndGet();
        if (failing) {
            return unavailable();
        }
        if (emptyCurrentAnswers.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        synchronized (this) {
            return CompletableFuture.completedFuture(sessions.getOrDefault(playerId, List.of()).stream()
                    .filter(CloudSession::active).findFirst());
        }
    }

    @Override
    public CompletableFuture<List<CloudOnlinePlayer>> onlinePlayers() {
        onlineCalls.incrementAndGet();
        if (failing) {
            return unavailable();
        }
        synchronized (this) {
            List<CloudOnlinePlayer> online = new ArrayList<>();
            sessions.forEach((player, history) -> history.stream().filter(CloudSession::active).findFirst()
                    .ifPresent(session -> online.add(new CloudOnlinePlayer(player, names.getOrDefault(player, "?"),
                            session.id(), "dev", "proxy-1"))));
            return CompletableFuture.completedFuture(online);
        }
    }

    @Override
    public ClientHealth health() {
        return new ClientHealth(configured, circuitOpen, null, null, null, totalCalls(), 0);
    }

    @Override
    public void close() {
        // nichts zu schließen
    }

    private static <T> CompletableFuture<T> unavailable() {
        return CompletableFuture.failedFuture(new SimpleCloudException(SimpleCloudException.Reason.UNAVAILABLE, 503,
                "Testausfall"));
    }
}
