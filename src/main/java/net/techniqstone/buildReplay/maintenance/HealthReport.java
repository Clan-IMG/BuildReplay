package net.techniqstone.buildReplay.maintenance;

import java.time.Instant;
import java.util.List;
import net.techniqstone.buildReplay.recording.EngineStatus;
import net.techniqstone.buildReplay.recording.RecordingStats;
import net.techniqstone.buildReplay.session.SessionServiceStats;
import net.techniqstone.buildReplay.simplecloud.ClientHealth;
import net.techniqstone.buildReplay.state.SharedStateHealth;
import net.techniqstone.buildReplay.storage.StoreHealth;

/** Gesamtzustand dieser Instanz für {@code /buildreplay status}. */
public record HealthReport(
        Instant at,
        String serverName,
        String instanceId,
        String pluginVersion,
        ClientHealth simpleCloud,
        boolean javaApiAvailable,
        StoreHealth database,
        SharedStateHealth redis,
        EngineStatus engine,
        RecordingStats recordings,
        SessionServiceStats sessions,
        List<String> warnings) {

    public HealthReport {
        warnings = List.copyOf(warnings);
    }
}
