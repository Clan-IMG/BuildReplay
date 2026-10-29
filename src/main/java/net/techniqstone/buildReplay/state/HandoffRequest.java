package net.techniqstone.buildReplay.state;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Auftrag an einen anderen Server, für einen Spieler nach dem Wechsel ein Replay zu starten (der Abschnitt kann
 * nur dort abgespielt werden, wo er aufgenommen wurde). {@code sessionOwner}/{@code sessionRef} gesetzt = ganze
 * Sitzung ab diesem Abschnitt abspielen.
 */
public record HandoffRequest(
        long segmentId,
        String replayName,
        String targetServer,
        String requestedOn,
        @Nullable UUID sessionOwner,
        @Nullable String sessionRef,
        Instant createdAt) {
}
