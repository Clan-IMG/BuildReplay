package net.techniqstone.buildReplay.command;

import static net.techniqstone.buildReplay.command.SessionData.ALEX;
import static net.techniqstone.buildReplay.command.SessionData.LOGIN;
import static net.techniqstone.buildReplay.command.SessionData.STEVE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.techniqstone.buildReplay.config.Messages;
import net.techniqstone.buildReplay.config.PluginConfig;
import net.techniqstone.buildReplay.maintenance.HealthReport;
import net.techniqstone.buildReplay.maintenance.MaintenanceException;
import net.techniqstone.buildReplay.maintenance.RetentionResult;
import net.techniqstone.buildReplay.recording.ActiveRecordingInfo;
import net.techniqstone.buildReplay.recording.EngineStatus;
import net.techniqstone.buildReplay.recording.RecordingStats;
import net.techniqstone.buildReplay.session.AmbiguousReferenceException;
import net.techniqstone.buildReplay.session.SessionListPage;
import net.techniqstone.buildReplay.session.SessionServiceStats;
import net.techniqstone.buildReplay.simplecloud.ClientHealth;
import net.techniqstone.buildReplay.simplecloud.SimpleCloudException;
import net.techniqstone.buildReplay.state.SharedStateHealth;
import net.techniqstone.buildReplay.storage.StorageUnavailableException;
import net.techniqstone.buildReplay.storage.StoreHealth;
import net.techniqstone.buildReplay.storage.model.SegmentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BuildReplayCommandTest {

    private static final Instant NOW = LOGIN.plusSeconds(600);

    private final QueueingTaskRunner tasks = new QueueingTaskRunner();
    private final MutableClock clock = new MutableClock(NOW);
    private final Fakes.Queries queries = new Fakes.Queries();
    private final Fakes.PlaybackFake playback = new Fakes.PlaybackFake();
    private final Fakes.RecordingsFake recordings = new Fakes.RecordingsFake();
    private final Fakes.Admin admin = new Fakes.Admin();
    private final Fakes.Players players = new Fakes.Players().add("Steve", STEVE).add("Alex", ALEX);
    private final Fakes.CapturingHandler log = new Fakes.CapturingHandler();
    private HealthReport health;
    private BuildReplayCommand command;

    private final FakeSender steve = FakeSender.player("Steve", STEVE, Permissions.USE, Permissions.PLAY);
    private final FakeSender moderator = FakeSender.player("Mod", java.util.UUID.randomUUID(), Permissions.USE,
            Permissions.PLAY, Permissions.OTHERS, Permissions.ADMIN);
    private final FakeSender console = FakeSender.console();

    @BeforeEach
    void setUp() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(log);
        health = new HealthReport(NOW, "dev", "dev-instance-123456789", "1.0.0",
                new ClientHealth(true, false, NOW, null, null, 4, 0), true,
                new StoreHealth(true, true, 0, 0, 0, NOW, null, 1),
                new SharedStateHealth(true, true, false, NOW, null),
                new EngineStatus(true, true, false, "NPC-Pakete inkompatibel", true, 3600, "FILE", "1.8.19", 1),
                new RecordingStats(1, 3, 2, 1, 0, 0),
                new SessionServiceStats(1, 1, 0, 3, 0, true),
                List.of("Wiedergabe mit AdvancedReplay 1.8.19 nicht kompatibel"));
        command = new BuildReplayCommand(PluginConfig.defaults(), Messages.fallbackOnly(), tasks, clock, queries,
                playback, recordings, () -> health, admin, logger, players);
    }

    private void run(FakeSender sender, String... args) {
        command.onCommand(sender.sender, null, "buildreplay", args);
    }

    private static SessionListPage onePage() {
        return SessionData.page(0, false, 1, SessionListPage.Source.SIMPLECLOUD,
                List.of(SessionData.closed("3c8a7573", LOGIN, 871, 2, 2, Set.of("dev"))), List.of());
    }

    @Test
    void ownSessionsAreSentOnlyAfterHoppingBackToTheMainThread() {
        run(steve, "sessions");

        assertEquals(List.of(new Fakes.ListCall(STEVE, 0)), queries.listCalls);
        queries.nextList.complete(onePage());
        assertTrue(steve.received.isEmpty(), "erst auf dem Hauptthread senden");
        assertEquals(1, tasks.pendingMain());

        tasks.drainMain();

        assertEquals(List.of("[BuildReplay] Deine Sitzungen (letzte 14 Tage)",
                "▪ 29.10.2026 16:58 · 14 min · 2 Replays · dev"), steve.lines());
    }

    @Test
    void viewingAnotherPlayersSessionsNeedsTheOthersPermission() {
        run(steve, "sessions", "Alex");

        assertTrue(queries.listCalls.isEmpty());
        assertEquals(List.of("[BuildReplay] Dazu fehlt dir die Berechtigung."), steve.lines());

        steve.clear();
        run(steve, "sessions", "steve", "2");
        assertEquals(List.of(new Fakes.ListCall(STEVE, 1)), queries.listCalls, "eigener Name braucht keine Rechte");

        run(moderator, "sessions", "Alex");
        assertEquals(new Fakes.ListCall(ALEX, 0), queries.listCalls.get(1));
        queries.nextList.complete(onePage());
        tasks.drainMain();
        assertTrue(moderator.text().contains("Sitzungen von Alex"), moderator.text());
    }

    @Test
    void sessionWithAPlayerNameInsteadOfAReferenceShowsThatPlayersSessionList() {
        run(steve, "session", "Steve");
        assertEquals(List.of(new Fakes.ListCall(STEVE, 0)), queries.listCalls, "eigener Name -> eigene Liste");
        assertTrue(queries.detailsCalls.isEmpty());

        run(steve, "session", "Alex");
        assertEquals(1, queries.listCalls.size(), "fremder Spieler braucht weiterhin buildreplay.others");
        assertTrue(steve.text().contains("Berechtigung"), steve.text());

        run(moderator, "session", "Alex");
        assertEquals(new Fakes.ListCall(ALEX, 0), queries.listCalls.get(1));

        clock.advance(Duration.ofSeconds(2));
        run(moderator, "session", "3c8a7573");
        assertEquals(List.of(new Fakes.DetailsCall(moderator.uuid, "3c8a7573")), queries.detailsCalls,
                "echte Referenzen bleiben Sitzungsdetails");
        assertTrue(BuildReplayCommand.looksLikeSessionRef("p1a2b3c"));
        assertFalse(BuildReplayCommand.looksLikeSessionRef("wrobelxxl"));
    }

    @Test
    void anUnknownPlayerNameIsReportedClearly() {
        run(moderator, "sessions", "Herobrine");

        assertTrue(queries.listCalls.isEmpty());
        assertEquals(List.of("[BuildReplay] Der Spieler Herobrine ist auf diesem Server nicht bekannt."),
                moderator.lines());
    }

    @Test
    void theConsoleMustNameAPlayer() {
        run(console, "sessions");

        assertEquals(List.of("[BuildReplay] Dieser Befehl ist nur für Spieler.",
                "Verwendung in der Konsole: /buildreplay sessions <Spieler> [Seite]"), console.lines());

        console.clear();
        run(console, "sessions", "Steve");
        assertEquals(List.of(new Fakes.ListCall(STEVE, 0)), queries.listCalls);
    }

    @Test
    void aDatabaseOutageIsReportedAsDatabaseUnavailable() {
        run(steve, "sessions");
        queries.nextList.completeExceptionally(new StorageUnavailableException("weg"));
        tasks.drainMain();

        assertEquals(List.of("[BuildReplay] Datenbank derzeit nicht erreichbar. Bitte versuche es später erneut."),
                steve.lines());
        assertTrue(log.records.stream().noneMatch(r -> r.getLevel() == Level.SEVERE));
    }

    @Test
    void aSimpleCloudOutageIsReportedAsSimpleCloudUnavailable() {
        run(steve, "session", "3c8a7573");
        queries.nextDetails.completeExceptionally(new java.util.concurrent.CompletionException(
                new SimpleCloudException(SimpleCloudException.Reason.UNAVAILABLE, 503, "Service Unavailable")));
        tasks.drainMain();

        assertEquals(List.of("[BuildReplay] SimpleCloud nicht erreichbar. Bitte versuche es später erneut."),
                steve.lines());
    }

    @Test
    void anAmbiguousReferenceListsTheCandidates() {
        run(steve, "session", "59dc");
        queries.nextDetails.completeExceptionally(new AmbiguousReferenceException("59dc", List.of("59dc83e5", "59dc1234")));
        tasks.drainMain();

        assertEquals(List.of("[BuildReplay] Die Referenz 59dc passt auf mehrere Sitzungen – bitte genauer angeben:",
                "▪ 59dc83e5", "▪ 59dc1234"), steve.lines());
        assertEquals("/buildreplay session 59dc83e5",
                SessionViewsTest.command(steve.received.get(1).clickEvent()));
    }

    @Test
    void anUnexpectedFailureIsLoggedAsSevereAndAnsweredGenerically() {
        run(steve, "sessions");
        queries.nextList.completeExceptionally(new IllegalStateException("Bug"));
        tasks.drainMain();

        assertEquals(List.of("[BuildReplay] Ein interner Fehler ist aufgetreten. Details stehen in der Serverkonsole."),
                steve.lines());
        assertTrue(log.records.stream().anyMatch(r -> r.getLevel() == Level.SEVERE
                && r.getThrown() instanceof IllegalStateException), "SEVERE mit Ursache");
    }

    @Test
    void aFullIoQueueIsReportedAsBusy() {
        queries.listThrows = new RejectedExecutionException("voll");

        run(steve, "sessions");
        tasks.drainMain();

        assertEquals(List.of("[BuildReplay] BuildReplay ist gerade ausgelastet. Bitte versuche es gleich erneut."),
                steve.lines());
    }

    @Test
    void listAndDetailRequestsHaveAOneSecondCooldownPerPlayer() {
        run(steve, "sessions");
        run(steve, "session", "3c8a7573");

        assertEquals(1, queries.listCalls.size());
        assertTrue(queries.detailsCalls.isEmpty());
        assertEquals(List.of("[BuildReplay] Bitte warte einen Moment, bevor du den Befehl erneut verwendest."),
                steve.lines());

        clock.advance(Duration.ofSeconds(1));
        run(steve, "session", "3c8a7573");
        assertEquals(1, queries.detailsCalls.size());

        run(moderator, "sessions", "Steve");
        assertEquals(2, queries.listCalls.size(), "die Abklingzeit gilt pro Spieler");
    }

    @Test
    void sessionDetailsAreShownAndUnknownReferencesExplained() {
        run(steve, "session", "3C8A7573");
        assertEquals(List.of(new Fakes.DetailsCall(STEVE, "3c8a7573")), queries.detailsCalls);
        queries.nextDetails.complete(Optional.of(SessionData.details(SessionData.active("3c8a7573"), List.of(
                SessionData.segment(41, LOGIN.plusSeconds(60), 252, SegmentStatus.AVAILABLE)), List.of())));
        tasks.drainMain();

        assertTrue(steve.text().contains("[▶ Abspielen]"), steve.text());
        assertTrue(steve.text().contains("[▶ Ganze Sitzung]"), steve.text());

        steve.clear();
        clock.advance(Duration.ofSeconds(2));
        queries.nextDetails = CompletableFuture.completedFuture(Optional.empty());
        run(steve, "session", "deadbeef");
        tasks.drainMain();
        assertEquals(List.of("[BuildReplay] Keine Sitzung mit der Referenz deadbeef gefunden."), steve.lines());
    }

    @Test
    void playDelegatesToPlaybackAndChecksPermissions() {
        run(steve, "play", "41");
        run(steve, "play", "session", "3c8a7573");
        run(steve, "play", "session", "3c8a7573", "Alex");
        run(moderator, "play", "session", "3c8a7573", "Alex");

        assertEquals(List.of("segment Steve 41", "session Steve " + STEVE + " 3c8a7573",
                "session Mod " + ALEX + " 3c8a7573"), playback.calls);
        assertEquals(List.of("[BuildReplay] Dazu fehlt dir die Berechtigung."), steve.lines());

        FakeSender viewer = FakeSender.player("Viewer", java.util.UUID.randomUUID(), Permissions.USE);
        command.onCommand(viewer.sender, null, "buildreplay", new String[]{"play", "41"});
        assertEquals(List.of("[BuildReplay] Dazu fehlt dir die Berechtigung."), viewer.lines());
        assertEquals(3, playback.calls.size());
    }

    @Test
    void theConsoleCannotPlayOrStop() {
        run(console, "play", "41");
        run(console, "stop");

        assertTrue(playback.calls.isEmpty());
        assertEquals(List.of("[BuildReplay] Dieser Befehl ist nur für Spieler.",
                "[BuildReplay] Dieser Befehl ist nur für Spieler."), console.lines());
    }

    @Test
    void stopIsAlwaysHandedToPlaybackWhichAnswersItself() {
        run(steve, "stop");
        assertEquals(List.of("stop Steve"), playback.calls, "auch ohne laufendes Replay (Pause zwischen Abschnitten)");
        assertTrue(steve.lines().isEmpty(), "die Rückmeldung sendet die Wiedergabe");

        playback.watching = true;
        run(steve, "stop");
        assertEquals(List.of("stop Steve", "stop Steve"), playback.calls);
    }

    @Test
    void statusIsOnlyForAdminsAndShowsTheWarnings() {
        run(steve, "status");
        assertEquals(List.of("[BuildReplay] Dazu fehlt dir die Berechtigung."), steve.lines());

        run(moderator, "status");
        String text = moderator.text();
        assertTrue(text.contains("BuildReplay-Status · dev (dev-instance…) · v1.0.0"), text);
        assertTrue(text.contains("AdvancedReplay 1.8.19: Aufnahme OK, Wiedergabe inkompatibel"), text);
        assertTrue(text.contains("MariaDB: OK"), text);
        assertTrue(text.contains("Redis: OK"), text);
        assertTrue(text.contains("Warnungen:"), text);
        assertTrue(text.contains("• Wiedergabe mit AdvancedReplay 1.8.19 nicht kompatibel"), text);
    }

    @Test
    void adminRecordingsAndInspectShowTheLocalRecordings() {
        recordings.active.add(new ActiveRecordingInfo(STEVE, "Steve", "brraw-abc-123", "world", NOW.minusSeconds(125),
                2_500, 17, true, "3c8a7573"));

        run(moderator, "admin", "recordings");
        String list = moderator.text();
        assertTrue(list.contains("Laufende Aufnahmen auf diesem Server (1)"), list);
        assertTrue(list.contains("▪ Steve · world · seit 17:05:58 (2 min 05 s) · 17 relevante Aktionen · AFK"), list);

        moderator.clear();
        run(moderator, "admin", "inspect", "Steve");
        assertTrue(moderator.text().contains("Rohaufnahme: brraw-abc-123"), moderator.text());
        assertTrue(moderator.text().contains("[Sitzungen anzeigen]"), moderator.text());

        moderator.clear();
        run(moderator, "admin", "inspect", "Alex");
        assertTrue(moderator.text().contains("Keine laufende Aufnahme von Alex auf diesem Server."), moderator.text());

        run(steve, "admin", "recordings");
        assertEquals(List.of("[BuildReplay] Dazu fehlt dir die Berechtigung."), steve.lines());
    }

    @Test
    void adminCleanupPassesTheDryRunFlagAndReportsTheCounts() {
        run(moderator, "admin", "cleanup", "--dry-run");

        assertEquals(List.of("cleanup dryRun=true"), admin.calls);
        assertEquals(List.of("[BuildReplay] Probelauf läuft – es wird nichts gelöscht …"), moderator.lines());
        admin.cleanup.complete(new RetentionResult(12, 5, 0, true));
        tasks.drainMain();

        assertEquals("[BuildReplay] Probelauf: 5 würden gelöscht (12 geprüft, nichts verändert)",
                moderator.lines().getLast());
    }

    @Test
    void aCleanupThatCannotRunShowsTheReason() {
        run(moderator, "admin", "cleanup");
        admin.cleanup.completeExceptionally(new MaintenanceException("Aufräumen läuft bereits."));
        tasks.drainMain();

        assertEquals("[BuildReplay] Aufräumen läuft bereits.", moderator.lines().getLast());
    }

    @Test
    void adminReloadAndSyncReportTheirOutcome() {
        admin.reload = CompletableFuture.completedFuture(List.of("display.time-zone: unbekannte Zeitzone 'Mars'"));

        run(moderator, "admin", "reload");
        tasks.drainMain();
        assertEquals(List.of("[BuildReplay] Konfiguration und Texte wurden neu geladen.",
                "1 Konfigurationswarnung(en):", "• display.time-zone: unbekannte Zeitzone 'Mars'"), moderator.lines());

        moderator.clear();
        run(moderator, "admin", "sync");
        tasks.drainMain();
        assertEquals(List.of("[BuildReplay] Outbox und Sitzungsabgleich werden ausgeführt …",
                "[BuildReplay] Outbox geleert und Sitzungsabgleich mit SimpleCloud ausgeführt."), moderator.lines());
    }

    @Test
    void invalidInputGetsAClearMessageAndTheUsage() {
        run(steve, "sessions", "0");
        assertEquals(List.of("[BuildReplay] '0' ist keine gültige Seitenzahl (1–10000).",
                "Verwendung: /buildreplay sessions [Spieler] [Seite]"), steve.lines());

        steve.clear();
        run(steve, "admin", "explode");
        assertEquals(List.of("[BuildReplay] Dazu fehlt dir die Berechtigung."), steve.lines(),
                "Admin-Hilfe nur für Admins");

        steve.clear();
        run(steve, "fly");
        assertEquals(List.of("[BuildReplay] Unbekannter Unterbefehl fly. Hilfe: /buildreplay help"), steve.lines());
    }

    @Test
    void theHelpListsOnlyPermittedSubcommands() {
        run(steve, "help");
        String help = steve.text();
        assertTrue(help.contains("/buildreplay sessions [Seite]"), help);
        assertTrue(help.contains("/buildreplay play <Abschnitt-ID>"), help);
        assertFalse(help.contains("/buildreplay status"), help);
        assertFalse(help.contains("<Spieler> [Seite]"), help);

        run(moderator, "help");
        assertTrue(moderator.text().contains("/buildreplay admin"), moderator.text());
    }

    @Test
    void tabCompletionOffersPermittedSubcommandsAndRecentReferences() {
        assertEquals(List.of("session", "sessions", "status", "stop"),
                command.onTabComplete(moderator.sender, null, "buildreplay", new String[]{"s"}).stream().sorted().toList());
        assertEquals(List.of("sessions", "session"),
                command.onTabComplete(steve.sender, null, "buildreplay", new String[]{"se"}));
        assertEquals(List.of(), command.onTabComplete(steve.sender, null, "buildreplay", new String[]{"sessions", ""}));
        assertEquals(List.of("Alex"),
                command.onTabComplete(moderator.sender, null, "buildreplay", new String[]{"sessions", "A"}));
        assertEquals(List.of("--dry-run"),
                command.onTabComplete(moderator.sender, null, "buildreplay", new String[]{"admin", "cleanup", "-"}));
        assertEquals(List.of(), command.onTabComplete(steve.sender, null, "buildreplay", new String[]{"admin", ""}));

        run(steve, "sessions");
        queries.nextList.complete(onePage());
        tasks.drainMain();
        assertEquals(List.of("3c8a7573"),
                command.onTabComplete(steve.sender, null, "buildreplay", new String[]{"session", "3c"}));
        assertEquals(List.of("3c8a7573"),
                command.onTabComplete(steve.sender, null, "buildreplay", new String[]{"play", "session", ""}));
    }

    @Test
    void answersForPlayersWhoLeftMeanwhileAreDropped() {
        run(steve, "sessions");
        steve.online = false;
        queries.nextList.complete(onePage());
        tasks.drainMain();

        assertTrue(steve.received.isEmpty());
    }

    @Test
    void aReloadedConfigurationChangesTheDisplay() {
        org.bukkit.configuration.MemoryConfiguration yaml = new org.bukkit.configuration.MemoryConfiguration();
        yaml.set("display.history-days", 7);
        yaml.set("display.date-time-pattern", "yyyy-MM-dd HH:mm");
        command.updateConfig(PluginConfig.from(yaml, new net.techniqstone.buildReplay.config.EnvResolver(n -> null),
                new java.util.ArrayList<>()));

        run(steve, "sessions");
        queries.nextList.complete(onePage());
        tasks.drainMain();

        assertEquals(List.of("[BuildReplay] Deine Sitzungen (letzte 7 Tage)",
                "▪ 2026-10-29 16:58 · 14 min · 2 Replays · dev"), steve.lines());
    }
}
