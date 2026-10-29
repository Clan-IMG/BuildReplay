package net.techniqstone.buildReplay.maintenance;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.techniqstone.buildReplay.recording.EngineStatus;
import net.techniqstone.buildReplay.recording.RecordingStats;
import net.techniqstone.buildReplay.session.SessionServiceStats;
import net.techniqstone.buildReplay.simplecloud.ClientHealth;
import net.techniqstone.buildReplay.state.SharedStateHealth;
import net.techniqstone.buildReplay.storage.StoreHealth;

/**
 * Woher {@link HealthService} die Zustände der Komponenten bezieht.
 *
 * <ul>
 *   <li>I/O-Seite ({@code simpleCloud}, {@code javaApiAvailable}, {@code database}, {@code redis}): werden
 *       ausschließlich auf dem I/O-Pool abgefragt und zwischengespeichert (z. B. {@code SharedState#health} darf
 *       laut Vertrag blockieren), z. B. {@code client::health}, {@code platform::isAvailable}, {@code store::health},
 *       {@code shared::health}.</li>
 *   <li>Hauptthread-Seite ({@code engine}, {@code recordings}, {@code sessions}): werden bei jedem
 *       {@link HealthService#report()} auf dem Hauptthread gelesen, z. B. {@code engine::status},
 *       {@code recordings::stats}, {@code sessions::stats}.</li>
 * </ul>
 */
public record HealthSources(
        Supplier<ClientHealth> simpleCloud,
        BooleanSupplier javaApiAvailable,
        Supplier<StoreHealth> database,
        Supplier<SharedStateHealth> redis,
        Supplier<EngineStatus> engine,
        Supplier<RecordingStats> recordings,
        Supplier<SessionServiceStats> sessions) {

    public HealthSources {
        Objects.requireNonNull(simpleCloud, "simpleCloud");
        Objects.requireNonNull(javaApiAvailable, "javaApiAvailable");
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(redis, "redis");
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(recordings, "recordings");
        Objects.requireNonNull(sessions, "sessions");
    }
}
