package net.techniqstone.buildReplay.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.techniqstone.buildReplay.BuildReplay;
import net.techniqstone.buildReplay.command.Permissions;
import net.techniqstone.buildReplay.config.EnvResolver;
import net.techniqstone.buildReplay.config.PluginConfig;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginLoadOrder;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

/**
 * Die mitgelieferten Dateien (plugin.yml, config.yml, messages.yml) laden so, wie der Server sie liest, und jeder
 * Nachrichtenschlüssel, den der Code verwendet, steht mit passenden Platzhaltern in der messages.yml.
 */
class ShippedResourcesIntegrationTest {

    /** Erste Ebenen der messages.yml - nur solche Literale gelten als Nachrichtenschlüssel. */
    private static final Pattern MESSAGE_KEY = Pattern.compile(
            "\"((?:command|help|sessions|session|status|admin|error|playback)(?:\\.[a-z0-9-]+)+)\"");
    /** {@code send(empfänger, "schlüssel", "Fallback" + ...)}, {@code component("schlüssel", "Fallback")}, {@code new Text(...)}. */
    private static final Pattern KEY_WITH_FALLBACK = Pattern.compile(
            "(?:send\\(\\s*\\w+\\s*,|component\\(|new Text\\()\\s*\"([a-z][a-z0-9-]*(?:\\.[a-z0-9-]+)+)\"\\s*,\\s*"
                    + "((?:\"(?:[^\"\\\\]|\\\\.)*\"\\s*\\+?\\s*)+)", Pattern.DOTALL);
    private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z]+)}");
    /** Konfigurationspfade sehen aus wie Nachrichtenschlüssel ({@code playback.cross-server}). */
    private static final Set<String> NOT_MESSAGE_SOURCES = Set.of("PluginConfig.java");

    private static InputStream resource(String name) {
        InputStream in = ShippedResourcesIntegrationTest.class.getResourceAsStream("/" + name);
        assertNotNull(in, name + " fehlt im Jar");
        return in;
    }

    private static YamlConfiguration yaml(String name) throws IOException {
        try (InputStream in = resource(name)) {
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    /** Wie {@link #yaml}, aber mit '/' als Pfadtrenner - Berechtigungsnamen enthalten Punkte. */
    private static YamlConfiguration yamlWithDottedKeys(String name) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().pathSeparator('/');
        try (InputStream in = resource(name)) {
            yaml.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        return yaml;
    }

    private static List<Path> mainSources() throws IOException {
        Path root = Path.of(System.getProperty("user.dir"), "src", "main", "java");
        assertTrue(Files.isDirectory(root), "Quellcode nicht gefunden unter " + root);
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> !NOT_MESSAGE_SOURCES.contains(file.getFileName().toString()))
                    .sorted()
                    .toList();
        }
    }

    private static Set<String> placeholders(String text) {
        Set<String> names = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    // ------------------------------------------------------------------ plugin.yml

    @Test
    void pluginYmlIsAValidDescriptionWithExpandedVersionsAndTheRealMainClass() throws Exception {
        PluginDescriptionFile description;
        try (InputStream in = resource("plugin.yml")) {
            description = new PluginDescriptionFile(in);
        }
        assertEquals("BuildReplay", description.getName());
        assertEquals(BuildReplay.class.getName(), description.getMain());
        assertTrue(JavaPlugin.class.isAssignableFrom(Class.forName(description.getMain(), false,
                getClass().getClassLoader())), "Hauptklasse erweitert JavaPlugin");
        assertEquals("1.21", description.getAPIVersion());
        assertEquals(PluginLoadOrder.POSTWORLD, description.getLoad());
        assertFalse(description.getVersion().contains("${"), "Version ersetzt: " + description.getVersion());
        assertTrue(description.getDepend().containsAll(List.of("AdvancedReplay", "ProtocolLib")));
        assertTrue(description.getSoftDepend().contains("simplecloud-api"));
        assertEquals(3, description.getLibraries().size(), description.getLibraries().toString());
        for (String library : description.getLibraries()) {
            assertTrue(library.matches("[a-z0-9.\\-]+:[A-Za-z0-9.\\-]+:\\d+\\.\\d+(\\.\\d+)?"),
                    "Maven-Koordinate mit Version: " + library);
        }

        Map<String, Map<String, Object>> commands = description.getCommands();
        assertTrue(commands.containsKey("buildreplay"), commands.toString());
        assertEquals(List.of("brp", "buildreplays"), commands.get("buildreplay").get("aliases"));
    }

    @Test
    void everyPermissionUsedInCodeIsDeclaredInPluginYml() throws Exception {
        ConfigurationSection permissions = yamlWithDottedKeys("plugin.yml").getConfigurationSection("permissions");
        assertNotNull(permissions);
        Set<String> declared = permissions.getKeys(false);
        for (Field field : Permissions.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                String node = (String) field.get(null);
                assertTrue(declared.contains(node), "plugin.yml deklariert " + node);
            }
        }
        assertEquals(Boolean.FALSE, permissions.get(Permissions.EXEMPT + "/default"),
                "niemand ist ungewollt von der Aufnahme ausgenommen");
    }

    // ------------------------------------------------------------------ config.yml

    @Test
    void shippedConfigYmlLoadsWithoutWarningsOnceTheSecretsAreProvided() throws Exception {
        YamlConfiguration config = yaml("config.yml");
        Map<String, String> environment = Map.of(
                "SC_NETWORK_ID", "network", "SC_NETWORK_SECRET", "cloud-s3cr3t",
                "BUILDREPLAY_DB_PASSWORD", "db-s3cr3t", "BUILDREPLAY_REDIS_PASSWORD", "redis-s3cr3t");
        List<String> warnings = new ArrayList<>();

        PluginConfig parsed = PluginConfig.from(config, new EnvResolver(environment::get), warnings);

        assertEquals(List.of(), warnings);
        assertTrue(parsed.simpleCloud().configured());
        assertEquals("db-s3cr3t", parsed.database().password());
        assertEquals("cloud-s3cr3t", parsed.simpleCloud().networkSecret());
        assertFalse(parsed.toString().contains("s3cr3t"), "toString verrät keine Geheimnisse: " + parsed);
        assertEquals(PluginConfig.CURRENT_CONFIG_VERSION, config.getInt("config-version"));
    }

    // ------------------------------------------------------------------ messages.yml

    @Test
    void everyMessageKeyUsedInTheCodeExistsInTheShippedMessagesYml() throws Exception {
        YamlConfiguration messages = yaml("messages.yml");
        assertNotNull(messages.getString("prefix"));
        Map<String, Path> used = new TreeMap<>();
        for (Path source : mainSources()) {
            Matcher matcher = MESSAGE_KEY.matcher(Files.readString(source));
            while (matcher.find()) {
                used.putIfAbsent(matcher.group(1), source.getFileName());
            }
        }

        assertTrue(used.size() > 150, "gefundene Schlüssel: " + used.size());
        assertTrue(used.containsKey("playback.started") && used.containsKey("sessions.header-own"), used.keySet()
                .toString());
        List<String> missing = new ArrayList<>();
        used.forEach((key, source) -> {
            if (!messages.isString(key)) {
                missing.add(key + " (" + source + ")");
            }
        });
        assertEquals(List.of(), missing, "Schlüssel fehlen in messages.yml");
    }

    @Test
    void shippedTextsOnlyUsePlaceholdersTheCodeFillsIn() throws Exception {
        YamlConfiguration messages = yaml("messages.yml");
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (Path source : mainSources()) {
            Matcher matcher = KEY_WITH_FALLBACK.matcher(Files.readString(source));
            while (matcher.find()) {
                String key = matcher.group(1);
                StringBuilder fallback = new StringBuilder();
                Matcher literal = STRING_LITERAL.matcher(matcher.group(2));
                while (literal.find()) {
                    fallback.append(literal.group(1));
                }
                String shipped = messages.getString(key);
                if (shipped == null) {
                    problems.add(key + ": fehlt");
                    continue;
                }
                checked++;
                Set<String> extra = new TreeSet<>(placeholders(shipped));
                extra.removeAll(placeholders(fallback.toString()));
                if (!extra.isEmpty()) {
                    problems.add(key + ": messages.yml nutzt " + extra + ", der Code setzt nur "
                            + placeholders(fallback.toString()) + " (" + source.getFileName() + ")");
                }
            }
        }
        assertTrue(checked > 150, "geprüfte Texte: " + checked);
        assertEquals(List.of(), problems);
    }
}
