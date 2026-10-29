package net.techniqstone.buildReplay.command;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import net.techniqstone.buildReplay.maintenance.RetentionResult;
import net.techniqstone.buildReplay.playback.Playback;
import net.techniqstone.buildReplay.recording.ActiveRecordingInfo;
import net.techniqstone.buildReplay.recording.RecordingStats;
import net.techniqstone.buildReplay.recording.Recordings;
import net.techniqstone.buildReplay.session.SessionDetails;
import net.techniqstone.buildReplay.session.SessionListPage;
import net.techniqstone.buildReplay.session.SessionQueries;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Kleine Fakes für die Befehlstests. */
final class Fakes {

    private Fakes() {
    }

    record ListCall(UUID player, int page) {
    }

    record DetailsCall(UUID player, String ref) {
    }

    static final class Queries implements SessionQueries {
        final List<ListCall> listCalls = new ArrayList<>();
        final List<DetailsCall> detailsCalls = new ArrayList<>();
        CompletableFuture<SessionListPage> nextList = new CompletableFuture<>();
        CompletableFuture<Optional<SessionDetails>> nextDetails = new CompletableFuture<>();
        /** Wird statt eines Futures sofort geworfen (z. B. volle I/O-Warteschlange). */
        RuntimeException listThrows;

        @Override
        public CompletableFuture<SessionListPage> listSessions(UUID player, int page) {
            listCalls.add(new ListCall(player, page));
            if (listThrows != null) {
                throw listThrows;
            }
            return nextList;
        }

        @Override
        public CompletableFuture<Optional<SessionDetails>> details(UUID player, String ref) {
            detailsCalls.add(new DetailsCall(player, ref));
            return nextDetails;
        }
    }

    static final class PlaybackFake implements Playback {
        final List<String> calls = new ArrayList<>();
        boolean watching;

        @Override
        public void playSegment(Player viewer, long segmentId) {
            calls.add("segment " + viewer.getName() + " " + segmentId);
        }

        @Override
        public void playSession(Player viewer, UUID owner, String sessionRef) {
            calls.add("session " + viewer.getName() + " " + owner + " " + sessionRef);
        }

        @Override
        public void stop(Player viewer) {
            calls.add("stop " + viewer.getName());
        }

        @Override
        public boolean isWatching(UUID viewer) {
            return watching;
        }
    }

    static final class RecordingsFake implements Recordings {
        final List<ActiveRecordingInfo> active = new ArrayList<>();

        @Override
        public List<ActiveRecordingInfo> activeRecordings() {
            return List.copyOf(active);
        }

        @Override
        public Optional<ActiveRecordingInfo> recordingOf(UUID player) {
            return active.stream().filter(info -> info.player().equals(player)).findFirst();
        }

        @Override
        public RecordingStats stats() {
            return new RecordingStats(active.size(), 12, 30, 4, 1, 0);
        }
    }

    static final class Admin implements AdminActions {
        final List<String> calls = new ArrayList<>();
        CompletableFuture<List<String>> reload = CompletableFuture.completedFuture(List.of());
        CompletableFuture<RetentionResult> cleanup = new CompletableFuture<>();
        CompletableFuture<Void> sync = CompletableFuture.completedFuture(null);

        @Override
        public CompletableFuture<List<String>> reload() {
            calls.add("reload");
            return reload;
        }

        @Override
        public CompletableFuture<RetentionResult> cleanup(boolean dryRun) {
            calls.add("cleanup dryRun=" + dryRun);
            return cleanup;
        }

        @Override
        public CompletableFuture<Void> syncNow() {
            calls.add("sync");
            return sync;
        }
    }

    static final class Players implements PlayerLookup {
        final Map<String, KnownPlayer> known = new HashMap<>();
        final List<String> online = new ArrayList<>();

        Players add(String name, UUID uuid) {
            known.put(name.toLowerCase(), new KnownPlayer(uuid, name));
            online.add(name);
            return this;
        }

        @Override
        public Optional<KnownPlayer> find(String name) {
            return Optional.ofNullable(known.get(name.toLowerCase()));
        }

        @Override
        public List<String> onlineNames(CommandSender viewer) {
            return List.copyOf(online);
        }
    }

    /** Sammelt Log-Einträge. */
    static final class CapturingHandler extends Handler {
        final List<LogRecord> records = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
