package net.techniqstone.buildReplay.maintenance;

import static net.techniqstone.buildReplay.maintenance.Rows.CURRENT_INSTANCE;
import static net.techniqstone.buildReplay.maintenance.Rows.SERVER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.techniqstone.buildReplay.config.PluginConfig;
import net.techniqstone.buildReplay.recording.EngineStatus;
import net.techniqstone.buildReplay.recording.RecordingStats;
import net.techniqstone.buildReplay.server.ServerIdentity;
import net.techniqstone.buildReplay.session.SessionServiceStats;
import net.techniqstone.buildReplay.simplecloud.ClientHealth;
import net.techniqstone.buildReplay.state.SharedStateHealth;
import net.techniqstone.buildReplay.storage.StoreHealth;
import org.junit.jupiter.api.Test;

class HealthServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-29T16:00:00Z");

    private final SyncTaskRunner tasks = new SyncTaskRunner();
    private final MutableClock clock = new MutableClock(NOW);
    private final List<String> ioCallsOnMain = new CopyOnWriteArrayList<>();
    private final List<String> mainCallsOffMain = new CopyOnWriteArrayList<>();

    private final AtomicReference<ClientHealth> cloud = new AtomicReference<>(
            new ClientHealth(true, false, NOW.minusSeconds(5), null, null, 10, 0));
    private final AtomicReference<StoreHealth> database = new AtomicReference<>(
            new StoreHealth(true, true, 0, 0, 0, NOW.minusSeconds(1), null, 1));
    private final AtomicReference<SharedStateHealth> redis = new AtomicReference<>(
            new SharedStateHealth(true, true, false, NOW.minusSeconds(1), null));
    private final AtomicReference<EngineStatus> engine = new AtomicReference<>(
            new EngineStatus(true, true, true, null, true, 3600, "FILE", "1.8.19", 2));
    private volatile int ioCalls;

    private HealthService service(PluginConfig config) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        HealthSources sources = new HealthSources(
                () -> io("simplecloud", cloud.get()),
                () -> io("java-api", true),
                () -> io("database", database.get()),
                () -> io("redis", redis.get()),
                () -> main("engine", engine.get()),
                () -> main("recordings", new RecordingStats(2, 10, 5, 3, 0, 0)),
                () -> main("sessions", new SessionServiceStats(2, 2, 0, 12, 0, true)));
        return new HealthService(config, new ServerIdentity(SERVER, CURRENT_INSTANCE, "dev", "-1", true), "1.0.0",
                sources, tasks, clock, logger);
    }

    private <T> T io(String name, T value) {
        ioCalls++;
        if (SyncTaskRunner.onMainThread()) {
            ioCallsOnMain.add(name);
        }
        return value;
    }

    private <T> T main(String name, T value) {
        if (!SyncTaskRunner.onMainThread()) {
            mainCallsOffMain.add(name);
        }
        return value;
    }

    private HealthReport reportOnMain(HealthService service) {
        AtomicReference<HealthReport> report = new AtomicReference<>();
        tasks.runOnMain(() -> report.set(service.report()));
        return report.get();
    }

    private static boolean contains(HealthReport report, String text) {
        return report.warnings().stream().anyMatch(w -> w.contains(text));
    }

    @Test
    void aHealthyInstanceHasNoWarnings() {
        HealthService service = service(PluginConfig.defaults());
        service.start();

        HealthReport report = reportOnMain(service);

        assertEquals(List.of(), report.warnings());
        assertEquals(SERVER, report.serverName());
        assertEquals(CURRENT_INSTANCE, report.instanceId());
        assertEquals("1.0.0", report.pluginVersion());
        assertEquals(NOW, report.at());
        assertTrue(report.javaApiAvailable());
    }

    @Test
    void theReportOnTheMainThreadNeverQueriesBlockingComponents() {
        HealthService service = service(PluginConfig.defaults());
        service.start();
        int afterStart = ioCalls;

        reportOnMain(service);
        reportOnMain(service);

        assertEquals(afterStart, ioCalls, "report() liest nur den Zwischenspeicher");
        assertTrue(ioCallsOnMain.isEmpty(), ioCallsOnMain.toString());
        assertTrue(mainCallsOffMain.isEmpty(), mainCallsOffMain.toString());
    }

    @Test
    void anIncompatibleAdvancedReplayPlaybackIsReported() {
        engine.set(new EngineStatus(true, true, false, "PacketNPC.getInfoAddPacket schlägt fehl", true, 3600,
                "FILE", "1.8.19", 0));
        HealthService service = service(PluginConfig.defaults());
        service.start();

        HealthReport report = reportOnMain(service);

        assertTrue(contains(report, "nicht kompatibel: PacketNPC.getInfoAddPacket schlägt fehl"), report.warnings().toString());
    }

    @Test
    void realWorldBlockChangesAndATooShortMaxLengthAreReported() {
        engine.set(new EngineStatus(true, true, true, null, false, 600, "FILE", "1.8.19", 0));
        org.bukkit.configuration.MemoryConfiguration yaml = new org.bukkit.configuration.MemoryConfiguration();
        yaml.set("playback.force-client-side-block-changes", false);
        PluginConfig config = PluginConfig.from(yaml, new net.techniqstone.buildReplay.config.EnvResolver(n -> null),
                new java.util.ArrayList<>());
        HealthService service = service(config);
        service.start();

        HealthReport report = reportOnMain(service);

        assertTrue(contains(report, "verändern die echte Welt"), report.warnings().toString());
        assertTrue(contains(report, "max_length (600 s)"), report.warnings().toString());
    }

    @Test
    void anUnreachableDatabaseAndAFillingOutboxAreReported() {
        database.set(new StoreHealth(true, false, 3_000, 6_000, 0, NOW.minusSeconds(120), "Connection refused", 1));
        HealthService service = service(PluginConfig.defaults());
        service.start();

        HealthReport report = reportOnMain(service);

        assertTrue(contains(report, "MariaDB nicht erreichbar (Connection refused)"), report.warnings().toString());
        assertTrue(contains(report, "Outbox zu 90 % gefüllt (9000/10000)"), report.warnings().toString());
    }

    @Test
    void droppedWritesAndAHalfFullOutboxAreReported() {
        database.set(new StoreHealth(true, true, 0, 5_500, 17, NOW, null, 1));
        HealthService service = service(PluginConfig.defaults());
        service.start();

        HealthReport report = reportOnMain(service);

        assertTrue(contains(report, "Outbox füllt sich"), report.warnings().toString());
        assertTrue(contains(report, "17 Schreibvorgänge wurden verworfen"), report.warnings().toString());
    }

    @Test
    void aRedisOutageWithLocalFallbackIsReported() {
        redis.set(new SharedStateHealth(true, false, true, NOW.minusSeconds(60), "NOAUTH"));
        HealthService service = service(PluginConfig.defaults());
        service.start();

        HealthReport report = reportOnMain(service);

        assertTrue(contains(report, "Redis nicht erreichbar (NOAUTH) - lokaler Ersatzzustand aktiv"),
                report.warnings().toString());
    }

    @Test
    void aRejectedSimpleCloudCredentialIsReportedAsUnauthorized() {
        cloud.set(new ClientHealth(true, true, null, NOW.minusSeconds(3), "HTTP 401: invalid JWT", 5, 5));
        HealthService service = service(PluginConfig.defaults());
        service.start();

        HealthReport report = reportOnMain(service);

        assertTrue(contains(report, "SimpleCloud lehnt die Anmeldung ab"), report.warnings().toString());
    }

    @Test
    void anOpenCircuitBreakerIsReportedAsSimpleCloudUnreachable() {
        cloud.set(new ClientHealth(true, true, NOW.minusSeconds(300), NOW.minusSeconds(3), "Timeout", 50, 9));
        HealthService service = service(PluginConfig.defaults());
        service.start();

        HealthReport report = reportOnMain(service);

        assertTrue(contains(report, "Circuit-Breaker offen"), report.warnings().toString());
    }

    @Test
    void beforeTheFirstCheckOnlyAPendingHintIsShownForTheBlockingComponents() {
        database.set(new StoreHealth(true, false, 0, 0, 0, null, "down", 0));
        HealthService service = service(PluginConfig.defaults());

        HealthReport report = reportOnMain(service);

        assertTrue(contains(report, "wird noch ermittelt"), report.warnings().toString());
        assertTrue(report.warnings().stream().noneMatch(w -> w.contains("MariaDB nicht erreichbar")),
                report.warnings().toString());
    }

    @Test
    void anOutdatedCacheIsReportedAndRefreshedPeriodically() {
        HealthService service = service(PluginConfig.defaults());
        service.start();
        assertEquals(Duration.ofSeconds(15).toMillis(), tasks.scheduler.active().getFirst().periodMillis());

        clock.advance(Duration.ofSeconds(60));
        assertTrue(contains(reportOnMain(service), "s alt"));

        database.set(new StoreHealth(true, false, 0, 0, 0, null, "weg", 1));
        tasks.scheduler.runAll();

        HealthReport refreshed = reportOnMain(service);
        assertTrue(refreshed.warnings().stream().noneMatch(w -> w.contains("s alt")), refreshed.warnings().toString());
        assertTrue(contains(refreshed, "MariaDB nicht erreichbar (weg)"), refreshed.warnings().toString());

        service.close();
        assertTrue(tasks.scheduler.active().isEmpty());
    }

    @Test
    void aFailingComponentDoesNotBreakTheReport() {
        engine.set(null);
        HealthService service = new HealthService(PluginConfig.defaults(),
                new ServerIdentity(SERVER, CURRENT_INSTANCE, "dev", "-1", true), "1.0.0",
                new HealthSources(
                        () -> { throw new IllegalStateException("kaputt"); },
                        () -> true,
                        database::get,
                        redis::get,
                        () -> { throw new IllegalStateException("AR weg"); },
                        () -> new RecordingStats(0, 0, 0, 0, 0, 0),
                        () -> new SessionServiceStats(0, 0, 0, 0, 0, false)),
                tasks, clock, Logger.getAnonymousLogger());
        service.start();

        HealthReport report = reportOnMain(service);

        assertTrue(contains(report, "AdvancedReplay ist nicht verfügbar"), report.warnings().toString());
    }
}
