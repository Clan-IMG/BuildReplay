package net.techniqstone.buildReplay.config;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

/**
 * Texte aus {@code plugins/BuildReplay/messages.yml} mit {@code &}-Farbcodes und {@code {platzhalter}}. Jeder Aufruf
 * übergibt einen deutschen Fallback-Text, damit fehlende Schlüssel nie zu leeren Nachrichten führen. Platzhalter
 * werden als Paare übergeben: {@code send(sender, "key", "Text {name}", "name", "Steve")}.
 */
public final class Messages {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final Map<String, String> texts = new ConcurrentHashMap<>();
    private volatile String prefix = "&8[&6BuildReplay&8] &7";

    private Messages() {
    }

    /** Leere Instanz (nur Fallback-Texte), z. B. für Tests. */
    public static Messages fallbackOnly() {
        return new Messages();
    }

    /** Lädt {@code messages.yml} (legt sie aus dem Jar an, falls sie fehlt) und ergänzt fehlende Schlüssel aus dem Jar. */
    public static Messages load(Plugin plugin) {
        Messages messages = new Messages();
        messages.reload(plugin);
        return messages;
    }

    public void reload(Plugin plugin) {
        Logger logger = plugin.getLogger();
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists() && plugin.getResource("messages.yml") != null) {
            plugin.saveResource("messages.yml", false);
        }
        YamlConfiguration yaml = file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
        try (InputStream in = plugin.getResource("messages.yml")) {
            if (in != null) {
                yaml.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Standard-messages.yml konnte nicht gelesen werden", e);
        }
        texts.clear();
        for (String key : yaml.getKeys(true)) {
            if (!yaml.isConfigurationSection(key)) {
                Object value = yaml.get(key);
                if (value instanceof java.util.List<?> list) {
                    texts.put(key, String.join("\n", list.stream().map(String::valueOf).toList()));
                } else if (value != null) {
                    texts.put(key, String.valueOf(value));
                }
            }
        }
        prefix = texts.getOrDefault("prefix", prefix);
    }

    /** Rohtext mit ersetzten Platzhaltern (Farbcodes noch als {@code &}). */
    public String raw(String key, String fallback, String... placeholders) {
        String text = texts.getOrDefault(key, fallback);
        if (placeholders.length % 2 != 0) {
            throw new IllegalArgumentException("Platzhalter müssen paarweise übergeben werden: " + key);
        }
        for (int i = 0; i < placeholders.length; i += 2) {
            String value = placeholders[i + 1] == null ? "" : placeholders[i + 1];
            text = text.replace("{" + placeholders[i] + "}", value);
        }
        return text;
    }

    /** Text als Komponente ohne Präfix. */
    public Component component(String key, String fallback, String... placeholders) {
        return LEGACY.deserialize(raw(key, fallback, placeholders));
    }

    /** Präfix als Komponente. */
    public Component prefix() {
        return LEGACY.deserialize(prefix);
    }

    /** Text mit Präfix an einen Empfänger senden (mehrzeilige Texte: Präfix nur in der ersten Zeile). */
    public void send(CommandSender to, String key, String fallback, String... placeholders) {
        to.sendMessage(prefix().append(component(key, fallback, placeholders)));
    }

    /** Beliebige Komponente mit Präfix senden. */
    public void sendComponent(CommandSender to, Component component) {
        to.sendMessage(prefix().append(component));
    }

    /** Wandelt {@code &}-Codes in eine Komponente um (für zusammengesetzte Zeilen). */
    public static Component legacy(String text) {
        return LEGACY.deserialize(text);
    }
}
