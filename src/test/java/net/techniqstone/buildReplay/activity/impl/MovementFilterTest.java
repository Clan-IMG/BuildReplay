package net.techniqstone.buildReplay.activity.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import net.techniqstone.buildReplay.activity.ActivityType;
import org.junit.jupiter.api.Test;

class MovementFilterTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID NETHER = UUID.fromString("00000000-0000-0000-0000-0000000000f2");

    private final MovementFilter filter = new MovementFilter(0.3, 15.0f, 250, true, true);

    private ActivityType move(double x, double z, long millis) {
        return filter.check(PLAYER, WORLD, x, 64, z, 0f, 0f, millis);
    }

    @Test
    void theFirstPositionOnlySetsTheReference() {
        assertNull(move(0, 0, 0));
        assertEquals(1, filter.trackedPlayers());
    }

    @Test
    void positionsAreCheckedAtMostEveryQuarterSecond() {
        move(0, 0, 0);

        assertNull(move(5, 0, 249), "innerhalb von 250 ms wird nicht geprüft");
        assertEquals(ActivityType.MOVE, move(5, 0, 250));
    }

    @Test
    void smallStepsAccumulateUntilTheThresholdIsReached() {
        move(0, 0, 0);

        assertNull(move(0.2, 0, 300), "0,2 Blöcke < 0,3");
        assertEquals(ActivityType.MOVE, move(0.4, 0, 600), "0,4 Blöcke vom Bezugspunkt");
        assertNull(move(0.5, 0, 900), "neuer Bezugspunkt bei 0,4");
    }

    @Test
    void lookingAroundWithoutMovingIsReportedAsLook() {
        filter.check(PLAYER, WORLD, 0, 64, 0, 0f, 0f, 0);

        assertNull(filter.check(PLAYER, WORLD, 0, 64, 0, 10f, 0f, 300));
        assertEquals(ActivityType.LOOK, filter.check(PLAYER, WORLD, 0, 64, 0, 15f, 0f, 600));
        assertEquals(ActivityType.LOOK, filter.check(PLAYER, WORLD, 0, 64, 0, 15f, -20f, 900));
    }

    @Test
    void yawWrapsAroundAtThreeHundredSixtyDegrees() {
        filter.check(PLAYER, WORLD, 0, 64, 0, 350f, 0f, 0);

        assertEquals(ActivityType.LOOK, filter.check(PLAYER, WORLD, 0, 64, 0, 5f, 0f, 300));
        assertEquals(15f, MovementFilter.yawDifference(350f, 5f), 0.001f);
        assertEquals(10f, MovementFilter.yawDifference(-175f, 175f), 0.001f);
        assertEquals(180f, MovementFilter.yawDifference(0f, 180f), 0.001f);
    }

    @Test
    void aWorldChangeReanchorsWithoutCountingAsInput() {
        move(0, 0, 0);

        assertNull(filter.check(PLAYER, NETHER, 100, 64, 100, 0f, 0f, 300));
        assertEquals(ActivityType.MOVE, filter.check(PLAYER, NETHER, 101, 64, 100, 0f, 0f, 600));
    }

    @Test
    void resetDropsTheReferenceSoATeleportIsNotCounted() {
        move(0, 0, 0);
        filter.reset(PLAYER);

        assertNull(move(500, 500, 300));
        assertEquals(ActivityType.MOVE, move(501, 500, 600));
    }

    @Test
    void onlyConfiguredKindsAreReported() {
        MovementFilter lookOnly = new MovementFilter(0.3, 15.0f, 250, false, true);
        lookOnly.check(PLAYER, WORLD, 0, 64, 0, 0f, 0f, 0);

        assertNull(lookOnly.check(PLAYER, WORLD, 10, 64, 0, 0f, 0f, 300), "Bewegung nicht gewünscht");
        assertEquals(ActivityType.LOOK, lookOnly.check(PLAYER, WORLD, 20, 64, 0, 90f, 0f, 600));
        assertFalse(new MovementFilter(0.3, 15f, 250, false, false).enabled());
    }

    @Test
    void aZeroThresholdStillRequiresSomeChange() {
        MovementFilter sensitive = new MovementFilter(0.0, 0.0f, 0, true, true);
        sensitive.check(PLAYER, WORLD, 0, 64, 0, 0f, 0f, 0);

        assertNull(sensitive.check(PLAYER, WORLD, 0, 64, 0, 0f, 0f, 1));
        assertEquals(ActivityType.MOVE, sensitive.check(PLAYER, WORLD, 0.01, 64, 0, 0f, 0f, 2));
        assertEquals(ActivityType.LOOK, sensitive.check(PLAYER, WORLD, 0.01, 64, 0, 0.5f, 0f, 3));
    }

    @Test
    void aClockGoingBackwardsDoesNotBlockChecksForever() {
        move(0, 0, 10_000);

        assertEquals(ActivityType.MOVE, move(5, 0, 5_000));
    }
}
