package net.techniqstone.buildReplay.command;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.regex.Pattern;
import net.techniqstone.buildReplay.command.CommandRequest.Invalid;

/**
 * Reine Zerlegung der Befehlsargumente von {@code /buildreplay} (ohne Bukkit, daher direkt testbar). Seiten werden
 * 1-basiert eingegeben und 0-basiert weitergegeben. Ein einzelnes Argument nach {@code sessions} gilt als
 * Seitenzahl, wenn es nur aus Ziffern besteht, sonst als Spielername ({@code sessions <Spieler> 1} für Spieler mit
 * reinen Ziffernnamen).
 */
public final class CommandArguments {

    public static final int MAX_PAGE = 10_000;
    public static final int MIN_REF_LENGTH = 3;
    public static final int MAX_REF_LENGTH = 64;

    /** Unterbefehle in der Reihenfolge der Hilfe/Tab-Vervollständigung. */
    public static final List<String> SUBCOMMANDS = List.of("help", "sessions", "session", "play", "stop", "status",
            "admin");
    public static final List<String> ADMIN_SUBCOMMANDS = List.of("recordings", "inspect", "reload", "cleanup", "sync");
    public static final String DRY_RUN_FLAG = "--dry-run";

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("help", "help"), Map.entry("hilfe", "help"), Map.entry("?", "help"),
            Map.entry("sessions", "sessions"), Map.entry("sitzungen", "sessions"), Map.entry("list", "sessions"),
            Map.entry("session", "session"), Map.entry("sitzung", "session"), Map.entry("details", "session"),
            Map.entry("play", "play"), Map.entry("abspielen", "play"), Map.entry("watch", "play"),
            Map.entry("stop", "stop"), Map.entry("stopp", "stop"),
            Map.entry("status", "status"),
            Map.entry("admin", "admin"));
    private static final Map<String, String> ADMIN_ALIASES = Map.ofEntries(
            Map.entry("recordings", "recordings"), Map.entry("aufnahmen", "recordings"),
            Map.entry("inspect", "inspect"), Map.entry("diagnose", "inspect"),
            Map.entry("reload", "reload"),
            Map.entry("cleanup", "cleanup"), Map.entry("aufraeumen", "cleanup"), Map.entry("aufräumen", "cleanup"),
            Map.entry("sync", "sync"), Map.entry("abgleich", "sync"));
    private static final List<String> DRY_RUN_FLAGS = List.of(DRY_RUN_FLAG, "--dryrun", "-n", "dry-run", "dryrun",
            "--probe", "probe");
    private static final List<String> SESSION_KEYWORDS = List.of("session", "sitzung");

    /** Java-Namen (3–16 Zeichen, hier großzügig ab 1) und optional ein Floodgate-Präfix ({@code .} oder {@code *}). */
    private static final Pattern PLAYER_NAME = Pattern.compile("^[.*]?[A-Za-z0-9_]{1,16}$");
    private static final Pattern NUMBER = Pattern.compile("^[+-]?[0-9]+$");
    private static final Pattern REF = Pattern.compile("^[0-9a-z][0-9a-z-]*$");
    private static final Pattern SEGMENT_ID = Pattern.compile("^[0-9]{1,18}$");

    private CommandArguments() {
    }

    public static CommandRequest parse(String[] args) {
        if (args.length == 0) {
            return new CommandRequest.Help();
        }
        String sub = subcommand(args[0]);
        return switch (sub) {
            case "help" -> new CommandRequest.Help();
            case "sessions" -> parseSessions(args);
            case "session" -> parseSession(args);
            case "play" -> parsePlay(args);
            case "stop" -> new CommandRequest.Stop();
            case "status" -> new CommandRequest.Status();
            case "admin" -> parseAdmin(args);
            default -> Invalid.of(CommandTexts.UNKNOWN_SUBCOMMAND, null, "input", args[0]);
        };
    }

    /** Kanonischer Name eines Unterbefehls (deutsche Aliasse werden übersetzt) oder die Eingabe in Kleinbuchstaben. */
    public static String subcommand(String raw) {
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        return ALIASES.getOrDefault(lower, lower);
    }

    public static String adminSubcommand(String raw) {
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        return ADMIN_ALIASES.getOrDefault(lower, lower);
    }

    /** Ob die Eingabe wie eine (ggf. ungültige) Zahl aussieht und daher als Seitenzahl gemeint ist. */
    public static boolean looksLikeNumber(String input) {
        return input != null && NUMBER.matcher(input.trim()).matches();
    }

    /** 1-basierte Seitenzahl ({@code 1..MAX_PAGE}) → 0-basierter Index. */
    public static OptionalInt parsePage(String input) {
        if (input == null) {
            return OptionalInt.empty();
        }
        String trimmed = input.trim();
        if (!trimmed.matches("^[0-9]{1,6}$")) {
            return OptionalInt.empty();
        }
        int page = Integer.parseInt(trimmed);
        if (page < 1 || page > MAX_PAGE) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(page - 1);
    }

    /**
     * Sitzungsreferenz normalisieren: Kleinbuchstaben, {@value #MIN_REF_LENGTH}–{@value #MAX_REF_LENGTH} Zeichen aus
     * {@code [0-9a-z-]}, beginnend mit Buchstabe/Ziffer. Üblich ist die 8-stellige Kurzreferenz aus der Liste
     * ({@code NetworkSession#shortRef}); längere Präfixe bzw. ganze SimpleCloud-IDs werden unverändert (mit
     * Bindestrichen) weitergegeben, die Zuordnung übernimmt {@code SessionQueries}.
     */
    public static Optional<String> normalizeRef(String input) {
        if (input == null) {
            return Optional.empty();
        }
        String normalized = input.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() < MIN_REF_LENGTH || normalized.length() > MAX_REF_LENGTH
                || !REF.matcher(normalized).matches()) {
            return Optional.empty();
        }
        return Optional.of(normalized);
    }

    public static boolean isValidPlayerName(String input) {
        return input != null && PLAYER_NAME.matcher(input).matches();
    }

    /** Positive Abschnitt-ID. */
    public static OptionalLong parseSegmentId(String input) {
        if (input == null) {
            return OptionalLong.empty();
        }
        String trimmed = input.trim();
        if (trimmed.startsWith("#")) {
            trimmed = trimmed.substring(1);
        }
        if (!SEGMENT_ID.matcher(trimmed).matches()) {
            return OptionalLong.empty();
        }
        long id = Long.parseLong(trimmed);
        return id > 0 ? OptionalLong.of(id) : OptionalLong.empty();
    }

    public static boolean isDryRunFlag(String input) {
        return input != null && DRY_RUN_FLAGS.contains(input.trim().toLowerCase(Locale.ROOT));
    }

    public static boolean isSessionKeyword(String input) {
        return input != null && SESSION_KEYWORDS.contains(input.trim().toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------ Unterbefehle

    private static CommandRequest parseSessions(String[] args) {
        return switch (args.length) {
            case 1 -> new CommandRequest.ListSessions(null, 0);
            case 2 -> {
                String arg = args[1];
                if (looksLikeNumber(arg)) {
                    yield pageOrInvalid(arg, null);
                }
                yield isValidPlayerName(arg)
                        ? new CommandRequest.ListSessions(arg, 0)
                        : Invalid.of(CommandTexts.INVALID_PLAYER, CommandTexts.USAGE_SESSIONS, "input", arg);
            }
            case 3 -> {
                if (!isValidPlayerName(args[1])) {
                    yield Invalid.of(CommandTexts.INVALID_PLAYER, CommandTexts.USAGE_SESSIONS, "input", args[1]);
                }
                yield pageOrInvalid(args[2], args[1]);
            }
            default -> Invalid.of(CommandTexts.USAGE_SESSIONS, null);
        };
    }

    private static CommandRequest pageOrInvalid(String input, String playerName) {
        OptionalInt page = parsePage(input);
        if (page.isEmpty()) {
            return Invalid.of(CommandTexts.INVALID_PAGE, CommandTexts.USAGE_SESSIONS, "input", input,
                    "max", String.valueOf(MAX_PAGE));
        }
        return new CommandRequest.ListSessions(playerName, page.getAsInt());
    }

    private static CommandRequest parseSession(String[] args) {
        if (args.length < 2 || args.length > 3) {
            return Invalid.of(CommandTexts.USAGE_SESSION, null);
        }
        Optional<String> ref = normalizeRef(args[1]);
        if (ref.isEmpty()) {
            return invalidRef(args[1], CommandTexts.USAGE_SESSION);
        }
        String player = args.length == 3 ? args[2] : null;
        if (player != null && !isValidPlayerName(player)) {
            return Invalid.of(CommandTexts.INVALID_PLAYER, CommandTexts.USAGE_SESSION, "input", player);
        }
        return new CommandRequest.ShowSession(ref.get(), player);
    }

    private static CommandRequest parsePlay(String[] args) {
        if (args.length < 2) {
            return Invalid.of(CommandTexts.USAGE_PLAY, null);
        }
        if (isSessionKeyword(args[1])) {
            if (args.length < 3 || args.length > 4) {
                return Invalid.of(CommandTexts.USAGE_PLAY, null);
            }
            Optional<String> ref = normalizeRef(args[2]);
            if (ref.isEmpty()) {
                return invalidRef(args[2], CommandTexts.USAGE_PLAY);
            }
            String player = args.length == 4 ? args[3] : null;
            if (player != null && !isValidPlayerName(player)) {
                return Invalid.of(CommandTexts.INVALID_PLAYER, CommandTexts.USAGE_PLAY, "input", player);
            }
            return new CommandRequest.PlaySession(ref.get(), player);
        }
        if (args.length > 2) {
            return Invalid.of(CommandTexts.USAGE_PLAY, null);
        }
        OptionalLong id = parseSegmentId(args[1]);
        if (id.isEmpty()) {
            return Invalid.of(CommandTexts.INVALID_SEGMENT, CommandTexts.USAGE_PLAY, "input", args[1]);
        }
        return new CommandRequest.PlaySegment(id.getAsLong());
    }

    private static CommandRequest parseAdmin(String[] args) {
        if (args.length < 2) {
            return Invalid.of(CommandTexts.USAGE_ADMIN, null);
        }
        String sub = adminSubcommand(args[1]);
        return switch (sub) {
            case "recordings" -> new CommandRequest.AdminRecordings();
            case "reload" -> new CommandRequest.AdminReload();
            case "sync" -> new CommandRequest.AdminSync();
            case "inspect" -> {
                if (args.length != 3) {
                    yield Invalid.of(CommandTexts.USAGE_INSPECT, null);
                }
                yield isValidPlayerName(args[2])
                        ? new CommandRequest.AdminInspect(args[2])
                        : Invalid.of(CommandTexts.INVALID_PLAYER, CommandTexts.USAGE_INSPECT, "input", args[2]);
            }
            case "cleanup" -> {
                boolean dryRun = false;
                for (int i = 2; i < args.length; i++) {
                    if (isDryRunFlag(args[i])) {
                        dryRun = true;
                    } else {
                        yield Invalid.of(CommandTexts.UNKNOWN_FLAG, CommandTexts.USAGE_CLEANUP, "input", args[i]);
                    }
                }
                yield new CommandRequest.AdminCleanup(dryRun);
            }
            default -> Invalid.of(CommandTexts.UNKNOWN_SUBCOMMAND, CommandTexts.USAGE_ADMIN, "input", args[1]);
        };
    }

    private static CommandRequest invalidRef(String input, Text usage) {
        return Invalid.of(CommandTexts.INVALID_REF, usage, "input", input, "min", String.valueOf(MIN_REF_LENGTH));
    }
}
