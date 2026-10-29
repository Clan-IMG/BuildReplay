package net.techniqstone.buildReplay.command;

import static net.techniqstone.buildReplay.command.Formatters.plain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.techniqstone.buildReplay.config.Messages;
import net.techniqstone.buildReplay.maintenance.HealthReport;
import net.techniqstone.buildReplay.maintenance.RetentionResult;
import net.techniqstone.buildReplay.recording.ActiveRecordingInfo;
import net.techniqstone.buildReplay.recording.EngineStatus;
import net.techniqstone.buildReplay.recording.RecordingStats;
import net.techniqstone.buildReplay.session.SessionServiceStats;
import net.techniqstone.buildReplay.simplecloud.ClientHealth;
import net.techniqstone.buildReplay.state.SharedStateHealth;
import net.techniqstone.buildReplay.storage.StoreHealth;
import org.jspecify.annotations.Nullable;

/** Ausgaben für {@code /buildreplay status} und {@code /buildreplay admin ...} (ohne Bukkit-Zugriff). */
public final class AdminViews {

    private final Messages messages;
    private final Formatters fmt;

    public AdminViews(Messages messages, Formatters formatters) {
        this.messages = messages;
        this.fmt = formatters;
    }

    // ------------------------------------------------------------------ Status

    public List<Component> status(HealthReport report) {
        List<Component> lines = new ArrayList<>();
        lines.add(CommandTexts.STATUS_HEADER.render(messages,
                        "server", plain(report.serverName()),
                        "instance", plain(Formatters.shortId(report.instanceId())),
                        "version", plain(report.pluginVersion()))
                .hoverEvent(HoverEvent.showText(Component.text(report.instanceId() + "\n" + fmt.dateTime(report.at()),
                        NamedTextColor.GRAY))));

        ClientHealth cloud = report.simpleCloud();
        lines.add(withError(CommandTexts.STATUS_SIMPLECLOUD.render(messages,
                "state", simpleCloudState(cloud),
                "requests", String.valueOf(cloud.requests()),
                "failures", String.valueOf(cloud.failures()),
                "last", lastSuccess(cloud.lastSuccess())), cloud.lastError()));
        lines.add(CommandTexts.STATUS_JAVA_API.render(messages, "state",
                (report.javaApiAvailable() ? CommandTexts.STATE_YES : CommandTexts.STATE_NO).raw(messages)));

        StoreHealth db = report.database();
        lines.add(withError(CommandTexts.STATUS_DATABASE.render(messages,
                "state", databaseState(db),
                "queued", String.valueOf(db.queuedWrites()),
                "outbox", String.valueOf(db.persistedOutboxEntries()),
                "dropped", String.valueOf(db.droppedWrites()),
                "schema", String.valueOf(db.schemaVersion())), db.lastError()));

        SharedStateHealth redis = report.redis();
        lines.add(withError(CommandTexts.STATUS_REDIS.render(messages,
                "state", redisState(redis),
                "last", lastSuccess(redis.lastSuccess())), redis.lastError()));

        EngineStatus engine = report.engine();
        lines.add(withError(CommandTexts.STATUS_ENGINE.render(messages,
                "version", plain(engine.advancedReplayVersion()),
                "state", engineState(engine),
                "backend", plain(engine.storageBackend()),
                "maxLength", engine.maxLengthSeconds() > 0
                        ? Formatters.clipDuration(engine.maxLengthSeconds()) : Formatters.NONE,
                "blocks", (engine.clientSideBlockChanges()
                        ? CommandTexts.STATE_CLIENT_SIDE : CommandTexts.STATE_REAL_WORLD).raw(messages)),
                engine.incompatibilityReason()));

        RecordingStats recordings = report.recordings();
        lines.add(CommandTexts.STATUS_RECORDINGS.render(messages,
                "active", String.valueOf(recordings.active()),
                "started", String.valueOf(recordings.rawRecordingsStarted()),
                "saved", String.valueOf(recordings.segmentsSaved()),
                "discarded", String.valueOf(recordings.recordingsDiscarded()),
                "failures", String.valueOf(recordings.failures()),
                "pending", String.valueOf(recordings.pendingFinalizations())));

        SessionServiceStats sessions = report.sessions();
        lines.add(CommandTexts.STATUS_SESSIONS.render(messages,
                "local", String.valueOf(sessions.localPlayers()),
                "resolved", String.valueOf(sessions.resolvedSessions()),
                "provisional", String.valueOf(sessions.provisionalSessions()),
                "lookups", String.valueOf(sessions.cloudLookups()),
                "lookupFailures", String.valueOf(sessions.cloudLookupFailures()),
                "leader", (sessions.reconcilerLeader() ? CommandTexts.STATE_YES : CommandTexts.STATE_NO).raw(messages)));

        if (report.warnings().isEmpty()) {
            lines.add(CommandTexts.STATUS_NO_WARNINGS.render(messages));
        } else {
            lines.add(CommandTexts.STATUS_WARNINGS.render(messages));
            for (String warning : report.warnings()) {
                lines.add(warning(warning));
            }
        }
        return lines;
    }

    private String simpleCloudState(ClientHealth cloud) {
        Text state;
        if (!cloud.configured()) {
            state = CommandTexts.STATE_NOT_CONFIGURED;
        } else if (cloud.circuitOpen()) {
            state = CommandTexts.STATE_CIRCUIT_OPEN;
        } else if (failing(cloud.lastSuccess(), cloud.lastFailure())) {
            state = CommandTexts.STATE_DOWN;
        } else {
            state = CommandTexts.STATE_OK;
        }
        return state.raw(messages);
    }

    private String databaseState(StoreHealth db) {
        Text state = !db.enabled() ? CommandTexts.STATE_DISABLED
                : db.connected() ? CommandTexts.STATE_OK : CommandTexts.STATE_DOWN;
        return state.raw(messages);
    }

    private String redisState(SharedStateHealth redis) {
        Text state;
        if (!redis.enabled()) {
            state = CommandTexts.STATE_DISABLED;
        } else if (redis.usingLocalFallback() || !redis.connected()) {
            state = CommandTexts.STATE_FALLBACK;
        } else {
            state = CommandTexts.STATE_OK;
        }
        return state.raw(messages);
    }

    private String engineState(EngineStatus engine) {
        Text state;
        if (!engine.installed()) {
            state = CommandTexts.STATE_NOT_INSTALLED;
        } else if (!engine.available()) {
            state = CommandTexts.STATE_UNAVAILABLE;
        } else if (!engine.playbackCompatible()) {
            state = CommandTexts.STATE_INCOMPATIBLE;
        } else {
            state = CommandTexts.STATE_OK;
        }
        return state.raw(messages);
    }

    /** Letzter Fehlschlag jünger als der letzte Erfolg (oder nie ein Erfolg). */
    static boolean failing(@Nullable Instant lastSuccess, @Nullable Instant lastFailure) {
        return lastFailure != null && (lastSuccess == null || lastFailure.isAfter(lastSuccess));
    }

    private String lastSuccess(@Nullable Instant at) {
        return at == null ? CommandTexts.STATE_NEVER.raw(messages) : fmt.dateTime(at);
    }

    private Component withError(Component line, @Nullable String error) {
        if (error == null || error.isBlank()) {
            return line;
        }
        return line.hoverEvent(HoverEvent.showText(CommandTexts.STATUS_LAST_ERROR_HOVER.render(messages,
                "error", plain(error))));
    }

    // ------------------------------------------------------------------ Aufnahmen

    public List<Component> recordings(List<ActiveRecordingInfo> active, RecordingStats stats, Instant now) {
        List<Component> lines = new ArrayList<>();
        lines.add(CommandTexts.ADMIN_RECORDINGS_HEADER.render(messages, "count", String.valueOf(active.size())));
        if (active.isEmpty()) {
            lines.add(CommandTexts.ADMIN_RECORDINGS_EMPTY.render(messages));
        }
        List<ActiveRecordingInfo> sorted = new ArrayList<>(active);
        sorted.sort((a, b) -> a.playerName().compareToIgnoreCase(b.playerName()));
        for (ActiveRecordingInfo info : sorted) {
            Component hover = CommandTexts.ADMIN_RECORDINGS_HOVER.render(messages,
                    "raw", plain(info.rawName()),
                    "tick", String.valueOf(info.currentTick()),
                    "session", plain(info.sessionRef() == null ? Formatters.NONE : info.sessionRef()));
            lines.add(CommandTexts.ADMIN_RECORDINGS_LINE.render(messages,
                            "player", plain(info.playerName()),
                            "world", plain(info.worldName() == null ? Formatters.NONE : info.worldName()),
                            "since", fmt.time(info.startedAt()),
                            "duration", Formatters.clipDuration(Duration.between(info.startedAt(), now).toSeconds()),
                            "actions", String.valueOf(info.relevantActions()),
                            "afk", info.afk() ? CommandTexts.ADMIN_RECORDINGS_AFK.raw(messages) : "")
                    .hoverEvent(HoverEvent.showText(hover))
                    .clickEvent(ClickEvent.runCommand(SessionViews.ROOT + " admin inspect " + info.playerName())));
        }
        lines.add(CommandTexts.ADMIN_RECORDINGS_STATS.render(messages,
                "started", String.valueOf(stats.rawRecordingsStarted()),
                "saved", String.valueOf(stats.segmentsSaved()),
                "discarded", String.valueOf(stats.recordingsDiscarded()),
                "failures", String.valueOf(stats.failures()),
                "pending", String.valueOf(stats.pendingFinalizations())));
        return lines;
    }

    public List<Component> inspect(String playerName, @Nullable ActiveRecordingInfo info, Instant now) {
        List<Component> lines = new ArrayList<>();
        String name = plain(playerName);
        lines.add(CommandTexts.ADMIN_INSPECT_HEADER.render(messages, "player", name));
        if (info == null) {
            lines.add(CommandTexts.ADMIN_INSPECT_NONE.render(messages, "player", name));
        } else {
            lines.add(CommandTexts.ADMIN_INSPECT_DETAILS.render(messages,
                    "raw", plain(info.rawName()),
                    "world", plain(info.worldName() == null ? Formatters.NONE : info.worldName()),
                    "since", fmt.dateTime(info.startedAt()),
                    "duration", Formatters.clipDuration(Duration.between(info.startedAt(), now).toSeconds()),
                    "tick", String.valueOf(info.currentTick()),
                    "actions", String.valueOf(info.relevantActions()),
                    "afk", (info.afk() ? CommandTexts.STATE_YES : CommandTexts.STATE_NO).raw(messages),
                    "session", plain(info.sessionRef() == null ? Formatters.NONE : info.sessionRef()))
                    .clickEvent(ClickEvent.copyToClipboard(info.rawName())));
        }
        lines.add(CommandTexts.ADMIN_INSPECT_SESSIONS.render(messages)
                .clickEvent(ClickEvent.runCommand(SessionViews.sessionsCommand(playerName, 0)))
                .hoverEvent(HoverEvent.showText(CommandTexts.ADMIN_INSPECT_SESSIONS_HOVER.render(messages,
                        "player", name))));
        return lines;
    }

    // ------------------------------------------------------------------ Wartung

    public Component cleanup(RetentionResult result) {
        Text text = result.dryRun() ? CommandTexts.ADMIN_CLEANUP_DRY_RUN : CommandTexts.ADMIN_CLEANUP_DONE;
        return text.render(messages,
                "deleted", String.valueOf(result.deleted()),
                "failed", String.valueOf(result.failed()),
                "examined", String.valueOf(result.examined()));
    }

    public List<Component> reload(List<String> warnings) {
        List<Component> lines = new ArrayList<>();
        lines.add(CommandTexts.ADMIN_RELOAD_DONE.render(messages));
        if (!warnings.isEmpty()) {
            lines.add(CommandTexts.ADMIN_RELOAD_WARNINGS.render(messages, "count", String.valueOf(warnings.size())));
            for (String warning : warnings) {
                lines.add(warning(warning));
            }
        }
        return lines;
    }

    private Component warning(String warning) {
        return CommandTexts.WARNING_BULLET.render(messages).append(Component.text(warning, NamedTextColor.GRAY));
    }
}
