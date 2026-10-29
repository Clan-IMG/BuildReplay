package net.techniqstone.buildReplay.state;

import java.time.Instant;

/** Herzschlag einer BuildReplay-Instanz. */
public record InstanceInfo(String instanceId, String serverName, String pluginVersion, Instant startedAt,
                           Instant lastBeat) {
}
