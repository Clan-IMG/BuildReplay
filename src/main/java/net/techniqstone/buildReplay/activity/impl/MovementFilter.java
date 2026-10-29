package net.techniqstone.buildReplay.activity.impl;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.techniqstone.buildReplay.activity.ActivityType;
import org.jspecify.annotations.Nullable;

/**
 * Billige Vorprüfung für {@code PlayerMoveEvent}: vergleicht höchstens alle {@code checkIntervalMillis} pro Spieler
 * die aktuelle Position/Blickrichtung mit einem Bezugspunkt. Erst wenn sich die Position um mindestens
 * {@code movementThreshold} Blöcke bzw. Gier/Neigung um mindestens {@code lookThresholdDegrees} Grad vom Bezugspunkt
 * entfernt haben, entsteht ein {@link ActivityType#MOVE}- bzw. {@link ActivityType#LOOK}-Ereignis und der
 * Bezugspunkt wandert mit. Langsames, stetiges Gehen summiert sich so ebenfalls auf.
 *
 * <p>Hauptthread-gebunden, nicht threadsicher.
 */
final class MovementFilter {

    private final double movementThreshold;
    private final double movementThresholdSquared;
    private final float lookThresholdDegrees;
    private final long checkIntervalMillis;
    private final boolean reportMoves;
    private final boolean reportLooks;
    private final Map<UUID, Anchor> anchors = new HashMap<>();

    MovementFilter(double movementThreshold, float lookThresholdDegrees, long checkIntervalMillis,
            boolean reportMoves, boolean reportLooks) {
        this.movementThreshold = Math.max(0.0, movementThreshold);
        this.movementThresholdSquared = this.movementThreshold * this.movementThreshold;
        this.lookThresholdDegrees = Math.max(0.0f, lookThresholdDegrees);
        this.checkIntervalMillis = Math.max(0L, checkIntervalMillis);
        this.reportMoves = reportMoves;
        this.reportLooks = reportLooks;
    }

    /** Ob überhaupt Bewegung oder Blick gemeldet werden soll - sonst kann der Listener sofort abbrechen. */
    boolean enabled() {
        return reportMoves || reportLooks;
    }

    /**
     * Prüft die neue Lage des Spielers.
     *
     * @return {@link ActivityType#MOVE}, {@link ActivityType#LOOK} oder {@code null}, wenn nichts zu melden ist
     */
    @Nullable ActivityType check(UUID player, @Nullable UUID world, double x, double y, double z, float yaw,
            float pitch, long nowMillis) {
        Objects.requireNonNull(player, "player");
        Anchor anchor = anchors.get(player);
        if (anchor == null) {
            anchors.put(player, new Anchor(world, x, y, z, yaw, pitch, nowMillis));
            return null;
        }
        long sinceCheck = nowMillis - anchor.checkedAt;
        if (sinceCheck >= 0 && sinceCheck < checkIntervalMillis) {
            return null;
        }
        anchor.checkedAt = nowMillis;
        if (!Objects.equals(anchor.world, world)) {
            // Weltwechsel ohne Teleport-Ereignis: neu verankern, das ist keine Eingabe des Spielers
            anchor.world = world;
            anchor.moveTo(x, y, z);
            anchor.lookAt(yaw, pitch);
            return null;
        }
        double dx = x - anchor.x;
        double dy = y - anchor.y;
        double dz = z - anchor.z;
        double distanceSquared = dx * dx + dy * dy + dz * dz;
        if (distanceSquared > 0.0 && distanceSquared >= movementThresholdSquared) {
            anchor.moveTo(x, y, z);
            if (reportMoves) {
                anchor.lookAt(yaw, pitch);
                return ActivityType.MOVE;
            }
        }
        float rotation = Math.max(yawDifference(anchor.yaw, yaw), Math.abs(pitch - anchor.pitch));
        if (rotation > 0.0f && rotation >= lookThresholdDegrees) {
            anchor.lookAt(yaw, pitch);
            if (reportLooks) {
                return ActivityType.LOOK;
            }
        }
        return null;
    }

    /** Nach Teleport: nächste Bewegung verankert neu statt als Eingabe zu zählen. */
    void reset(UUID player) {
        anchors.remove(player);
    }

    int trackedPlayers() {
        return anchors.size();
    }

    /** Kleinster Winkel zwischen zwei Gier-Werten in Grad (0..180). */
    static float yawDifference(float from, float to) {
        float difference = (to - from) % 360.0f;
        if (difference < 0) {
            difference += 360.0f;
        }
        return difference > 180.0f ? 360.0f - difference : difference;
    }

    private static final class Anchor {
        @Nullable UUID world;
        double x;
        double y;
        double z;
        float yaw;
        float pitch;
        long checkedAt;

        Anchor(@Nullable UUID world, double x, double y, double z, float yaw, float pitch, long checkedAt) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.checkedAt = checkedAt;
        }

        void moveTo(double newX, double newY, double newZ) {
            this.x = newX;
            this.y = newY;
            this.z = newZ;
        }

        void lookAt(float newYaw, float newPitch) {
            this.yaw = newYaw;
            this.pitch = newPitch;
        }
    }
}
