package net.techniqstone.buildReplay.maintenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.techniqstone.buildReplay.config.EnvResolver;
import net.techniqstone.buildReplay.config.PluginConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Die mitgelieferte config.yml passt zu {@link PluginConfig}: alle Schlüssel vorhanden und gelesen, keine
 * Bereichs-/Tippfehler-Warnungen, Verhaltenswerte gleich den eingebauten Standardwerten. Verbindungsdaten
 * (Host, Port, Benutzer) dürfen für das Netzwerk angepasst sein; Geheimnisse stehen nur als Platzhalter
 * ({@code ${env:...}} oder SimpleCloud {@code {{ secrets.* }}}/{@code {{ env.* }}}) in der Datei.
 */
class ShippedConfigFileTest {

    private static final Pattern ENV_PLACEHOLDER = Pattern.compile("\\$\\{env:([A-Za-z_][A-Za-z0-9_]*)(?::-[^}]*)?}");
    private static final Pattern CLOUD_PLACEHOLDER =
            Pattern.compile("\\{\\{\\s*(?:env|secrets)\\.([A-Za-z_][A-Za-z0-9_.-]*)\\s*}}");

    private static String shippedText() throws IOException {
        try (InputStream in = ShippedConfigFileTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "config.yml fehlt im Jar");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static YamlConfiguration shipped() throws IOException {
        return YamlConfiguration.loadConfiguration(new StringReader(shippedText()));
    }

    /** Alle Schlüssel, die {@code PluginConfig.from} liest (aus dem Quelltext des Vertrags). */
    private static Set<String> keysReadByPluginConfig() throws IOException {
        String source = Files.readString(Path.of("src/main/java/net/techniqstone/buildReplay/config/PluginConfig.java"),
                StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("r\\.[a-zA-Z]+\\(\"([a-z0-9.\\-]+)\"").matcher(source);
        Set<String> keys = new TreeSet<>();
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        keys.add("database.properties");
        assertTrue(keys.size() > 80, "Schlüssel gefunden: " + keys.size());
        return keys;
    }

    /** Namen aller Umgebungsvariablen/Secrets, auf die die Datei verweist. */
    private static Set<String> referencedVariables() throws IOException {
        String text = shippedText();
        Set<String> names = new TreeSet<>();
        for (Pattern pattern : List.of(ENV_PLACEHOLDER, CLOUD_PLACEHOLDER)) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
        }
        return names;
    }

    private static boolean credentialWarning(String warning) {
        return warning.startsWith("SimpleCloud nicht vollständig konfiguriert")
                || warning.contains("SimpleCloud-Platzhalter");
    }

    @Test
    void theShippedConfigLoadsWithoutWarningsExceptMissingCredentials() throws IOException {
        List<String> warnings = new ArrayList<>();

        PluginConfig.from(shipped(), new EnvResolver(name -> null), warnings);

        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("SimpleCloud nicht vollständig konfiguriert")),
                warnings.toString());
        assertEquals(List.of(), warnings.stream().filter(w -> !credentialWarning(w)).toList(),
                "nur fehlende Zugangsdaten dürfen gemeldet werden");
    }

    @Test
    void theShippedBehaviourSettingsEqualTheBuiltInDefaults() throws IOException {
        PluginConfig defaults = PluginConfig.defaults();
        PluginConfig loaded = PluginConfig.from(shipped(), new EnvResolver(name -> null), new ArrayList<>());

        assertEquals(defaults.serverNameOverride(), loaded.serverNameOverride());
        assertEquals(defaults.logLevel(), loaded.logLevel());
        assertEquals(defaults.recording(), loaded.recording());
        assertEquals(defaults.activity(), loaded.activity());
        assertEquals(defaults.display(), loaded.display());
        assertEquals(defaults.playback(), loaded.playback());
        assertEquals(defaults.storage(), loaded.storage());
        assertEquals(defaults.resilience(), loaded.resilience());

        PluginConfig.SimpleCloudSettings cloud = loaded.simpleCloud();
        PluginConfig.SimpleCloudSettings cloudDefaults = defaults.simpleCloud();
        assertEquals(cloudDefaults.baseUrl(), cloud.baseUrl());
        assertEquals(new PluginConfig.SimpleCloudSettings(cloud.baseUrl(), cloud.networkId(), cloud.networkSecret(),
                cloudDefaults.useJavaApi(), cloudDefaults.connectTimeout(), cloudDefaults.requestTimeout(),
                cloudDefaults.maxRetries(), cloudDefaults.retryBaseDelay(), cloudDefaults.retryMaxDelay(),
                cloudDefaults.circuitFailureThreshold(), cloudDefaults.circuitOpenDuration(),
                cloudDefaults.onlinePollInterval(), cloudDefaults.sessionCacheTtl(),
                cloudDefaults.currentSessionRetryWindow(), cloudDefaults.sessionListCacheTtl()), cloud);

        // Verbindungsdaten (Host, Port, Benutzer, Passwort) sind netzwerkspezifisch, der Rest nicht
        PluginConfig.DatabaseSettings db = loaded.database();
        PluginConfig.DatabaseSettings dbDefaults = defaults.database();
        assertEquals(new PluginConfig.DatabaseSettings(dbDefaults.enabled(), db.host(), db.port(), dbDefaults.database(),
                db.username(), db.password(), dbDefaults.tablePrefix(), dbDefaults.poolSize(),
                dbDefaults.connectionTimeout(), dbDefaults.properties()), db);
        PluginConfig.RedisSettings redis = loaded.redis();
        PluginConfig.RedisSettings redisDefaults = defaults.redis();
        assertEquals(new PluginConfig.RedisSettings(redisDefaults.enabled(), redis.host(), redis.port(),
                redis.username(), redis.password(), redisDefaults.database(), redisDefaults.ssl(),
                redisDefaults.keyPrefix(), redisDefaults.timeout(), redisDefaults.poolSize()), redis);
    }

    @Test
    void theShippedConfigHasTheCurrentVersion() throws IOException {
        assertEquals(PluginConfig.CURRENT_CONFIG_VERSION, shipped().getInt("config-version"));
    }

    @Test
    void everyKeyReadByThePluginIsPresentAndEveryShippedKeyIsRead() throws IOException {
        YamlConfiguration yaml = shipped();
        Set<String> read = keysReadByPluginConfig();

        for (String key : read) {
            assertTrue(yaml.contains(key, true), "fehlt in config.yml: " + key);
        }
        for (String key : yaml.getKeys(true)) {
            if (yaml.isConfigurationSection(key) || key.equals("config-version")) {
                continue;
            }
            assertTrue(read.contains(key), "config.yml enthält einen Schlüssel, den niemand liest: " + key);
        }
    }

    @Test
    void secretsAreNeverStoredInPlainText() throws IOException {
        YamlConfiguration yaml = shipped();

        for (String key : List.of("database.password", "redis.password", "simplecloud.network-secret",
                "simplecloud.network-id")) {
            String raw = yaml.getString(key, "");
            assertTrue(ENV_PLACEHOLDER.matcher(raw).matches() || CLOUD_PLACEHOLDER.matcher(raw).matches(),
                    key + " muss ein Platzhalter sein, ist aber '" + (raw.isEmpty() ? "" : "<Wert>") + "'");
        }
    }

    @Test
    void allPlaceholdersAreResolvedWhenTheEnvironmentProvidesThem() throws IOException {
        Map<String, String> environment = new HashMap<>();
        for (String name : referencedVariables()) {
            environment.put(name, "wert-" + name.toLowerCase());
        }
        assertFalse(environment.isEmpty(), "keine Platzhalter gefunden");
        List<String> warnings = new ArrayList<>();

        PluginConfig config = PluginConfig.from(shipped(), new EnvResolver(environment::get), warnings);

        assertEquals(List.of(), warnings);
        assertTrue(config.simpleCloud().configured());
        assertFalse(config.database().password().isEmpty());
        assertFalse(config.redis().password().isEmpty());
        assertTrue(config.database().password().startsWith("wert-"), "Passwort aus der Umgebung");
        assertTrue(config.simpleCloud().networkSecret().startsWith("wert-"), "Secret aus der Umgebung");
    }
}
