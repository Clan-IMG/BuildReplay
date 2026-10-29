package net.techniqstone.buildReplay.state;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** Kurzlebige Aktivitätsinformation eines Spielers, übersteht Serverwechsel. */
public record ActivityStamp(@Nullable Instant lastRelevant, Instant lastInput, boolean afk, String serverName) {
}
