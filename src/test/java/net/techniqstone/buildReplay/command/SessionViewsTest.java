package net.techniqstone.buildReplay.command;

import static net.techniqstone.buildReplay.command.SessionData.LOGIN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.techniqstone.buildReplay.config.Messages;
import net.techniqstone.buildReplay.config.PluginConfig;
import net.techniqstone.buildReplay.session.SessionDetails;
import net.techniqstone.buildReplay.session.SessionListPage;
import net.techniqstone.buildReplay.storage.model.SegmentStatus;
import org.junit.jupiter.api.Test;

class SessionViewsTest {

    private static final Instant NOW = LOGIN.plusSeconds(14 * 60);

    private final SessionViews views = new SessionViews(Messages.fallbackOnly(),
            Formatters.of(PluginConfig.defaults().display()), 14);

    static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** Alle Teilkomponenten (Baum flach). */
    static List<Component> flatten(Component root) {
        List<Component> all = new ArrayList<>();
        all.add(root);
        for (Component child : root.children()) {
            all.addAll(flatten(child));
        }
        return all;
    }

    static String command(ClickEvent click) {
        assertNotNull(click, "Klick fehlt");
        return ((ClickEvent.Payload.Text) click.payload()).value();
    }

    static String hover(Component component) {
        HoverEvent<?> hover = component.hoverEvent();
        assertNotNull(hover, "Hover fehlt");
        return plain((Component) hover.value());
    }

    private static Component find(Component root, String text) {
        return flatten(root).stream()
                .filter(c -> c instanceof TextComponent t && t.content().contains(text))
                .findFirst()
                .orElseThrow(() -> new AssertionError("'" + text + "' nicht gefunden in " + plain(root)));
    }

    /** Tiefste Komponente mit Klick, deren Text {@code text} enthält. */
    static Component withClick(Component root, String text) {
        Component found = null;
        for (Component c : flatten(root)) {
            if (c.clickEvent() != null && plain(c).contains(text)) {
                found = c;
            }
        }
        if (found == null) {
            throw new AssertionError("kein Klick für '" + text + "' in " + plain(root));
        }
        return found;
    }

    static boolean anyClick(Component root, String text) {
        return flatten(root).stream().anyMatch(c -> c.clickEvent() != null && plain(c).contains(text));
    }

    /** Tiefste Komponente mit Hover, deren Text {@code text} enthält. */
    static Component withHover(Component root, String text) {
        Component found = null;
        for (Component c : flatten(root)) {
            if (c.hoverEvent() != null && plain(c).contains(text)) {
                found = c;
            }
        }
        if (found == null) {
            throw new AssertionError("kein Hover für '" + text + "' in " + plain(root));
        }
        return found;
    }

    @Test
    void theListLooksLikeTheDashboardWithDateDurationAndAGreenActiveMarker() {
        SessionListPage page = SessionData.page(0, false, 2, SessionListPage.Source.SIMPLECLOUD, List.of(
                SessionData.active("59dc83e5"),
                SessionData.closed("3c8a7573", Instant.parse("2026-10-28T22:33:30Z"), 871, 2, 2, Set.of("dev", "build"))),
                List.of());

        List<Component> lines = views.sessionList(page, null, NOW);

        assertEquals("Deine Sitzungen (letzte 14 Tage)", plain(lines.get(0)));
        assertEquals("▪ 29.10.2026 16:58 · Aktiv · 3 Replays · dev", plain(lines.get(1)));
        assertEquals("▪ 28.10.2026 23:33 · 14 min · 2 Replays · build, dev", plain(lines.get(2)));
        assertEquals(NamedTextColor.GREEN, find(lines.get(1), "Aktiv").color());
        assertEquals(3, lines.size(), "keine Blätterleiste bei nur einer Seite");
    }

    @Test
    void everyEntryOpensItsDetailsOnClickAndExplainsItselfOnHover() {
        SessionListPage page = SessionData.page(0, false, 1, SessionListPage.Source.SIMPLECLOUD, List.of(
                SessionData.closed("3c8a7573", Instant.parse("2026-10-28T22:33:30Z"), 871, 2, 1, Set.of("dev"))),
                List.of());

        Component entry = views.sessionList(page, null, NOW).get(1);

        assertEquals(ClickEvent.Action.RUN_COMMAND, entry.clickEvent().action());
        assertEquals("/buildreplay session 3c8a7573", command(entry.clickEvent()));
        String hover = hover(entry);
        assertTrue(hover.contains("Sitzung 3c8a7573"), hover);
        assertTrue(hover.contains("Login: 28.10.2026 23:33"), hover);
        assertTrue(hover.contains("Logout: 28.10.2026 23:48"), hover);
        assertTrue(hover.contains("Dauer: 14 min"), hover);
        assertTrue(hover.contains("Replays: 2 (1 abspielbar)"), hover);
        assertTrue(plain(entry).contains("2 Replays (1 abspielbar)"), plain(entry));
    }

    @Test
    void activeSessionsShowTheRunningDurationInTheHover() {
        SessionListPage page = SessionData.page(0, false, 1, SessionListPage.Source.SIMPLECLOUD,
                List.of(SessionData.active("59dc83e5")), List.of());

        String hover = hover(views.sessionList(page, null, NOW).get(1));

        assertTrue(hover.contains("Logout: noch online"), hover);
        assertTrue(hover.contains("Dauer: 14 min"), hover);
    }

    @Test
    void anotherPlayersListCarriesTheNameInEveryClickCommand() {
        SessionListPage page = SessionData.page(1, true, 30, SessionListPage.Source.SIMPLECLOUD,
                List.of(SessionData.active("59dc83e5")), List.of());

        List<Component> lines = views.sessionList(page, "Alex", NOW);

        assertEquals("Sitzungen von Alex (letzte 14 Tage)", plain(lines.get(0)));
        assertEquals("/buildreplay session 59dc83e5 Alex", command(lines.get(1).clickEvent()));
        Component navigation = lines.get(2);
        assertEquals("[« Zurück] Seite 2/4 [Weiter »]", plain(navigation));
        assertEquals("/buildreplay sessions Alex", command(withClick(navigation, "Zurück").clickEvent()));
        assertEquals("/buildreplay sessions Alex 3", command(withClick(navigation, "Weiter").clickEvent()));
    }

    @Test
    void theFirstAndLastPageHaveDisabledButtons() {
        SessionListPage first = SessionData.page(0, true, 0, SessionListPage.Source.SIMPLECLOUD,
                List.of(SessionData.active("59dc83e5")), List.of());
        Component navigation = views.sessionList(first, null, NOW).get(2);
        assertFalse(anyClick(navigation, "Zurück"));
        assertEquals("/buildreplay sessions 2", command(withClick(navigation, "Weiter").clickEvent()));
        assertEquals("[« Zurück] Seite 1 [Weiter »]", plain(navigation), "Gesamtzahl unbekannt");

        SessionListPage last = SessionData.page(2, false, 20, SessionListPage.Source.SIMPLECLOUD,
                List.of(SessionData.active("59dc83e5")), List.of());
        Component end = views.sessionList(last, null, NOW).get(2);
        assertFalse(anyClick(end, "Weiter"));
        assertEquals("/buildreplay sessions 2", command(withClick(end, "Zurück").clickEvent()));
        assertEquals("[« Zurück] Seite 3/3 [Weiter »]", plain(end));
    }

    @Test
    void anEmptyHistoryAndProvisionalSessionsAreExplained() {
        SessionListPage empty = SessionData.page(0, false, 0, SessionListPage.Source.SIMPLECLOUD, List.of(), List.of());
        assertEquals("Keine Sitzungen in den letzten 14 Tagen.", plain(views.sessionList(empty, null, NOW).get(1)));

        var provisional = new net.techniqstone.buildReplay.session.SessionListEntry("p1a2b3c4", null, 9L, LOGIN, null,
                0, true, true, 0, 0, Set.of());
        SessionListPage page = SessionData.page(0, false, 1, SessionListPage.Source.DATABASE, List.of(provisional),
                List.of("SimpleCloud nicht erreichbar - Anzeige aus der Datenbank"));
        List<Component> lines = views.sessionList(page, null, NOW);
        assertEquals("▪ 29.10.2026 16:58 · Aktiv (vorläufig) · keine Replays · –", plain(lines.get(1)));
        assertTrue(hover(lines.get(1)).contains("Vorläufige Sitzung"));
        assertEquals("• SimpleCloud nicht erreichbar - Anzeige aus der Datenbank", plain(lines.get(2)));
        assertEquals(NamedTextColor.GRAY, find(lines.get(2), "SimpleCloud").color());
    }

    @Test
    void withoutDatabaseTheReplayCountIsShownAsUnknown() {
        SessionListPage page = SessionData.page(0, false, 1, SessionListPage.Source.SIMPLECLOUD_ONLY,
                List.of(SessionData.closed("3c8a7573", LOGIN, 30, 0, 0, Set.of())), List.of());

        Component entry = views.sessionList(page, null, NOW).get(1);

        assertEquals("▪ 29.10.2026 16:58 · <1 min · Replays unbekannt · –", plain(entry));
        assertTrue(hover(entry).contains("Replays: unbekannt"));
    }

    @Test
    void detailsListSegmentsWithTimeRangeAndPlayButtonsOnlyForPlayableOnes() {
        SessionDetails details = SessionData.details(SessionData.active("59dc83e5"), List.of(
                SessionData.segment(41, LOGIN.plusSeconds(60), 252, SegmentStatus.AVAILABLE),
                SessionData.segment(42, LOGIN.plusSeconds(600), 45, SegmentStatus.DELETED)),
                List.of("Abschnitte auf build sind nur dort abspielbar"));

        List<Component> lines = views.sessionDetails(details, null, true);

        assertEquals("Sitzung 59dc83e5 · 29.10.2026 16:58 · Aktiv", plain(lines.get(0)));
        assertEquals("Server: dev, lobby-1", plain(lines.get(1)));
        assertEquals("Verlauf: lobby-1 → dev", plain(lines.get(2)));
        assertEquals("Replay-Abschnitte (2):", plain(lines.get(3)));
        assertEquals("1. 16:59:03–17:03:15 (4 min 12 s) · dev · 42 Aktionen [▶ Abspielen]", plain(lines.get(4)));
        assertEquals("2. 17:08:03–17:08:48 (45 s) · dev · 42 Aktionen [Gelöscht]", plain(lines.get(5)));
        assertEquals("/buildreplay play 41", command(withClick(lines.get(4), "Abspielen").clickEvent()));
        assertTrue(flatten(lines.get(5)).stream().noneMatch(c -> c.clickEvent() != null), "nicht abspielbar");
        String segmentHover = hover(withHover(lines.get(4), "16:59:03"));
        assertTrue(segmentHover.contains("Abschnitt #41"), segmentHover);
        assertTrue(segmentHover.contains("30× Blöcke gesetzt, 12× Blöcke abgebaut"), segmentHover);
        assertTrue(segmentHover.contains("Vorlauf / Nachlauf: 10 s / 10 s"), segmentHover);

        Component footer = lines.get(6);
        assertEquals("[▶ Ganze Sitzung]  [« Zur Übersicht]", plain(footer));
        assertEquals("/buildreplay play session 59dc83e5", command(withClick(footer, "Ganze Sitzung").clickEvent()));
        assertEquals("/buildreplay sessions", command(withClick(footer, "Übersicht").clickEvent()));
        assertEquals("• Abschnitte auf build sind nur dort abspielbar", plain(lines.get(7)));
        assertEquals(NamedTextColor.GRAY, find(lines.get(7), "Abschnitte auf build").color());
    }

    @Test
    void detailsWithoutPlayableSegmentsOrWithoutPermissionOfferNoPlayback() {
        SessionDetails none = SessionData.details(SessionData.active("59dc83e5"), List.of(
                SessionData.segment(42, LOGIN.plusSeconds(600), 45, SegmentStatus.MISSING)), List.of());
        List<Component> lines = views.sessionDetails(none, "Alex", true);
        assertFalse(lines.stream().map(SessionViewsTest::plain).anyMatch(l -> l.contains("Ganze Sitzung")));
        assertEquals("/buildreplay sessions Alex",
                command(withClick(lines.getLast(), "Übersicht").clickEvent()));

        SessionDetails playable = SessionData.details(SessionData.active("59dc83e5"), List.of(
                SessionData.segment(41, LOGIN.plusSeconds(60), 252, SegmentStatus.AVAILABLE)), List.of());
        List<Component> noPermission = views.sessionDetails(playable, null, false);
        assertFalse(noPermission.stream().map(SessionViewsTest::plain).anyMatch(l -> l.contains("Abspielen")
                || l.contains("Ganze Sitzung")));
    }

    @Test
    void aSessionWithoutSegmentsSaysSo() {
        SessionDetails details = SessionData.details(
                SessionData.closed("3c8a7573", LOGIN, 3_900, 0, 0, Set.of("lobby-1")), List.of(), List.of());

        List<String> lines = views.sessionDetails(details, null, true).stream().map(SessionViewsTest::plain).toList();

        assertEquals("Sitzung 3c8a7573 · 29.10.2026 16:58 · 1 h 05 min", lines.get(0));
        assertTrue(lines.contains("In dieser Sitzung wurden keine Replay-Abschnitte gespeichert."), lines.toString());
    }

    @Test
    void ambiguousReferencesListClickableCandidates() {
        List<Component> lines = views.ambiguous("59dc", List.of("59dc83e5", "59dc1234"), "Alex");

        assertEquals("Die Referenz 59dc passt auf mehrere Sitzungen – bitte genauer angeben:", plain(lines.get(0)));
        assertEquals("▪ 59dc83e5", plain(lines.get(1)));
        assertEquals("/buildreplay session 59dc1234 Alex", command(lines.get(2).clickEvent()));
    }
}
