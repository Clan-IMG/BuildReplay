package net.techniqstone.buildReplay.recording.service.fakes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.techniqstone.buildReplay.session.NetworkSession;
import net.techniqstone.buildReplay.session.SessionServiceStats;
import net.techniqstone.buildReplay.session.Sessions;
import net.techniqstone.buildReplay.storage.model.SessionSource;
import net.techniqstone.buildReplay.storage.model.VisitEndReason;

/**
 * Sitzungsverwaltung mit fest hinterlegten Sitzungen. {@link #currentKnown} = {@code false} stellt nach, dass die
 * Auflösung beim Join noch läuft; mit {@link #pending} lässt sich die Auflösung zurückhalten.
 */
public final class FakeSessions implements Sessions {

    public final Map<UUID, NetworkSession> sessions = new HashMap<>();
    public final Map<UUID, CompletableFuture<NetworkSession>> pending = new HashMap<>();
    public final List<String> calls;
    public boolean currentKnown = true;

    public FakeSessions() {
        this(new ArrayList<>());
    }

    /** Mit gemeinsamem Aufrufprotokoll (Reihenfolge über mehrere Fakes prüfen). */
    public FakeSessions(List<String> calls) {
        this.calls = calls;
    }

    public NetworkSession cloudSession(UUID player, String sessionId) {
        NetworkSession session = new NetworkSession(sessionId, sessionId, player, SessionSource.SIMPLECLOUD,
                Instant.parse("2026-10-29T10:00:00Z"));
        sessions.put(player, session);
        return session;
    }

    @Override
    public void handleJoin(UUID player, String name, Instant joinedAt) {
        calls.add("join:" + name);
    }

    @Override
    public void handleQuit(UUID player, String name, Instant leftAt, VisitEndReason reason) {
        calls.add("quit:" + name + ":" + reason);
    }

    @Override
    public Optional<NetworkSession> current(UUID player) {
        return currentKnown ? Optional.ofNullable(sessions.get(player)) : Optional.empty();
    }

    @Override
    public CompletableFuture<NetworkSession> resolve(UUID player) {
        CompletableFuture<NetworkSession> held = pending.get(player);
        if (held != null) {
            return held;
        }
        NetworkSession session = sessions.get(player);
        return session != null ? CompletableFuture.completedFuture(session)
                : CompletableFuture.failedFuture(new IllegalStateException("keine Sitzung"));
    }

    @Override
    public SessionServiceStats stats() {
        return new SessionServiceStats(0, sessions.size(), 0, 0, 0, false);
    }
}
