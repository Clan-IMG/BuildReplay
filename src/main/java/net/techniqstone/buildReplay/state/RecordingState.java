package net.techniqstone.buildReplay.state;

import java.time.Instant;

/** Aktueller Aufnahmestatus eines Spielers (informativ, z. B. für Status und Diagnose). */
public record RecordingState(String rawName, String serverName, String instanceId, Instant since) {
}
