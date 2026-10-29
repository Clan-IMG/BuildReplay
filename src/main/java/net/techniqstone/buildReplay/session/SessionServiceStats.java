package net.techniqstone.buildReplay.session;

/** Kennzahlen der Sitzungsverwaltung für {@code /buildreplay status}. */
public record SessionServiceStats(
        int localPlayers,
        int resolvedSessions,
        int provisionalSessions,
        long cloudLookups,
        long cloudLookupFailures,
        boolean reconcilerLeader) {
}
