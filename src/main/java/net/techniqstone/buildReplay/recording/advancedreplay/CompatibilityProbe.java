package net.techniqstone.buildReplay.recording.advancedreplay;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import me.jumper251.replay.replaysystem.utils.entities.PacketNPC;
import org.jspecify.annotations.Nullable;

/**
 * Prüft, ob AdvancedReplay auf diesem Server Spieler-NPCs erzeugen kann, ohne etwas zu senden: baut wie
 * {@code PacketNPC.spawn} (bzw. AR's eigenes {@code ProtocolLibUtil.prepare}) das PlayerInfo-ADD-Paket.
 * Auf Leaf 1.21.11 mit ProtocolLib 5.5.0-SNAPSHOT wirft AR's relocateter
 * {@code WrapperPlayServerPlayerInfo#setData} dabei "Field index 1 is out of bounds" (mit ProtocolLib 5.4.0
 * "Unsupported getId() method") - dann scheitert jede Wiedergabe in {@code executeTick(0)}, nachdem AR das
 * Inventar des Zuschauers bereits geleert hat. Die Probe selbst bringt das Plugin nie zum Absturz.
 */
final class CompatibilityProbe {

    /** Ergebnis der Probe; {@code reason} ist eine deutsche Begründung für Status und Spieler-Meldung. */
    record Result(boolean compatible, @Nullable String reason) {

        static Result unknown() {
            return new Result(false, "Kompatibilität von AdvancedReplay wurde noch nicht geprüft");
        }

        static Result unavailable() {
            return new Result(false, "AdvancedReplay ist nicht aktiv");
        }
    }

    private static final int MAX_DETAIL = 200;

    private CompatibilityProbe() {
    }

    /** Hauptthread (erzeugt ProtocolLib-Pakete, sendet aber nichts). */
    static Result run(String advancedReplayVersion, String protocolLibVersion, String serverVersion) {
        try {
            PacketNPC npc = new PacketNPC(ThreadLocalRandom.current().nextInt(600_000, 900_000), UUID.randomUUID(),
                    "brprobe");
            npc.getInfoAddPacket();
            return new Result(true, null);
        } catch (Throwable t) {
            return new Result(false, reason(advancedReplayVersion, protocolLibVersion, serverVersion, t));
        }
    }

    static String reason(String advancedReplayVersion, String protocolLibVersion, String serverVersion,
                         Throwable error) {
        String message = error.getMessage() == null ? "" : error.getMessage().replace('\n', ' ');
        if (message.length() > MAX_DETAIL) {
            message = message.substring(0, MAX_DETAIL) + "...";
        }
        return "AdvancedReplay " + advancedReplayVersion + " kann mit ProtocolLib " + protocolLibVersion + " auf "
                + serverVersion + " keine Spieler-NPCs erzeugen (" + error.getClass().getSimpleName()
                + (message.isEmpty() ? "" : ": " + message) + "). Die Wiedergabe ist deaktiviert, bis eine "
                + "passende ProtocolLib- oder AdvancedReplay-Version installiert ist.";
    }
}
