package net.techniqstone.buildReplay.config;

import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import net.techniqstone.buildReplay.activity.ActivityType;
import org.bukkit.GameMode;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

/**
 * Unveränderliche, validierte Sicht auf die {@code config.yml}. Alle Werte haben Standardwerte und werden auf
 * sinnvolle Grenzen geklemmt; Probleme landen in {@code warnings} statt das Plugin abstürzen zu lassen.
 * Geheimnisse (DB-/Redis-Passwort, SimpleCloud-Secret) werden über {@code ${env:NAME}} aus der Umgebung gelesen.
 */
public record PluginConfig(
        String serverNameOverride,
        Level logLevel,
        SimpleCloudSettings simpleCloud,
        DatabaseSettings database,
        RedisSettings redis,
        RecordingSettings recording,
        ActivitySettings activity,
        DisplaySettings display,
        PlaybackSettings playback,
        StorageSettings storage,
        ResilienceSettings resilience) {

    public static final int CURRENT_CONFIG_VERSION = 1;

    /** SimpleCloud-REST ({@code /v0/players/...}) und optionale Java-API. */
    public record SimpleCloudSettings(
            String baseUrl,
            String networkId,
            String networkSecret,
            boolean useJavaApi,
            Duration connectTimeout,
            Duration requestTimeout,
            int maxRetries,
            Duration retryBaseDelay,
            Duration retryMaxDelay,
            int circuitFailureThreshold,
            Duration circuitOpenDuration,
            Duration onlinePollInterval,
            Duration sessionCacheTtl,
            Duration currentSessionRetryWindow,
            Duration sessionListCacheTtl) {

        public boolean configured() {
            return !baseUrl.isBlank() && !networkId.isBlank() && !networkSecret.isBlank();
        }

        /** Ohne Secret - die Einstellungen dürfen gefahrlos protokolliert werden. */
        @Override
        public String toString() {
            return "SimpleCloudSettings[baseUrl=" + baseUrl + ", networkId=" + networkId + ", networkSecret="
                    + redacted(networkSecret) + ", useJavaApi=" + useJavaApi + ", connectTimeout=" + connectTimeout
                    + ", requestTimeout=" + requestTimeout + ", maxRetries=" + maxRetries + "]";
        }
    }

    public record DatabaseSettings(
            boolean enabled,
            String host,
            int port,
            String database,
            String username,
            String password,
            String tablePrefix,
            int poolSize,
            Duration connectionTimeout,
            Map<String, String> properties) {

        /** Ohne Passwort und JDBC-Parameter (können Zugangsdaten enthalten). */
        @Override
        public String toString() {
            return "DatabaseSettings[enabled=" + enabled + ", host=" + host + ", port=" + port + ", database="
                    + database + ", username=" + username + ", password=" + redacted(password) + ", tablePrefix="
                    + tablePrefix + ", poolSize=" + poolSize + ", connectionTimeout=" + connectionTimeout
                    + ", properties=" + properties.keySet() + "]";
        }
    }

    public record RedisSettings(
            boolean enabled,
            String host,
            int port,
            String username,
            String password,
            int database,
            boolean ssl,
            String keyPrefix,
            Duration timeout,
            int poolSize) {

        /** Ohne Passwort. */
        @Override
        public String toString() {
            return "RedisSettings[enabled=" + enabled + ", host=" + host + ", port=" + port + ", username=" + username
                    + ", password=" + redacted(password) + ", database=" + database + ", ssl=" + ssl + ", keyPrefix="
                    + keyPrefix + ", timeout=" + timeout + ", poolSize=" + poolSize + "]";
        }
    }

    /** Aufnahme: Pufferaufnahme pro Spieler, Abschnitte mit Vor-/Nachlauf. */
    public record RecordingSettings(
            boolean enabled,
            List<String> worlds,
            List<String> excludedWorlds,
            Set<GameMode> excludedGameModes,
            Duration preRoll,
            Duration postRoll,
            int minRelevantActions,
            Duration minSegmentLength,
            Duration maxSegmentLength,
            Duration mergeGap,
            Duration rotationInterval,
            boolean stopWhileAfk,
            boolean blockFidelity) {

        /** Ob in dieser Welt aufgezeichnet werden darf (Whitelist leer = alle, Blacklist hat Vorrang). */
        public boolean recordsWorld(String worldName) {
            if (excludedWorlds.stream().anyMatch(w -> w.equalsIgnoreCase(worldName))) {
                return false;
            }
            return worlds.isEmpty() || worlds.stream().anyMatch(w -> w.equalsIgnoreCase(worldName));
        }
    }

    /** Aktivitäts- und AFK-Erkennung. */
    public record ActivitySettings(
            Duration afkTimeout,
            Duration relevantIdleTimeout,
            Duration maxIndicatorExtension,
            Set<ActivityType> relevantTypes,
            Set<ActivityType> indicatorTypes,
            List<String> editCommandPrefixes,
            Set<String> editCommands,
            double movementThreshold,
            float lookThresholdDegrees) {

        public boolean isRelevant(ActivityType type) {
            return relevantTypes.contains(type);
        }

        public boolean isIndicator(ActivityType type) {
            return indicatorTypes.contains(type) && !relevantTypes.contains(type);
        }
    }

    public record DisplaySettings(
            int historyDays,
            int pageSize,
            int maxSessions,
            ZoneId timeZone,
            String dateTimePattern) {
    }

    public record PlaybackSettings(
            boolean forceClientSideBlockChanges,
            boolean protectInventory,
            boolean crossServer,
            Duration handoffTimeout,
            boolean showInitialState,
            boolean autoAdvance) {
    }

    public record StorageSettings(
            int retentionDays,
            Duration cleanupInterval,
            int maxSegmentsPerPlayer) {
    }

    /** Verhalten bei Ausfällen von MariaDB, Redis und SimpleCloud. */
    public record ResilienceSettings(
            int outboxMaxEntries,
            Duration outboxFlushInterval,
            Duration healthCheckInterval,
            boolean provisionalSessions,
            Duration presenceTtl,
            Duration switchGrace,
            Duration heartbeatInterval,
            Duration leaderLease) {
    }

    /** Standardkonfiguration (entspricht der mitgelieferten config.yml), z. B. für Tests. */
    public static PluginConfig defaults() {
        return from(new MemoryConfiguration(), new EnvResolver(name -> null), new ArrayList<>());
    }

    public static PluginConfig from(ConfigurationSection root, EnvResolver env, List<String> warnings) {
        Reader r = new Reader(root, env, warnings);

        Level level = switch (r.string("logging.level", "INFO").toUpperCase(Locale.ROOT)) {
            case "DEBUG", "FINE" -> Level.FINE;
            case "TRACE", "FINEST", "ALL" -> Level.FINEST;
            case "WARN", "WARNING" -> Level.WARNING;
            default -> Level.INFO;
        };

        // Fehlen die Werte (oder blieb ein {{ ... }}-Platzhalter unaufgelöst), gelten die Variablen, die SimpleCloud
        // jedem Server mitgibt.
        String controllerUrl = r.secret("simplecloud.base-url",
                "${env:SIMPLECLOUD_CONTROLLER_URL:-https://controller.simplecloud.app}", "SIMPLECLOUD_CONTROLLER_URL");
        SimpleCloudSettings simpleCloud = new SimpleCloudSettings(
                trimTrailingSlash(controllerUrl.isEmpty() ? "https://controller.simplecloud.app" : controllerUrl),
                r.secret("simplecloud.network-id", "${env:SIMPLECLOUD_NETWORK_ID}", "SIMPLECLOUD_NETWORK_ID"),
                r.secret("simplecloud.network-secret", "${env:SIMPLECLOUD_NETWORK_SECRET}", "SIMPLECLOUD_NETWORK_SECRET"),
                r.bool("simplecloud.use-java-api", true),
                r.seconds("simplecloud.connect-timeout-seconds", 5, 1, 60),
                r.seconds("simplecloud.request-timeout-seconds", 10, 1, 120),
                r.integer("simplecloud.max-retries", 3, 0, 10),
                r.millis("simplecloud.retry-base-delay-millis", 500, 50, 60_000),
                r.millis("simplecloud.retry-max-delay-millis", 8_000, 100, 300_000),
                r.integer("simplecloud.circuit-breaker.failure-threshold", 5, 1, 1000),
                r.seconds("simplecloud.circuit-breaker.open-seconds", 30, 1, 3600),
                r.seconds("simplecloud.online-poll-seconds", 60, 10, 3600),
                r.seconds("simplecloud.session-cache-seconds", 21_600, 60, 7 * 86_400),
                r.seconds("simplecloud.current-session-retry-seconds", 20, 0, 300),
                r.seconds("simplecloud.session-list-cache-seconds", 30, 0, 3600));

        Map<String, String> jdbcProperties = new LinkedHashMap<>();
        ConfigurationSection props = root.getConfigurationSection("database.properties");
        if (props != null) {
            for (String key : props.getKeys(false)) {
                jdbcProperties.put(key, env.resolve(String.valueOf(props.get(key))));
            }
        }
        DatabaseSettings database = new DatabaseSettings(
                r.bool("database.enabled", true),
                r.secret("database.host", "127.0.0.1"),
                r.integer("database.port", 3306, 1, 65_535),
                r.secret("database.database", "buildreplay"),
                r.secret("database.username", "buildreplay"),
                r.secret("database.password", "${env:BUILDREPLAY_DB_PASSWORD}"),
                r.identifier("database.table-prefix", "br_"),
                r.integer("database.pool-size", 6, 1, 64),
                r.millis("database.connection-timeout-millis", 5_000, 250, 60_000),
                Map.copyOf(jdbcProperties));

        RedisSettings redis = new RedisSettings(
                r.bool("redis.enabled", true),
                r.secret("redis.host", "127.0.0.1"),
                r.integer("redis.port", 6379, 1, 65_535),
                r.secret("redis.username", ""),
                r.secret("redis.password", "${env:BUILDREPLAY_REDIS_PASSWORD}"),
                r.integer("redis.database", 0, 0, 255),
                r.bool("redis.ssl", false),
                r.string("redis.key-prefix", "buildreplay:"),
                r.millis("redis.timeout-millis", 2_000, 100, 60_000),
                r.integer("redis.pool-size", 8, 1, 128));

        Set<GameMode> excludedModes = EnumSet.noneOf(GameMode.class);
        for (String mode : r.stringList("recording.excluded-gamemodes", List.of("SPECTATOR"))) {
            try {
                excludedModes.add(GameMode.valueOf(mode.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                warnings.add("recording.excluded-gamemodes: unbekannter Spielmodus '" + mode + "'");
            }
        }
        Duration maxSegment = r.minutes("recording.max-segment-minutes", 30, 1, 240);
        Duration rotation = r.minutes("recording.rotation-minutes", 20, 1, 240);
        RecordingSettings recording = new RecordingSettings(
                r.bool("recording.enabled", true),
                List.copyOf(r.stringList("recording.worlds", List.of())),
                List.copyOf(r.stringList("recording.excluded-worlds", List.of())),
                Set.copyOf(excludedModes.isEmpty() ? EnumSet.noneOf(GameMode.class) : excludedModes),
                r.seconds("recording.pre-roll-seconds", 10, 0, 120),
                r.seconds("recording.post-roll-seconds", 10, 0, 120),
                r.integer("recording.min-relevant-actions", 3, 1, 10_000),
                r.seconds("recording.min-segment-seconds", 5, 0, 3_600),
                maxSegment,
                r.seconds("recording.merge-gap-seconds", 20, 0, 600),
                rotation,
                r.bool("recording.stop-while-afk", true),
                r.bool("recording.block-fidelity", true));

        Set<ActivityType> relevant = r.activityTypes("activity.relevant-actions", true);
        Set<ActivityType> indicators = r.activityTypes("activity.indicators", false);
        for (ActivityType both : relevant) {
            if (indicators.remove(both)) {
                warnings.add("activity: '" + both.configKey() + "' steht in relevant-actions und indicators - "
                        + "zählt als relevante Aktion");
            }
        }
        Set<String> editCommands = new LinkedHashSet<>();
        for (String command : r.stringList("activity.edit-commands", List.of(
                "brush", "br", "fill", "setblock", "clone", "paste", "undo", "redo", "replacenear",
                "removenear", "drain", "fixwater", "fixlava", "snow", "thaw", "green", "forestgen",
                "pumpkins", "tool"))) {
            String normalized = command.trim().toLowerCase(Locale.ROOT);
            while (normalized.startsWith("/")) {
                normalized = normalized.substring(1);
            }
            if (!normalized.isEmpty()) {
                editCommands.add(normalized);
            }
        }
        Duration afkTimeout = r.seconds("activity.afk-timeout-seconds", 60, 5, 3_600);
        Duration relevantIdle = r.seconds("activity.relevant-idle-seconds", 60, 5, 3_600);
        Duration maxExtension = r.seconds("activity.max-indicator-extension-seconds", 240, 0, 7_200);
        if (maxExtension.compareTo(relevantIdle) < 0) {
            warnings.add("activity.max-indicator-extension-seconds < relevant-idle-seconds - Indikatoren verlängern "
                    + "Abschnitte damit nicht");
        }
        ActivitySettings activity = new ActivitySettings(
                afkTimeout,
                relevantIdle,
                maxExtension,
                Set.copyOf(relevant.isEmpty() ? EnumSet.noneOf(ActivityType.class) : relevant),
                Set.copyOf(indicators.isEmpty() ? EnumSet.noneOf(ActivityType.class) : indicators),
                List.copyOf(r.stringList("activity.edit-command-prefixes", List.of("//"))),
                Set.copyOf(editCommands),
                r.decimal("activity.movement-threshold-blocks", 0.3, 0.0, 16.0),
                (float) r.decimal("activity.look-threshold-degrees", 15.0, 0.0, 180.0));

        ZoneId zone;
        String zoneName = r.string("display.time-zone", "Europe/Berlin");
        try {
            zone = ZoneId.of(zoneName);
        } catch (RuntimeException e) {
            warnings.add("display.time-zone: unbekannte Zeitzone '" + zoneName + "', verwende Europe/Berlin");
            zone = ZoneId.of("Europe/Berlin");
        }
        String pattern = r.string("display.date-time-pattern", "dd.MM.yyyy HH:mm");
        try {
            java.time.format.DateTimeFormatter.ofPattern(pattern);
        } catch (IllegalArgumentException e) {
            warnings.add("display.date-time-pattern ungültig ('" + pattern + "'), verwende dd.MM.yyyy HH:mm");
            pattern = "dd.MM.yyyy HH:mm";
        }
        DisplaySettings display = new DisplaySettings(
                r.integer("display.history-days", 14, 1, 365),
                r.integer("display.page-size", 8, 1, 50),
                r.integer("display.max-sessions", 200, 1, 5_000),
                zone,
                pattern);

        PlaybackSettings playback = new PlaybackSettings(
                r.bool("playback.force-client-side-block-changes", true),
                r.bool("playback.protect-inventory", true),
                r.bool("playback.cross-server", true),
                r.seconds("playback.handoff-timeout-seconds", 60, 5, 600),
                r.bool("playback.show-initial-state", true),
                r.bool("playback.auto-advance", true));

        StorageSettings storage = new StorageSettings(
                r.integer("storage.retention-days", 30, 0, 3_650),
                r.minutes("storage.cleanup-interval-minutes", 60, 1, 1_440),
                r.integer("storage.max-segments-per-player", 1_000, 0, 1_000_000));

        ResilienceSettings resilience = new ResilienceSettings(
                r.integer("resilience.outbox-max-entries", 10_000, 100, 1_000_000),
                r.seconds("resilience.outbox-flush-seconds", 5, 1, 600),
                r.seconds("resilience.health-check-seconds", 15, 5, 600),
                r.bool("resilience.provisional-sessions", true),
                r.seconds("resilience.presence-ttl-seconds", 45, 10, 600),
                r.seconds("resilience.switch-grace-seconds", 30, 5, 600),
                r.seconds("resilience.heartbeat-seconds", 15, 5, 300),
                r.seconds("resilience.leader-lease-seconds", 30, 10, 600));

        if (resilience.heartbeatInterval().multipliedBy(2).compareTo(resilience.presenceTtl()) > 0) {
            warnings.add("resilience.presence-ttl-seconds sollte mindestens doppelt so groß wie heartbeat-seconds sein");
        }
        if (!simpleCloud.configured()) {
            warnings.add("SimpleCloud nicht vollständig konfiguriert (base-url/network-id/network-secret) - "
                    + "Sitzungen werden nur vorläufig erfasst");
        }

        return new PluginConfig(r.string("server-name", ""), level, simpleCloud, database, redis, recording,
                activity, display, playback, storage, resilience);
    }

    /** Platzhalter für Geheimnisse in {@code toString()}: nur, ob ein Wert gesetzt ist. */
    private static String redacted(String secret) {
        return secret == null || secret.isEmpty() ? "<leer>" : "***";
    }

    private static String trimTrailingSlash(String url) {
        String result = url == null ? "" : url.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /** Hilfsklasse zum Lesen mit Standardwert, Begrenzung und Warnung. */
    private static final class Reader {
        private final ConfigurationSection root;
        private final EnvResolver env;
        private final List<String> warnings;

        Reader(ConfigurationSection root, EnvResolver env, List<String> warnings) {
            this.root = root;
            this.env = env;
            this.warnings = warnings;
        }

        String string(String path, String def) {
            Object value = root.get(path);
            return value == null ? def : String.valueOf(value).trim();
        }

        /**
         * Wie {@link #string}, aber mit Auflösung von {@code ${env:...}}, {@code {{ secrets.X }}} und
         * {@code {{ env.X }}} (auch im Standardwert). Unaufgelöste SimpleCloud-Platzhalter werden gemeldet - nur der
         * Platzhalter-Name, nie ein Wert.
         */
        String secret(String path, String def) {
            return secret(path, def, null);
        }

        /** Wie {@link #secret(String, String)}; ist das Ergebnis leer, gilt die Umgebungsvariable {@code fallbackEnv}. */
        String secret(String path, String def, String fallbackEnv) {
            EnvResolver.Resolved resolved = env.resolveDetailed(string(path, def));
            String value = resolved.value() == null ? "" : resolved.value().trim();
            String fallback = fallbackEnv == null ? null : env.variable(fallbackEnv);
            boolean useFallback = value.isEmpty() && fallback != null;
            if (!resolved.complete()) {
                warnings.add(path + ": SimpleCloud-Platzhalter " + String.join(", ", resolved.unresolved())
                        + " wurde nicht ersetzt. SimpleCloud befüllt {{ secrets.* }}/{{ env.* }} nur in Dateien aus einem"
                        + " Template (z. B. templates/<server>/plugins/BuildReplay/config.yml), die vor dem ersten Start"
                        + " vorhanden sind" + (useFallback ? " - verwende stattdessen " + fallbackEnv : " - Wert bleibt leer"));
            }
            return useFallback ? fallback : value;
        }

        boolean bool(String path, boolean def) {
            Object value = root.get(path);
            if (value == null) {
                return def;
            }
            if (value instanceof Boolean b) {
                return b;
            }
            String text = String.valueOf(value).trim();
            if (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false")) {
                return Boolean.parseBoolean(text);
            }
            warnings.add(path + ": '" + text + "' ist kein Wahrheitswert, verwende " + def);
            return def;
        }

        int integer(String path, int def, int min, int max) {
            Object value = root.get(path);
            int result = def;
            if (value != null) {
                try {
                    result = value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value).trim());
                } catch (NumberFormatException e) {
                    warnings.add(path + ": '" + value + "' ist keine Zahl, verwende " + def);
                }
            }
            if (result < min || result > max) {
                int clamped = Math.max(min, Math.min(max, result));
                warnings.add(path + ": " + result + " liegt außerhalb [" + min + ", " + max + "], verwende " + clamped);
                result = clamped;
            }
            return result;
        }

        double decimal(String path, double def, double min, double max) {
            Object value = root.get(path);
            double result = def;
            if (value != null) {
                try {
                    result = value instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(value).trim());
                } catch (NumberFormatException e) {
                    warnings.add(path + ": '" + value + "' ist keine Zahl, verwende " + def);
                }
            }
            if (result < min || result > max || Double.isNaN(result)) {
                double clamped = Double.isNaN(result) ? def : Math.max(min, Math.min(max, result));
                warnings.add(path + ": " + result + " liegt außerhalb [" + min + ", " + max + "], verwende " + clamped);
                result = clamped;
            }
            return result;
        }

        Duration seconds(String path, int def, int min, int max) {
            return Duration.ofSeconds(integer(path, def, min, max));
        }

        Duration millis(String path, int def, int min, int max) {
            return Duration.ofMillis(integer(path, def, min, max));
        }

        Duration minutes(String path, int def, int min, int max) {
            return Duration.ofMinutes(integer(path, def, min, max));
        }

        List<String> stringList(String path, List<String> def) {
            if (!root.contains(path)) {
                return def;
            }
            List<String> values = new ArrayList<>();
            for (String entry : root.getStringList(path)) {
                if (entry != null && !entry.isBlank()) {
                    values.add(entry.trim());
                }
            }
            return values;
        }

        String identifier(String path, String def) {
            String value = string(path, def);
            if (!value.matches("[A-Za-z0-9_]{0,32}")) {
                warnings.add(path + ": nur Buchstaben, Ziffern und _ (max. 32) erlaubt, verwende '" + def + "'");
                return def;
            }
            return value;
        }

        Set<ActivityType> activityTypes(String path, boolean relevantList) {
            Set<ActivityType> result = EnumSet.noneOf(ActivityType.class);
            if (!root.contains(path)) {
                for (ActivityType type : ActivityType.values()) {
                    if (type.relevantByDefault() == relevantList) {
                        result.add(type);
                    }
                }
                return result;
            }
            for (String key : root.getStringList(path)) {
                ActivityType.fromConfigKey(key).ifPresentOrElse(result::add,
                        () -> warnings.add(path + ": unbekannte Aktivität '" + key + "'"));
            }
            return result;
        }
    }
}
