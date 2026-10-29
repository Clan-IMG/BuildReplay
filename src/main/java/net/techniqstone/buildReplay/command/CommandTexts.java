package net.techniqstone.buildReplay.command;

/**
 * Alle Texte der Befehle. Jeder Schlüssel steht mit genau diesem Fallback auch in der mitgelieferten
 * {@code messages.yml} (geprüft durch {@code ShippedMessagesFileTest}).
 */
public final class CommandTexts {

    // ------------------------------------------------------------------ allgemein
    public static final Text ONLY_PLAYERS = new Text("command.only-players",
            "&cDieser Befehl ist nur für Spieler.");
    public static final Text NO_PERMISSION = new Text("command.no-permission",
            "&cDazu fehlt dir die Berechtigung.");
    public static final Text UNKNOWN_SUBCOMMAND = new Text("command.unknown-subcommand",
            "&cUnbekannter Unterbefehl &e{input}&c. &7Hilfe: &e/buildreplay help");
    public static final Text COOLDOWN = new Text("command.cooldown",
            "&7Bitte warte einen Moment, bevor du den Befehl erneut verwendest.");
    public static final Text INVALID_PAGE = new Text("command.invalid-page",
            "&c'{input}' ist keine gültige Seitenzahl &7(1–{max})&c.");
    public static final Text INVALID_REF = new Text("command.invalid-ref",
            "&c'{input}' ist keine gültige Sitzungsreferenz. &7Beispiel: &e59dc83e5 &7(mind. {min} Zeichen)");
    public static final Text INVALID_SEGMENT = new Text("command.invalid-segment",
            "&c'{input}' ist keine gültige Abschnitt-ID.");
    public static final Text INVALID_PLAYER = new Text("command.invalid-player",
            "&c'{input}' ist kein gültiger Spielername.");
    public static final Text UNKNOWN_PLAYER = new Text("command.unknown-player",
            "&cDer Spieler &e{player} &cist auf diesem Server nicht bekannt.");
    public static final Text UNKNOWN_FLAG = new Text("command.unknown-flag",
            "&cUnbekannte Option &e{input}&c.");
    public static final Text USAGE_SESSIONS = new Text("command.usage.sessions",
            "&7Verwendung: &e/buildreplay sessions [Spieler] [Seite]");
    public static final Text USAGE_SESSIONS_CONSOLE = new Text("command.usage.sessions-console",
            "&7Verwendung in der Konsole: &e/buildreplay sessions <Spieler> [Seite]");
    public static final Text USAGE_SESSION = new Text("command.usage.session",
            "&7Verwendung: &e/buildreplay session <Referenz> [Spieler]");
    public static final Text USAGE_PLAY = new Text("command.usage.play",
            "&7Verwendung: &e/buildreplay play <Abschnitt-ID> &7oder &e/buildreplay play session <Referenz> [Spieler]");
    public static final Text USAGE_ADMIN = new Text("command.usage.admin",
            "&7Verwendung: &e/buildreplay admin <recordings|inspect|reload|cleanup|sync>");
    public static final Text USAGE_INSPECT = new Text("command.usage.inspect",
            "&7Verwendung: &e/buildreplay admin inspect <Spieler>");
    public static final Text USAGE_CLEANUP = new Text("command.usage.cleanup",
            "&7Verwendung: &e/buildreplay admin cleanup [--dry-run]");
    public static final Text WARNING_BULLET = new Text("command.warning-bullet", "&8• ");

    // ------------------------------------------------------------------ Hilfe
    public static final Text HELP_HEADER = new Text("help.header",
            "&6BuildReplay &8– &7Bau-Replays deiner Spielsitzungen");
    public static final Text HELP_SESSIONS = new Text("help.sessions",
            "&e/buildreplay sessions [Seite] &8– &7deine Sitzungen der letzten {days} Tage");
    public static final Text HELP_SESSIONS_OTHERS = new Text("help.sessions-others",
            "&e/buildreplay sessions <Spieler> [Seite] &8– &7Sitzungen eines anderen Spielers");
    public static final Text HELP_SESSION = new Text("help.session",
            "&e/buildreplay session <Referenz> &8– &7Replays einer Sitzung anzeigen");
    public static final Text HELP_PLAY = new Text("help.play",
            "&e/buildreplay play <Abschnitt-ID> &8– &7einen Abschnitt abspielen");
    public static final Text HELP_PLAY_SESSION = new Text("help.play-session",
            "&e/buildreplay play session <Referenz> &8– &7eine ganze Sitzung abspielen");
    public static final Text HELP_STOP = new Text("help.stop",
            "&e/buildreplay stop &8– &7Wiedergabe beenden");
    public static final Text HELP_STATUS = new Text("help.status",
            "&e/buildreplay status &8– &7Zustand von BuildReplay auf diesem Server");
    public static final Text HELP_ADMIN = new Text("help.admin",
            "&e/buildreplay admin <recordings|inspect|reload|cleanup|sync> &8– &7Verwaltung");
    public static final Text HELP_HOVER = new Text("help.hover", "&7Klicken, um den Befehl einzufügen");

    // ------------------------------------------------------------------ Sitzungsliste
    public static final Text SESSIONS_HEADER_OWN = new Text("sessions.header-own",
            "&6Deine Sitzungen &8(&7letzte {days} Tage&8)");
    public static final Text SESSIONS_HEADER_OTHER = new Text("sessions.header-other",
            "&6Sitzungen von &e{player} &8(&7letzte {days} Tage&8)");
    public static final Text SESSIONS_EMPTY = new Text("sessions.empty",
            "&7Keine Sitzungen in den letzten {days} Tagen.");
    public static final Text SESSIONS_PAGE_EMPTY = new Text("sessions.page-empty",
            "&7Seite {page} enthält keine Sitzungen.");
    public static final Text SESSIONS_BULLET = new Text("sessions.entry.bullet", "&8▪ ");
    public static final Text SESSIONS_SEPARATOR = new Text("sessions.entry.separator", " &8· ");
    public static final Text SESSIONS_DATE = new Text("sessions.entry.date", "&f{date}");
    public static final Text SESSIONS_ACTIVE = new Text("sessions.entry.active", "&aAktiv");
    public static final Text SESSIONS_DURATION = new Text("sessions.entry.duration", "&7{duration}");
    public static final Text SESSIONS_PROVISIONAL = new Text("sessions.entry.provisional", " &8(vorläufig)");
    public static final Text SESSIONS_SEGMENTS_ONE = new Text("sessions.entry.segments-one", "&e1 &7Replay");
    public static final Text SESSIONS_SEGMENTS_MANY = new Text("sessions.entry.segments-many",
            "&e{count} &7Replays");
    public static final Text SESSIONS_SEGMENTS_PARTLY = new Text("sessions.entry.segments-partly",
            " &8({available} abspielbar)");
    public static final Text SESSIONS_SEGMENTS_NONE_PLAYABLE = new Text("sessions.entry.segments-none-playable",
            " &c(nicht abspielbar)");
    public static final Text SESSIONS_NO_SEGMENTS = new Text("sessions.entry.no-segments", "&8keine Replays");
    public static final Text SESSIONS_SEGMENTS_UNKNOWN = new Text("sessions.entry.segments-unknown",
            "&8Replays unbekannt");
    public static final Text SESSIONS_SERVERS = new Text("sessions.entry.servers", "&7{servers}");
    public static final Text SESSIONS_HOVER = new Text("sessions.entry.hover",
            "&6Sitzung &e{ref}\n&7Login: &f{login}\n&7Logout: &f{logout}\n&7Dauer: &f{duration}\n"
                    + "&7Server: &f{servers}\n&7Replays: &f{segments} &8({available} abspielbar)\n\n&eKlicken für Details");
    public static final Text SESSIONS_HOVER_STILL_ONLINE = new Text("sessions.entry.hover-still-online",
            "&anoch online");
    public static final Text SESSIONS_HOVER_PROVISIONAL = new Text("sessions.entry.hover-provisional",
            "\n&8Vorläufige Sitzung: SimpleCloud war beim Login nicht erreichbar.");
    public static final Text SESSIONS_HOVER_UNKNOWN = new Text("sessions.entry.hover-unknown", "unbekannt");
    public static final Text NAV_PREVIOUS = new Text("sessions.nav.previous", "&e[« Zurück]");
    public static final Text NAV_PREVIOUS_DISABLED = new Text("sessions.nav.previous-disabled", "&8[« Zurück]");
    public static final Text NAV_NEXT = new Text("sessions.nav.next", "&e[Weiter »]");
    public static final Text NAV_NEXT_DISABLED = new Text("sessions.nav.next-disabled", "&8[Weiter »]");
    public static final Text NAV_PAGE = new Text("sessions.nav.page", " &7Seite &f{page}&7/&f{pages} ");
    public static final Text NAV_PAGE_OPEN = new Text("sessions.nav.page-open", " &7Seite &f{page} ");
    public static final Text NAV_HOVER = new Text("sessions.nav.hover", "&7Seite {page} anzeigen");

    // ------------------------------------------------------------------ Sitzungsdetails
    public static final Text SESSION_NOT_FOUND = new Text("session.not-found",
            "&cKeine Sitzung mit der Referenz &e{ref} &cgefunden.");
    public static final Text SESSION_AMBIGUOUS = new Text("session.ambiguous",
            "&eDie Referenz &f{ref} &epasst auf mehrere Sitzungen – bitte genauer angeben:");
    public static final Text SESSION_AMBIGUOUS_CANDIDATE = new Text("session.ambiguous-candidate", "&8▪ &e{ref}");
    public static final Text SESSION_AMBIGUOUS_HOVER = new Text("session.ambiguous-hover",
            "&7Klicken für Details");
    public static final Text SESSION_HEADER = new Text("session.header",
            "&6Sitzung &e{ref} &8· &f{date} &8· {duration}{provisional}");
    public static final Text SESSION_SERVERS = new Text("session.servers", "&7Server: &f{servers}");
    public static final Text SESSION_ROUTE = new Text("session.route", "&7Verlauf: &f{route}");
    public static final Text SESSION_SEGMENTS_HEADER = new Text("session.segments-header",
            "&6Replay-Abschnitte &8({count})&6:");
    public static final Text SESSION_NO_SEGMENTS = new Text("session.no-segments",
            "&7In dieser Sitzung wurden keine Replay-Abschnitte gespeichert.");
    public static final Text SESSION_SEGMENT_LINE = new Text("session.segment.line",
            "&8{index}. &f{from}&7–&f{to} &8(&7{duration}&8) &8· &7{server} &8· &7{actions} Aktionen");
    public static final Text SESSION_SEGMENT_HOVER = new Text("session.segment.hover",
            "&6Abschnitt &e#{id}\n&7Zeitraum: &f{from} – {to}\n&7Dauer: &f{duration}\n"
                    + "&7Server: &f{server} &8/ &7Welt: &f{world}\n&7Vorlauf / Nachlauf: &f{preroll} / {postroll}\n"
                    + "&7Aktionen: &f{summary}\n&7Status: {status}");
    public static final Text SESSION_SEGMENT_PLAY = new Text("session.segment.play", "&a[▶ Abspielen]");
    public static final Text SESSION_SEGMENT_PLAY_HOVER = new Text("session.segment.play-hover",
            "&7Abschnitt &e#{id} &7abspielen");
    public static final Text SESSION_SEGMENT_UNPLAYABLE = new Text("session.segment.unplayable", "&8[{status}&8]");
    public static final Text SESSION_PLAY_ALL = new Text("session.play-all", "&a[▶ Ganze Sitzung]");
    public static final Text SESSION_PLAY_ALL_HOVER = new Text("session.play-all-hover",
            "&7Alle {count} abspielbaren Abschnitte nacheinander abspielen");
    public static final Text SESSION_BACK = new Text("session.back", "&e[« Zur Übersicht]");
    public static final Text SESSION_BACK_HOVER = new Text("session.back-hover", "&7Zurück zur Sitzungsliste");
    public static final Text STATUS_AVAILABLE = new Text("session.segment-status.available", "&aVerfügbar");
    public static final Text STATUS_SAVING = new Text("session.segment-status.saving", "&eWird gespeichert");
    public static final Text STATUS_MISSING = new Text("session.segment-status.missing", "&cFehlt");
    public static final Text STATUS_CORRUPT = new Text("session.segment-status.corrupt", "&cBeschädigt");
    public static final Text STATUS_DELETED = new Text("session.segment-status.deleted", "&8Gelöscht");
    public static final Text STATUS_FAILED = new Text("session.segment-status.failed", "&cFehlgeschlagen");

    // ------------------------------------------------------------------ Status
    public static final Text STATUS_HEADER = new Text("status.header",
            "&6BuildReplay-Status &8· &f{server} &8(&7{instance}&8) &8· &7v{version}");
    public static final Text STATUS_SIMPLECLOUD = new Text("status.simplecloud",
            "&7SimpleCloud: {state} &8· &7Anfragen &f{requests}&7, Fehler &f{failures} &8· &7zuletzt erfolgreich &f{last}");
    public static final Text STATUS_JAVA_API = new Text("status.java-api", "&7SimpleCloud-Java-API: {state}");
    public static final Text STATUS_DATABASE = new Text("status.database",
            "&7MariaDB: {state} &8· &7Warteschlange &f{queued}&7, Outbox &f{outbox}&7, verworfen &f{dropped} "
                    + "&8· &7Schema &fv{schema}");
    public static final Text STATUS_REDIS = new Text("status.redis",
            "&7Redis: {state} &8· &7zuletzt erfolgreich &f{last}");
    public static final Text STATUS_ENGINE = new Text("status.engine",
            "&7AdvancedReplay &f{version}&7: {state} &8· &7Speicher &f{backend} &8· &7max. Länge &f{maxLength} "
                    + "&8· &7Blockänderungen {blocks}");
    public static final Text STATUS_RECORDINGS = new Text("status.recordings",
            "&7Aufnahmen: &f{active} aktiv &8· &7gestartet &f{started}&7, Abschnitte &f{saved}&7, verworfen "
                    + "&f{discarded}&7, Fehler &f{failures}&7, ausstehend &f{pending}");
    public static final Text STATUS_SESSIONS = new Text("status.sessions",
            "&7Sitzungen: &f{local} Spieler &8· &7aufgelöst &f{resolved}&7, vorläufig &f{provisional} "
                    + "&8· &7SimpleCloud-Abfragen &f{lookups} &8(&7{lookupFailures} fehlgeschlagen&8) &8· &7Leader {leader}");
    public static final Text STATUS_WARNINGS = new Text("status.warnings", "&eWarnungen:");
    public static final Text STATUS_NO_WARNINGS = new Text("status.no-warnings", "&aKeine Warnungen.");
    public static final Text STATUS_LAST_ERROR_HOVER = new Text("status.last-error-hover",
            "&7Letzter Fehler: &f{error}");
    public static final Text STATE_OK = new Text("status.state.ok", "&aOK");
    public static final Text STATE_DOWN = new Text("status.state.down", "&cnicht erreichbar");
    public static final Text STATE_DISABLED = new Text("status.state.disabled", "&8deaktiviert");
    public static final Text STATE_FALLBACK = new Text("status.state.fallback", "&elokaler Ersatz");
    public static final Text STATE_NOT_CONFIGURED = new Text("status.state.not-configured", "&cnicht konfiguriert");
    public static final Text STATE_CIRCUIT_OPEN = new Text("status.state.circuit-open",
            "&cgesperrt (Circuit-Breaker)");
    public static final Text STATE_NOT_INSTALLED = new Text("status.state.not-installed", "&cnicht installiert");
    public static final Text STATE_UNAVAILABLE = new Text("status.state.unavailable", "&cnicht verfügbar");
    public static final Text STATE_INCOMPATIBLE = new Text("status.state.incompatible",
            "&eAufnahme OK, Wiedergabe inkompatibel");
    public static final Text STATE_CLIENT_SIDE = new Text("status.state.client-side", "&anur clientseitig");
    public static final Text STATE_REAL_WORLD = new Text("status.state.real-world", "&cverändern die Welt");
    public static final Text STATE_YES = new Text("status.state.answer-yes", "&aja");
    public static final Text STATE_NO = new Text("status.state.answer-no", "&7nein");
    public static final Text STATE_NEVER = new Text("status.state.never", "&8nie");

    // ------------------------------------------------------------------ Verwaltung
    public static final Text ADMIN_RECORDINGS_HEADER = new Text("admin.recordings.header",
            "&6Laufende Aufnahmen auf diesem Server &8({count})");
    public static final Text ADMIN_RECORDINGS_EMPTY = new Text("admin.recordings.empty",
            "&7Auf diesem Server läuft gerade keine Aufnahme.");
    public static final Text ADMIN_RECORDINGS_LINE = new Text("admin.recordings.line",
            "&8▪ &e{player} &8· &7{world} &8· &7seit &f{since} &8(&7{duration}&8) &8· &f{actions} &7relevante Aktionen{afk}");
    public static final Text ADMIN_RECORDINGS_AFK = new Text("admin.recordings.afk", " &8· &eAFK");
    public static final Text ADMIN_RECORDINGS_HOVER = new Text("admin.recordings.hover",
            "&7Rohaufnahme: &f{raw}\n&7Tick: &f{tick}\n&7Sitzung: &f{session}\n\n&eKlicken für die Diagnose");
    public static final Text ADMIN_RECORDINGS_STATS = new Text("admin.recordings.stats",
            "&7Seit dem Start: gestartet &f{started}&7, Abschnitte &f{saved}&7, verworfen &f{discarded}&7, "
                    + "Fehler &f{failures}&7, ausstehend &f{pending}");
    public static final Text ADMIN_INSPECT_HEADER = new Text("admin.inspect.header",
            "&6Aufnahme-Diagnose für &e{player}");
    public static final Text ADMIN_INSPECT_NONE = new Text("admin.inspect.none",
            "&7Keine laufende Aufnahme von &e{player} &7auf diesem Server.");
    public static final Text ADMIN_INSPECT_DETAILS = new Text("admin.inspect.details",
            "&7Rohaufnahme: &f{raw}\n&7Welt: &f{world}\n&7Gestartet: &f{since} &8(&7{duration}&8)\n"
                    + "&7Tick: &f{tick}\n&7Relevante Aktionen: &f{actions}\n&7AFK: {afk}\n&7Sitzung: &f{session}");
    public static final Text ADMIN_INSPECT_SESSIONS = new Text("admin.inspect.sessions-button",
            "&e[Sitzungen anzeigen]");
    public static final Text ADMIN_INSPECT_SESSIONS_HOVER = new Text("admin.inspect.sessions-hover",
            "&7/buildreplay sessions {player}");
    public static final Text ADMIN_RELOAD_DONE = new Text("admin.reload.done",
            "&aKonfiguration und Texte wurden neu geladen.");
    public static final Text ADMIN_RELOAD_WARNINGS = new Text("admin.reload.warnings",
            "&e{count} Konfigurationswarnung(en):");
    public static final Text ADMIN_CLEANUP_STARTED = new Text("admin.cleanup.started", "&7Aufräumen läuft …");
    public static final Text ADMIN_CLEANUP_DRY_RUN_STARTED = new Text("admin.cleanup.dry-run-started",
            "&7Probelauf läuft – es wird nichts gelöscht …");
    public static final Text ADMIN_CLEANUP_DONE = new Text("admin.cleanup.done",
            "&aAufräumen abgeschlossen: &f{deleted} &agelöscht, &f{failed} &afehlgeschlagen &8(&7{examined} geprüft&8)");
    public static final Text ADMIN_CLEANUP_DRY_RUN = new Text("admin.cleanup.dry-run",
            "&eProbelauf: &f{deleted} &ewürden gelöscht &8(&7{examined} geprüft, nichts verändert&8)");
    public static final Text ADMIN_SYNC_STARTED = new Text("admin.sync.started",
            "&7Outbox und Sitzungsabgleich werden ausgeführt …");
    public static final Text ADMIN_SYNC_DONE = new Text("admin.sync.done",
            "&aOutbox geleert und Sitzungsabgleich mit SimpleCloud ausgeführt.");

    // ------------------------------------------------------------------ Fehler
    public static final Text ERROR_STORAGE = new Text("error.storage-unavailable",
            "&cDatenbank derzeit nicht erreichbar. Bitte versuche es später erneut.");
    public static final Text ERROR_SIMPLECLOUD = new Text("error.simplecloud-unavailable",
            "&cSimpleCloud nicht erreichbar. Bitte versuche es später erneut.");
    public static final Text ERROR_TIMEOUT = new Text("error.timeout",
            "&cDie Anfrage hat zu lange gedauert. Bitte versuche es später erneut.");
    public static final Text ERROR_BUSY = new Text("error.busy",
            "&cBuildReplay ist gerade ausgelastet. Bitte versuche es gleich erneut.");
    public static final Text ERROR_MAINTENANCE = new Text("error.maintenance", "&c{reason}");
    public static final Text ERROR_GENERIC = new Text("error.generic",
            "&cEin interner Fehler ist aufgetreten. Details stehen in der Serverkonsole.");

    private CommandTexts() {
    }
}
