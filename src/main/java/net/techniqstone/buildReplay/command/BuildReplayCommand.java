package net.techniqstone.buildReplay.command;

import static net.techniqstone.buildReplay.command.Formatters.plain;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.techniqstone.buildReplay.command.CommandFailures.Failure;
import net.techniqstone.buildReplay.config.Messages;
import net.techniqstone.buildReplay.config.PluginConfig;
import net.techniqstone.buildReplay.core.TaskRunner;
import net.techniqstone.buildReplay.maintenance.HealthReport;
import net.techniqstone.buildReplay.playback.Playback;
import net.techniqstone.buildReplay.recording.ActiveRecordingInfo;
import net.techniqstone.buildReplay.recording.Recordings;
import net.techniqstone.buildReplay.session.AmbiguousReferenceException;
import net.techniqstone.buildReplay.session.SessionDetails;
import net.techniqstone.buildReplay.session.SessionListEntry;
import net.techniqstone.buildReplay.session.SessionQueries;
import net.techniqstone.buildReplay.storage.model.SegmentRow;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * {@code /buildreplay} (Aliasse {@code brp}, {@code buildreplays}).
 *
 * <p>Läuft auf dem Hauptthread. Abfragen ({@link SessionQueries}, {@link AdminActions}) liefern Futures, die auf
 * I/O-Threads abschließen; die Antwort wird über {@link TaskRunner#mainExecutor()} zurück auf den Hauptthread
 * gereicht, bevor etwas gesendet wird. Fehler werden auf verständliche Meldungen abgebildet
 * ({@link CommandFailures}); Unerwartetes wird mit SEVERE geloggt. Listen- und Detailabfragen haben eine
 * Abklingzeit von einer Sekunde pro Spieler.
 *
 * <p>{@code health} wird auf dem Hauptthread aufgerufen und muss schnell sein (siehe {@code HealthService#report}).
 * Rückmeldungen zu {@code play}/{@code stop} sendet {@link Playback} selbst.
 */
public final class BuildReplayCommand implements TabExecutor {

    static final Duration COOLDOWN = Duration.ofSeconds(1);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_REMEMBERED_SENDERS = 512;

    private final Messages messages;
    private final TaskRunner tasks;
    private final Clock clock;
    private final SessionQueries queries;
    private final Playback playback;
    private final Recordings recordings;
    private final Supplier<HealthReport> health;
    private final AdminActions admin;
    private final Logger logger;
    private final PlayerLookup players;
    private final Cooldowns cooldowns;
    private final RecentReferences recent = new RecentReferences(MAX_REMEMBERED_SENDERS);
    private volatile PluginConfig config;

    public BuildReplayCommand(PluginConfig config, Messages messages, TaskRunner tasks, Clock clock,
                              SessionQueries queries, Playback playback, Recordings recordings,
                              Supplier<HealthReport> health, AdminActions admin, Logger logger) {
        this(config, messages, tasks, clock, queries, playback, recordings, health, admin, logger,
                PlayerLookup.bukkit());
    }

    /** Wie oben, mit eigener Spielerauflösung (Tests, andere Plattformen). */
    public BuildReplayCommand(PluginConfig config, Messages messages, TaskRunner tasks, Clock clock,
                              SessionQueries queries, Playback playback, Recordings recordings,
                              Supplier<HealthReport> health, AdminActions admin, Logger logger,
                              PlayerLookup players) {
        this.config = Objects.requireNonNull(config, "config");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.queries = Objects.requireNonNull(queries, "queries");
        this.playback = Objects.requireNonNull(playback, "playback");
        this.recordings = Objects.requireNonNull(recordings, "recordings");
        this.health = Objects.requireNonNull(health, "health");
        this.admin = Objects.requireNonNull(admin, "admin");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.players = Objects.requireNonNull(players, "players");
        this.cooldowns = new Cooldowns(clock, COOLDOWN);
    }

    /** Nach {@code /buildreplay admin reload}: neue Konfiguration übernehmen (Anzeige, Zeitzone, Seitengröße). */
    public void updateConfig(PluginConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    // ------------------------------------------------------------------ Ausführung

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            dispatch(sender, CommandArguments.parse(args));
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "Fehler bei /" + label + " " + String.join(" ", args) + " (" + sender.getName()
                    + ")", e);
            CommandTexts.ERROR_GENERIC.send(messages, sender);
        }
        return true;
    }

    private void dispatch(CommandSender sender, CommandRequest request) {
        switch (request) {
            case CommandRequest.Help help -> help(sender);
            case CommandRequest.ListSessions list -> listSessions(sender, list);
            case CommandRequest.ShowSession show -> showSession(sender, show);
            case CommandRequest.PlaySegment play -> playSegment(sender, play);
            case CommandRequest.PlaySession play -> playSession(sender, play);
            case CommandRequest.Stop stop -> stop(sender);
            case CommandRequest.Status status -> status(sender);
            case CommandRequest.AdminRecordings recordingsRequest -> adminRecordings(sender);
            case CommandRequest.AdminInspect inspect -> adminInspect(sender, inspect);
            case CommandRequest.AdminReload reload -> adminReload(sender);
            case CommandRequest.AdminCleanup cleanup -> adminCleanup(sender, cleanup);
            case CommandRequest.AdminSync sync -> adminSync(sender);
            case CommandRequest.Invalid invalid -> invalid(sender, invalid);
        }
    }

    private void invalid(CommandSender sender, CommandRequest.Invalid invalid) {
        boolean adminUsage = isAdminText(invalid.text()) || (invalid.usage() != null && isAdminText(invalid.usage()));
        if (adminUsage && !require(sender, Permissions.ADMIN)) {
            return;
        }
        String[] placeholders = invalid.placeholderArray();
        for (int i = 1; i < placeholders.length; i += 2) {
            placeholders[i] = plain(placeholders[i]);
        }
        invalid.text().send(messages, sender, placeholders);
        if (invalid.usage() != null) {
            sender.sendMessage(invalid.usage().render(messages));
        }
    }

    private static boolean isAdminText(Text text) {
        return text == CommandTexts.USAGE_ADMIN || text == CommandTexts.USAGE_INSPECT
                || text == CommandTexts.USAGE_CLEANUP;
    }

    private void help(CommandSender sender) {
        if (!require(sender, Permissions.USE)) {
            return;
        }
        List<Component> lines = new ArrayList<>();
        lines.add(CommandTexts.HELP_HEADER.render(messages));
        lines.add(helpLine(CommandTexts.HELP_SESSIONS, "sessions", "days",
                String.valueOf(config.display().historyDays())));
        if (sender.hasPermission(Permissions.OTHERS)) {
            lines.add(helpLine(CommandTexts.HELP_SESSIONS_OTHERS, "sessions "));
        }
        lines.add(helpLine(CommandTexts.HELP_SESSION, "session "));
        if (sender.hasPermission(Permissions.PLAY)) {
            lines.add(helpLine(CommandTexts.HELP_PLAY, "play "));
            lines.add(helpLine(CommandTexts.HELP_PLAY_SESSION, "play session "));
            lines.add(helpLine(CommandTexts.HELP_STOP, "stop"));
        }
        if (sender.hasPermission(Permissions.ADMIN)) {
            lines.add(helpLine(CommandTexts.HELP_STATUS, "status"));
            lines.add(helpLine(CommandTexts.HELP_ADMIN, "admin "));
        }
        send(sender, lines);
    }

    private Component helpLine(Text text, String suggestion, String... placeholders) {
        return text.render(messages, placeholders)
                .clickEvent(ClickEvent.suggestCommand(SessionViews.ROOT + " " + suggestion))
                .hoverEvent(HoverEvent.showText(CommandTexts.HELP_HOVER.render(messages)));
    }

    // ------------------------------------------------------------------ Sitzungen

    private void listSessions(CommandSender sender, CommandRequest.ListSessions request) {
        if (!require(sender, Permissions.USE)) {
            return;
        }
        Target target = resolveTarget(sender, request.playerName(), CommandTexts.USAGE_SESSIONS_CONSOLE);
        if (target == null || !passCooldown(sender)) {
            return;
        }
        String other = target.own() ? null : target.name();
        async(sender, "sessions", () -> queries.listSessions(target.uuid(), request.page()), page -> {
            send(sender, sessionViews().sessionList(page, other, clock.instant()));
            recent.rememberRefs(key(sender), page.entries().stream().map(SessionListEntry::ref).toList());
        }, null);
    }

    private void showSession(CommandSender sender, CommandRequest.ShowSession request) {
        if (!require(sender, Permissions.USE)) {
            return;
        }
        Target target = resolveTarget(sender, request.playerName(), CommandTexts.USAGE_SESSION);
        if (target == null || !passCooldown(sender)) {
            return;
        }
        String other = target.own() ? null : target.name();
        async(sender, "session", () -> queries.details(target.uuid(), request.ref()), details -> {
            if (details.isEmpty()) {
                CommandTexts.SESSION_NOT_FOUND.send(messages, sender, "ref", plain(request.ref()));
                return;
            }
            SessionDetails found = details.get();
            send(sender, sessionViews().sessionDetails(found, other, sender.hasPermission(Permissions.PLAY)));
            recent.rememberRefs(key(sender), List.of(found.summary().ref()));
            recent.rememberSegments(key(sender), found.segments().stream()
                    .filter(segment -> segment.status().playable())
                    .map(SegmentRow::id)
                    .toList());
        }, failure -> {
            if (failure.cause() instanceof AmbiguousReferenceException ambiguous) {
                send(sender, sessionViews().ambiguous(request.ref(), ambiguous.candidates(), other));
                recent.rememberRefs(key(sender), ambiguous.candidates());
                return true;
            }
            return false;
        });
    }

    // ------------------------------------------------------------------ Wiedergabe

    private void playSegment(CommandSender sender, CommandRequest.PlaySegment request) {
        Player viewer = requirePlayer(sender);
        if (viewer == null || !require(sender, Permissions.PLAY)) {
            return;
        }
        playback.playSegment(viewer, request.segmentId());
    }

    private void playSession(CommandSender sender, CommandRequest.PlaySession request) {
        Player viewer = requirePlayer(sender);
        if (viewer == null || !require(sender, Permissions.PLAY)) {
            return;
        }
        Target target = resolveTarget(sender, request.playerName(), CommandTexts.USAGE_PLAY);
        if (target == null) {
            return;
        }
        playback.playSession(viewer, target.uuid(), request.ref());
    }

    private void stop(CommandSender sender) {
        Player viewer = requirePlayer(sender);
        if (viewer == null || !require(sender, Permissions.USE)) {
            return;
        }
        // Immer weitergeben: auch zwischen zwei Abschnitten einer Sitzung (kein Replay läuft gerade) muss die
        // Warteschlange enden. Die Rückmeldung (beendet / kein Replay) sendet die Wiedergabe selbst.
        playback.stop(viewer);
    }

    // ------------------------------------------------------------------ Status und Verwaltung

    private void status(CommandSender sender) {
        if (!require(sender, Permissions.ADMIN)) {
            return;
        }
        send(sender, adminViews().status(health.get()));
    }

    private void adminRecordings(CommandSender sender) {
        if (!require(sender, Permissions.ADMIN)) {
            return;
        }
        send(sender, adminViews().recordings(recordings.activeRecordings(), recordings.stats(), clock.instant()));
    }

    private void adminInspect(CommandSender sender, CommandRequest.AdminInspect request) {
        if (!require(sender, Permissions.ADMIN)) {
            return;
        }
        Optional<KnownPlayer> known = players.find(request.playerName());
        if (known.isEmpty()) {
            CommandTexts.UNKNOWN_PLAYER.send(messages, sender, "player", plain(request.playerName()));
            return;
        }
        ActiveRecordingInfo info = recordings.recordingOf(known.get().uuid()).orElse(null);
        send(sender, adminViews().inspect(known.get().name(), info, clock.instant()));
    }

    private void adminReload(CommandSender sender) {
        if (!require(sender, Permissions.ADMIN)) {
            return;
        }
        async(sender, "admin reload", admin::reload, warnings -> send(sender, adminViews().reload(warnings)), null);
    }

    private void adminCleanup(CommandSender sender, CommandRequest.AdminCleanup request) {
        if (!require(sender, Permissions.ADMIN)) {
            return;
        }
        (request.dryRun() ? CommandTexts.ADMIN_CLEANUP_DRY_RUN_STARTED : CommandTexts.ADMIN_CLEANUP_STARTED)
                .send(messages, sender);
        async(sender, "admin cleanup", () -> admin.cleanup(request.dryRun()),
                result -> messages.sendComponent(sender, adminViews().cleanup(result)), null);
    }

    private void adminSync(CommandSender sender) {
        if (!require(sender, Permissions.ADMIN)) {
            return;
        }
        CommandTexts.ADMIN_SYNC_STARTED.send(messages, sender);
        async(sender, "admin sync", admin::syncNow, ignored -> CommandTexts.ADMIN_SYNC_DONE.send(messages, sender),
                null);
    }

    // ------------------------------------------------------------------ Tab-Vervollständigung

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        try {
            return complete(sender, args);
        } catch (RuntimeException e) {
            logger.log(Level.FINE, "Tab-Vervollständigung fehlgeschlagen", e);
            return List.of();
        }
    }

    private List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 0 || !sender.hasPermission(Permissions.USE)) {
            return List.of();
        }
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("help", "sessions", "session"));
            if (sender.hasPermission(Permissions.PLAY)) {
                options.add("play");
                options.add("stop");
            }
            if (sender.hasPermission(Permissions.ADMIN)) {
                options.add("status");
                options.add("admin");
            }
            return filter(options, args[0]);
        }
        boolean others = sender.hasPermission(Permissions.OTHERS);
        String current = args[args.length - 1];
        return switch (CommandArguments.subcommand(args[0])) {
            case "sessions" -> args.length == 2 && others ? filter(players.onlineNames(sender), current) : List.of();
            case "session" -> switch (args.length) {
                case 2 -> filter(recent.refs(key(sender)), current);
                case 3 -> others ? filter(players.onlineNames(sender), current) : List.of();
                default -> List.of();
            };
            case "play" -> completePlay(sender, args, others, current);
            case "admin" -> completeAdmin(sender, args, current);
            default -> List.of();
        };
    }

    private List<String> completePlay(CommandSender sender, String[] args, boolean others, String current) {
        if (!sender.hasPermission(Permissions.PLAY)) {
            return List.of();
        }
        if (args.length == 2) {
            List<String> options = new ArrayList<>();
            options.add("session");
            options.addAll(recent.segmentIds(key(sender)));
            return filter(options, current);
        }
        if (!CommandArguments.isSessionKeyword(args[1])) {
            return List.of();
        }
        return switch (args.length) {
            case 3 -> filter(recent.refs(key(sender)), current);
            case 4 -> others ? filter(players.onlineNames(sender), current) : List.of();
            default -> List.of();
        };
    }

    private List<String> completeAdmin(CommandSender sender, String[] args, String current) {
        if (!sender.hasPermission(Permissions.ADMIN)) {
            return List.of();
        }
        if (args.length == 2) {
            return filter(CommandArguments.ADMIN_SUBCOMMANDS, current);
        }
        if (args.length == 3) {
            return switch (CommandArguments.adminSubcommand(args[1])) {
                case "inspect" -> filter(players.onlineNames(sender), current);
                case "cleanup" -> filter(List.of(CommandArguments.DRY_RUN_FLAG), current);
                default -> List.of();
            };
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }

    // ------------------------------------------------------------------ Hilfsfunktionen

    /** Wessen Sitzungen gemeint sind. */
    private record Target(UUID uuid, String name, boolean own) {
    }

    /** Eigene Sitzungen (ohne Namen) oder die eines anderen Spielers (braucht {@link Permissions#OTHERS}); {@code null} = Fehler gemeldet. */
    private @Nullable Target resolveTarget(CommandSender sender, @Nullable String playerName, Text consoleUsage) {
        if (playerName == null) {
            if (sender instanceof Player self) {
                return new Target(self.getUniqueId(), self.getName(), true);
            }
            CommandTexts.ONLY_PLAYERS.send(messages, sender);
            sender.sendMessage(consoleUsage.render(messages));
            return null;
        }
        if (sender instanceof Player self && self.getName().equalsIgnoreCase(playerName)) {
            return new Target(self.getUniqueId(), self.getName(), true);
        }
        if (!require(sender, Permissions.OTHERS)) {
            return null;
        }
        Optional<KnownPlayer> known = players.find(playerName);
        if (known.isEmpty()) {
            CommandTexts.UNKNOWN_PLAYER.send(messages, sender, "player", plain(playerName));
            return null;
        }
        boolean own = sender instanceof Player self && self.getUniqueId().equals(known.get().uuid());
        return new Target(known.get().uuid(), known.get().name(), own);
    }

    private boolean require(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        CommandTexts.NO_PERMISSION.send(messages, sender);
        return false;
    }

    private @Nullable Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        CommandTexts.ONLY_PLAYERS.send(messages, sender);
        return null;
    }

    private boolean passCooldown(CommandSender sender) {
        if (sender instanceof Player player && !cooldowns.tryAcquire(player.getUniqueId())) {
            CommandTexts.COOLDOWN.send(messages, sender);
            return false;
        }
        return true;
    }

    /**
     * Führt eine asynchrone Abfrage aus und verarbeitet das Ergebnis auf dem Hauptthread. {@code special} kann
     * einzelne Fehler selbst behandeln (z. B. mehrdeutige Referenzen) und gibt dann {@code true} zurück.
     */
    private <T> void async(CommandSender sender, String action, Supplier<CompletableFuture<T>> call,
                           Consumer<T> onSuccess, @Nullable Predicate<Failure> special) {
        CompletableFuture<T> future;
        try {
            future = Objects.requireNonNull(call.get(), "Abfrage lieferte kein Future");
        } catch (RuntimeException e) {
            future = CompletableFuture.failedFuture(e);
        }
        // copy(): ein Zeitlimit darf ein evtl. geteiltes (zwischengespeichertes) Future nicht abbrechen
        future.copy()
                .orTimeout(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .whenCompleteAsync((value, error) -> {
                    if (!reachable(sender)) {
                        return;
                    }
                    if (error != null) {
                        fail(sender, action, error, special);
                        return;
                    }
                    try {
                        onSuccess.accept(value);
                    } catch (RuntimeException e) {
                        fail(sender, action, e, null);
                    }
                }, tasks.mainExecutor());
    }

    private void fail(CommandSender sender, String action, Throwable error, @Nullable Predicate<Failure> special) {
        Failure failure = CommandFailures.classify(error);
        if (special != null && special.test(failure)) {
            return;
        }
        switch (failure.kind()) {
            case UNEXPECTED, AMBIGUOUS -> {
                logger.log(Level.SEVERE, "Unerwarteter Fehler bei /buildreplay " + action + " (" + sender.getName()
                        + ")", failure.cause());
                CommandTexts.ERROR_GENERIC.send(messages, sender);
            }
            case MAINTENANCE -> CommandTexts.ERROR_MAINTENANCE.send(messages, sender, "reason",
                    plain(String.valueOf(failure.cause().getMessage())));
            default -> {
                logger.log(Level.FINE, "/buildreplay " + action + " fehlgeschlagen: " + failure.cause());
                CommandFailures.textFor(failure.kind()).send(messages, sender);
            }
        }
    }

    private static boolean reachable(CommandSender sender) {
        return !(sender instanceof Player player) || player.isOnline();
    }

    private void send(CommandSender sender, List<Component> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (i == 0) {
                messages.sendComponent(sender, lines.get(i));
            } else {
                sender.sendMessage(lines.get(i));
            }
        }
    }

    private static String key(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId().toString() : "sender:" + sender.getName();
    }

    private SessionViews sessionViews() {
        PluginConfig current = config;
        return new SessionViews(messages, Formatters.of(current.display()), current.display().historyDays());
    }

    private AdminViews adminViews() {
        return new AdminViews(messages, Formatters.of(config.display()));
    }
}
