package net.techniqstone.buildReplay.maintenance;

import static net.techniqstone.buildReplay.maintenance.Rows.CURRENT_INSTANCE;
import static net.techniqstone.buildReplay.maintenance.Rows.SERVER;
import static net.techniqstone.buildReplay.maintenance.Rows.STEVE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import net.techniqstone.buildReplay.config.PluginConfig;
import net.techniqstone.buildReplay.server.ServerIdentity;
import net.techniqstone.buildReplay.state.InstanceInfo;
import net.techniqstone.buildReplay.state.PresenceInfo;
import org.junit.jupiter.api.Test;

class InstanceHeartbeatTest {

    private static final Instant START = Instant.parse("2026-10-29T16:00:00Z");

    private final FakeSharedState shared = new FakeSharedState();
    private final SyncTaskRunner tasks = new SyncTaskRunner();
    private final MutableClock clock = new MutableClock(START);

    private InstanceHeartbeat heartbeat() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        return new InstanceHeartbeat(PluginConfig.defaults(), shared,
                new ServerIdentity(SERVER, CURRENT_INSTANCE, "dev", "-1", true), "1.0.0", tasks, clock, logger);
    }

    @Test
    void eachBeatAnnouncesThisInstanceWithAnExpiringEntry() throws Exception {
        InstanceHeartbeat heartbeat = heartbeat();
        clock.advance(Duration.ofSeconds(30));

        heartbeat.beat().get(5, TimeUnit.SECONDS);

        assertEquals(1, shared.beats.size());
        FakeSharedState.Beat beat = shared.beats.getFirst();
        assertEquals(new InstanceInfo(CURRENT_INSTANCE, SERVER, "1.0.0", START, START.plusSeconds(30)), beat.info());
        assertEquals(Duration.ofSeconds(45), beat.ttl(), "drei Herzschlag-Intervalle");
        assertTrue(shared.threadViolations.isEmpty(), shared.threadViolations.toString());
    }

    @Test
    void theHeartbeatLeavesPlayerPresenceToTheSessionService() throws Exception {
        PresenceInfo left = new PresenceInfo(SERVER, CURRENT_INSTANCE, START.minusSeconds(100), false);
        shared.presence.put(STEVE, left);

        heartbeat().beat().get(5, TimeUnit.SECONDS);

        assertEquals(left, shared.presence.get(STEVE), "ein gegangener Spieler wird nicht wieder anwesend gemeldet");
        assertTrue(shared.presenceWrites.isEmpty(), "Präsenz schreibt nur der Sitzungsdienst");
        assertEquals(1, shared.beats.size());
    }

    @Test
    void startBeatsImmediatelyAndThenPeriodicallyUntilClosed() {
        InstanceHeartbeat heartbeat = heartbeat();

        heartbeat.start();

        assertEquals(1, shared.beats.size());
        assertEquals(Duration.ofSeconds(15).toMillis(), tasks.scheduler.active().getFirst().periodMillis());
        clock.advance(Duration.ofSeconds(15));
        tasks.scheduler.runAll();
        assertEquals(2, shared.beats.size());
        assertEquals(START.plusSeconds(15), shared.beats.get(1).info().lastBeat());

        heartbeat.close();
        assertTrue(tasks.scheduler.active().isEmpty());
        heartbeat.beat();
        assertEquals(2, shared.beats.size(), "nach close() kein Herzschlag mehr");
    }
}
