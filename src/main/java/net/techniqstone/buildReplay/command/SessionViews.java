package net.techniqstone.buildReplay.command;

import static net.techniqstone.buildReplay.command.Formatters.plain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.techniqstone.buildReplay.config.Messages;
import net.techniqstone.buildReplay.session.SessionDetails;
import net.techniqstone.buildReplay.session.SessionListEntry;
import net.techniqstone.buildReplay.session.SessionListPage;
import net.techniqstone.buildReplay.storage.model.SegmentRow;
import net.techniqstone.buildReplay.storage.model.SegmentStatus;
import org.jspecify.annotations.Nullable;

/**
 * Baut die Chat-Ausgaben für {@code /buildreplay sessions} und {@code /buildreplay session} (Adventure-Komponenten,
 * ohne Bukkit-Zugriff). Die erste Zeile ist die Überschrift (wird mit Präfix gesendet). {@code otherPlayer} ist der
 * Name des angezeigten Spielers, wenn es nicht die eigenen Sitzungen sind - er wird an die Klick-Befehle angehängt.
 */
public final class SessionViews {

    public static final String ROOT = "/buildreplay";

    private final Messages messages;
    private final Formatters fmt;
    private final int historyDays;

    public SessionViews(Messages messages, Formatters formatters, int historyDays) {
        this.messages = messages;
        this.fmt = formatters;
        this.historyDays = historyDays;
    }

    // ------------------------------------------------------------------ Klick-Befehle

    public static String sessionsCommand(@Nullable String player, int page) {
        StringBuilder command = new StringBuilder(ROOT).append(" sessions");
        if (player != null) {
            command.append(' ').append(player);
        }
        // Spieler mit reinem Ziffernnamen würden sonst als Seitenzahl gelesen
        if (page > 0 || (player != null && CommandArguments.looksLikeNumber(player))) {
            command.append(' ').append(page + 1);
        }
        return command.toString();
    }

    public static String sessionCommand(String ref, @Nullable String player) {
        return ROOT + " session " + ref + (player == null ? "" : " " + player);
    }

    public static String playSegmentCommand(long segmentId) {
        return ROOT + " play " + segmentId;
    }

    public static String playSessionCommand(String ref, @Nullable String player) {
        return ROOT + " play session " + ref + (player == null ? "" : " " + player);
    }

    // ------------------------------------------------------------------ Liste

    public List<Component> sessionList(SessionListPage page, @Nullable String otherPlayer, Instant now) {
        List<Component> lines = new ArrayList<>();
        String days = String.valueOf(historyDays);
        lines.add(otherPlayer == null
                ? CommandTexts.SESSIONS_HEADER_OWN.render(messages, "days", days)
                : CommandTexts.SESSIONS_HEADER_OTHER.render(messages, "player", plain(otherPlayer), "days", days));

        boolean segmentsUnknown = page.source() == SessionListPage.Source.SIMPLECLOUD_ONLY;
        if (page.entries().isEmpty()) {
            lines.add(page.page() == 0
                    ? CommandTexts.SESSIONS_EMPTY.render(messages, "days", days)
                    : CommandTexts.SESSIONS_PAGE_EMPTY.render(messages, "page", String.valueOf(page.page() + 1)));
        }
        for (SessionListEntry entry : page.entries()) {
            lines.add(entryLine(entry, otherPlayer, segmentsUnknown, now));
        }
        if (page.page() > 0 || page.hasNext()) {
            lines.add(navigation(page, otherPlayer));
        }
        addWarnings(lines, page.warnings());
        return lines;
    }

    /** Anzahl Seiten aus {@code totalInWindow}, mindestens so viele wie durch {@code page}/{@code hasNext} belegt; -1 = unbekannt. */
    public static int pageCount(SessionListPage page) {
        int minimum = page.page() + 1 + (page.hasNext() ? 1 : 0);
        if (page.totalInWindow() <= 0) {
            return page.entries().isEmpty() && page.page() == 0 ? 1 : (page.hasNext() ? -1 : minimum);
        }
        int size = Math.max(1, page.pageSize());
        int pages = (page.totalInWindow() + size - 1) / size;
        return Math.max(pages, minimum);
    }

    private Component entryLine(SessionListEntry entry, @Nullable String otherPlayer, boolean segmentsUnknown,
                                Instant now) {
        Component separator = CommandTexts.SESSIONS_SEPARATOR.render(messages);
        Component line = Component.empty()
                .append(CommandTexts.SESSIONS_BULLET.render(messages))
                .append(CommandTexts.SESSIONS_DATE.render(messages, "date", fmt.dateTime(entry.loginTime())))
                .append(separator)
                .append(entry.active()
                        ? CommandTexts.SESSIONS_ACTIVE.render(messages)
                        : CommandTexts.SESSIONS_DURATION.render(messages, "duration",
                                Formatters.sessionDuration(entry.durationSeconds())));
        if (entry.provisional()) {
            line = line.append(CommandTexts.SESSIONS_PROVISIONAL.render(messages));
        }
        line = line.append(separator)
                .append(segmentsPart(entry, segmentsUnknown))
                .append(separator)
                .append(CommandTexts.SESSIONS_SERVERS.render(messages, "servers", plain(Formatters.servers(entry.servers()))));
        return line
                .clickEvent(ClickEvent.runCommand(sessionCommand(entry.ref(), otherPlayer)))
                .hoverEvent(HoverEvent.showText(entryHover(entry, segmentsUnknown, now)));
    }

    private Component segmentsPart(SessionListEntry entry, boolean segmentsUnknown) {
        if (segmentsUnknown) {
            return CommandTexts.SESSIONS_SEGMENTS_UNKNOWN.render(messages);
        }
        if (entry.segments() <= 0) {
            return CommandTexts.SESSIONS_NO_SEGMENTS.render(messages);
        }
        Component count = entry.segments() == 1
                ? CommandTexts.SESSIONS_SEGMENTS_ONE.render(messages)
                : CommandTexts.SESSIONS_SEGMENTS_MANY.render(messages, "count", String.valueOf(entry.segments()));
        if (entry.availableSegments() <= 0) {
            return count.append(CommandTexts.SESSIONS_SEGMENTS_NONE_PLAYABLE.render(messages));
        }
        if (entry.availableSegments() < entry.segments()) {
            return count.append(CommandTexts.SESSIONS_SEGMENTS_PARTLY.render(messages, "available",
                    String.valueOf(entry.availableSegments())));
        }
        return count;
    }

    private Component entryHover(SessionListEntry entry, boolean segmentsUnknown, Instant now) {
        long seconds = entry.durationSeconds();
        if (entry.active()) {
            seconds = Math.max(seconds, Duration.between(entry.loginTime(), now).toSeconds());
        }
        String unknown = CommandTexts.SESSIONS_HOVER_UNKNOWN.raw(messages);
        String hover = CommandTexts.SESSIONS_HOVER.raw(messages,
                "ref", plain(entry.ref()),
                "login", fmt.dateTime(entry.loginTime()),
                "logout", entry.active()
                        ? CommandTexts.SESSIONS_HOVER_STILL_ONLINE.raw(messages)
                        : fmt.dateTime(entry.logoutTime()),
                "duration", Formatters.sessionDuration(seconds),
                "servers", plain(Formatters.servers(entry.servers())),
                "segments", segmentsUnknown ? unknown : String.valueOf(entry.segments()),
                "available", segmentsUnknown ? unknown : String.valueOf(entry.availableSegments()));
        if (entry.provisional()) {
            hover += CommandTexts.SESSIONS_HOVER_PROVISIONAL.raw(messages);
        }
        return Messages.legacy(hover);
    }

    private Component navigation(SessionListPage page, @Nullable String otherPlayer) {
        int current = page.page();
        int pages = pageCount(page);
        Component previous = current > 0
                ? CommandTexts.NAV_PREVIOUS.render(messages)
                        .clickEvent(ClickEvent.runCommand(sessionsCommand(otherPlayer, current - 1)))
                        .hoverEvent(HoverEvent.showText(CommandTexts.NAV_HOVER.render(messages, "page",
                                String.valueOf(current))))
                : CommandTexts.NAV_PREVIOUS_DISABLED.render(messages);
        Component middle = pages > 0
                ? CommandTexts.NAV_PAGE.render(messages, "page", String.valueOf(current + 1), "pages", String.valueOf(pages))
                : CommandTexts.NAV_PAGE_OPEN.render(messages, "page", String.valueOf(current + 1));
        Component next = page.hasNext()
                ? CommandTexts.NAV_NEXT.render(messages)
                        .clickEvent(ClickEvent.runCommand(sessionsCommand(otherPlayer, current + 1)))
                        .hoverEvent(HoverEvent.showText(CommandTexts.NAV_HOVER.render(messages, "page",
                                String.valueOf(current + 2))))
                : CommandTexts.NAV_NEXT_DISABLED.render(messages);
        return Component.empty().append(previous).append(middle).append(next);
    }

    // ------------------------------------------------------------------ Details

    /** {@code canPlay}: Abspiel-Schaltflächen anzeigen (Absender hat {@code buildreplay.play}). */
    public List<Component> sessionDetails(SessionDetails details, @Nullable String otherPlayer, boolean canPlay) {
        SessionListEntry summary = details.summary();
        List<Component> lines = new ArrayList<>();
        String duration = summary.active()
                ? CommandTexts.SESSIONS_ACTIVE.raw(messages)
                : CommandTexts.SESSIONS_DURATION.raw(messages, "duration",
                        Formatters.sessionDuration(summary.durationSeconds()));
        lines.add(CommandTexts.SESSION_HEADER.render(messages,
                "ref", plain(summary.ref()),
                "date", fmt.dateTime(summary.loginTime()),
                "duration", duration,
                "provisional", summary.provisional() ? CommandTexts.SESSIONS_PROVISIONAL.raw(messages) : ""));

        Set<String> servers = new LinkedHashSet<>(summary.servers());
        details.segments().forEach(segment -> servers.add(segment.serverName()));
        details.visits().forEach(visit -> servers.add(visit.serverName()));
        lines.add(CommandTexts.SESSION_SERVERS.render(messages, "servers", plain(Formatters.servers(servers))));
        if (details.visits().size() > 1) {
            lines.add(CommandTexts.SESSION_ROUTE.render(messages, "route", plain(Formatters.route(details.visits()))));
        }

        List<SegmentRow> segments = details.segments();
        if (segments.isEmpty()) {
            lines.add(CommandTexts.SESSION_NO_SEGMENTS.render(messages));
        } else {
            lines.add(CommandTexts.SESSION_SEGMENTS_HEADER.render(messages, "count", String.valueOf(segments.size())));
            for (int i = 0; i < segments.size(); i++) {
                lines.add(segmentLine(i + 1, segments.get(i), summary.loginTime(), canPlay));
            }
        }

        long playable = segments.stream().filter(segment -> segment.status().playable()).count();
        Component footer = Component.empty();
        if (playable > 0 && canPlay) {
            footer = footer.append(CommandTexts.SESSION_PLAY_ALL.render(messages)
                            .clickEvent(ClickEvent.runCommand(playSessionCommand(summary.ref(), otherPlayer)))
                            .hoverEvent(HoverEvent.showText(CommandTexts.SESSION_PLAY_ALL_HOVER.render(messages,
                                    "count", String.valueOf(playable)))))
                    .append(Component.text("  "));
        }
        footer = footer.append(CommandTexts.SESSION_BACK.render(messages)
                .clickEvent(ClickEvent.runCommand(sessionsCommand(otherPlayer, 0)))
                .hoverEvent(HoverEvent.showText(CommandTexts.SESSION_BACK_HOVER.render(messages))));
        lines.add(footer);
        addWarnings(lines, details.warnings());
        return lines;
    }

    private Component segmentLine(int index, SegmentRow segment, @Nullable Instant sessionStart, boolean canPlay) {
        Instant reference = sessionStart == null ? segment.startedAt() : sessionStart;
        String status = statusLabel(segment.status());
        Component hover = CommandTexts.SESSION_SEGMENT_HOVER.render(messages,
                "id", String.valueOf(segment.id()),
                "from", fmt.dateTime(segment.startedAt()),
                "to", fmt.dateTime(segment.endedAt()),
                "duration", Formatters.clipDuration(segment.durationSeconds()),
                "server", plain(segment.serverName()),
                "world", plain(segment.worldName() == null ? Formatters.NONE : segment.worldName()),
                "preroll", Formatters.roll(segment.preRollMillis()),
                "postroll", Formatters.roll(segment.postRollMillis()),
                "summary", plain(Formatters.actionSummary(segment.actionSummary())),
                "status", status);
        Component text = CommandTexts.SESSION_SEGMENT_LINE.render(messages,
                        "index", String.valueOf(index),
                        "from", fmt.timeOnDay(segment.startedAt(), reference),
                        "to", fmt.timeOnDay(segment.endedAt(), reference),
                        "duration", Formatters.clipDuration(segment.durationSeconds()),
                        "server", plain(segment.serverName()),
                        "actions", String.valueOf(segment.relevantActions()))
                .hoverEvent(HoverEvent.showText(hover));
        if (segment.status().playable() && !canPlay) {
            return text;
        }
        Component button = segment.status().playable()
                ? CommandTexts.SESSION_SEGMENT_PLAY.render(messages)
                        .clickEvent(ClickEvent.runCommand(playSegmentCommand(segment.id())))
                        .hoverEvent(HoverEvent.showText(CommandTexts.SESSION_SEGMENT_PLAY_HOVER.render(messages,
                                "id", String.valueOf(segment.id()))))
                : CommandTexts.SESSION_SEGMENT_UNPLAYABLE.render(messages, "status", status)
                        .hoverEvent(HoverEvent.showText(hover));
        return Component.empty().append(text).append(Component.space()).append(button);
    }

    /** Statusbezeichnung mit Farbcode (als Rohtext, zum Einsetzen in andere Texte). */
    public String statusLabel(SegmentStatus status) {
        Text text = switch (status) {
            case AVAILABLE -> CommandTexts.STATUS_AVAILABLE;
            case SAVING -> CommandTexts.STATUS_SAVING;
            case MISSING -> CommandTexts.STATUS_MISSING;
            case CORRUPT -> CommandTexts.STATUS_CORRUPT;
            case DELETED -> CommandTexts.STATUS_DELETED;
            case FAILED -> CommandTexts.STATUS_FAILED;
        };
        return text.raw(messages);
    }

    // ------------------------------------------------------------------ Mehrdeutigkeit, Warnungen

    public List<Component> ambiguous(String ref, List<String> candidates, @Nullable String otherPlayer) {
        List<Component> lines = new ArrayList<>();
        lines.add(CommandTexts.SESSION_AMBIGUOUS.render(messages, "ref", plain(ref)));
        for (String candidate : candidates) {
            Component line = CommandTexts.SESSION_AMBIGUOUS_CANDIDATE.render(messages, "ref", plain(candidate));
            var normalized = CommandArguments.normalizeRef(candidate);
            if (normalized.isPresent()) {
                line = line.clickEvent(ClickEvent.runCommand(sessionCommand(normalized.get(), otherPlayer)))
                        .hoverEvent(HoverEvent.showText(CommandTexts.SESSION_AMBIGUOUS_HOVER.render(messages)));
            }
            lines.add(line);
        }
        return lines;
    }

    /** Hinweis in Grau (Text unverändert, Farbcodes darin werden nicht ausgewertet). */
    public Component warning(String warning) {
        return CommandTexts.WARNING_BULLET.render(messages).append(Component.text(warning, NamedTextColor.GRAY));
    }

    private void addWarnings(List<Component> lines, List<String> warnings) {
        for (String warning : warnings) {
            if (warning != null && !warning.isBlank()) {
                lines.add(warning(warning));
            }
        }
    }
}
