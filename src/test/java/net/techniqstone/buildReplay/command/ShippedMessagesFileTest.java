package net.techniqstone.buildReplay.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Jeder Text der Befehle steht mit genau seinem Fallback in der mitgelieferten messages.yml. */
class ShippedMessagesFileTest {

    private static YamlConfiguration shipped() throws IOException {
        try (InputStream in = ShippedMessagesFileTest.class.getResourceAsStream("/messages.yml")) {
            assertNotNull(in, "messages.yml fehlt im Jar");
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    /** Wert wie in {@code Messages#reload}: Listen werden mit Zeilenumbrüchen verbunden. */
    private static String text(YamlConfiguration yaml, String key) {
        Object value = yaml.get(key);
        if (value instanceof List<?> list) {
            return String.join("\n", list.stream().map(String::valueOf).toList());
        }
        return value == null ? null : String.valueOf(value);
    }

    static List<Text> allTexts() throws IllegalAccessException {
        List<Text> texts = new ArrayList<>();
        for (Field field : CommandTexts.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == Text.class) {
                texts.add((Text) field.get(null));
            }
        }
        return texts;
    }

    @Test
    void everyCommandTextIsInTheShippedFileWithTheSameWording() throws Exception {
        YamlConfiguration yaml = shipped();
        List<Text> texts = allTexts();

        assertTrue(texts.size() > 100, "Texte gefunden: " + texts.size());
        for (Text text : texts) {
            assertEquals(text.fallback(), text(yaml, text.key()), "messages.yml: " + text.key());
        }
    }

    @Test
    void keysAreUniqueAndThePrefixIsShipped() throws Exception {
        Set<String> keys = new HashSet<>();
        for (Text text : allTexts()) {
            assertTrue(keys.add(text.key()), "doppelter Schlüssel " + text.key());
        }
        assertEquals("&8[&6BuildReplay&8] &7", shipped().getString("prefix"));
    }

    @Test
    void textsUseProperUmlautsInsteadOfReplacements() throws Exception {
        String all = String.join("\n", allTexts().stream().map(Text::fallback).toList());
        assertTrue(all.contains("für") && all.contains("Übersicht") && all.contains("gelöscht"));
        assertTrue(!all.contains("fuer") && !all.contains("Uebersicht"), "keine ae/oe/ue-Ersatzschreibweisen");
    }
}
