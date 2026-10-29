package net.techniqstone.buildReplay.activity.impl;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import net.techniqstone.buildReplay.config.PluginConfig;
import org.jspecify.annotations.Nullable;

/**
 * Erkennt Bearbeitungsbefehle (WorldEdit, FAWE, Vanilla-{@code /fill} usw.) in einer Befehlszeile, wie sie
 * {@code PlayerCommandPreprocessEvent#getMessage()} liefert.
 *
 * <p>Ablauf: Zeile trimmen und klein schreiben, genau einen führenden Befehls-Schrägstrich entfernen, das erste Wort
 * als Befehlsnamen nehmen und ein Namensraum-Präfix ({@code worldedit:}, {@code minecraft:}) abschneiden. Danach
 * trifft der Befehl, wenn
 * <ul>
 *   <li>die Zeile ohne Namensraum (wieder mit Schrägstrich, z. B. {@code //set stone}) mit einem der
 *       {@code activity.edit-command-prefixes} beginnt - Präfixe gelten inklusive des Befehls-Schrägstrichs, ein
 *       fehlender wird ergänzt - oder</li>
 *   <li>der Befehlsname ohne führende Schrägstriche in {@code activity.edit-commands} steht (z. B. {@code brush},
 *       {@code fill}).</li>
 * </ul>
 * Unveränderlich und damit threadsicher.
 */
public final class EditCommandMatcher {

    private final List<String> prefixes;
    private final Set<String> commands;

    public EditCommandMatcher(Collection<String> prefixes, Collection<String> commands) {
        Objects.requireNonNull(prefixes, "prefixes");
        Objects.requireNonNull(commands, "commands");
        Set<String> normalizedPrefixes = new LinkedHashSet<>();
        for (String prefix : prefixes) {
            if (prefix == null) {
                continue;
            }
            String normalized = prefix.strip().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty()) {
                continue;
            }
            normalizedPrefixes.add(normalized.startsWith("/") ? normalized : "/" + normalized);
        }
        Set<String> normalizedCommands = new LinkedHashSet<>();
        for (String command : commands) {
            if (command == null) {
                continue;
            }
            String normalized = stripLeadingSlashes(command.strip().toLowerCase(Locale.ROOT));
            if (!normalized.isEmpty()) {
                normalizedCommands.add(normalized);
            }
        }
        this.prefixes = List.copyOf(normalizedPrefixes);
        this.commands = Set.copyOf(normalizedCommands);
    }

    public EditCommandMatcher(PluginConfig.ActivitySettings settings) {
        this(settings.editCommandPrefixes(), settings.editCommands());
    }

    /** Ob die Befehlszeile ein Bearbeitungsbefehl ist. {@code null} und leere Zeilen treffen nie. */
    public boolean matches(@Nullable String commandLine) {
        if (commandLine == null) {
            return false;
        }
        String line = commandLine.strip().toLowerCase(Locale.ROOT);
        if (line.startsWith("/")) {
            line = line.substring(1);
        }
        int end = 0;
        while (end < line.length() && !Character.isWhitespace(line.charAt(end))) {
            end++;
        }
        String label = stripNamespace(line.substring(0, end));
        if (label.isEmpty()) {
            return false;
        }
        String normalizedLine = "/" + label + line.substring(end);
        for (String prefix : prefixes) {
            if (normalizedLine.startsWith(prefix)) {
                return true;
            }
        }
        String bare = stripLeadingSlashes(label);
        return !bare.isEmpty() && commands.contains(bare);
    }

    /** Normalisierte Präfixe (Diagnose). */
    public List<String> prefixes() {
        return prefixes;
    }

    /** Normalisierte Befehlsnamen (Diagnose). */
    public Set<String> commands() {
        return commands;
    }

    /** {@code worldedit:/set} → {@code /set}, {@code minecraft:fill} → {@code fill}; sonst unverändert. */
    static String stripNamespace(String label) {
        int colon = label.indexOf(':');
        if (colon <= 0) {
            return label;
        }
        for (int i = 0; i < colon; i++) {
            char c = label.charAt(i);
            boolean namespaceChar = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-'
                    || c == '.';
            if (!namespaceChar) {
                return label;
            }
        }
        return label.substring(colon + 1);
    }

    private static String stripLeadingSlashes(String value) {
        int start = 0;
        while (start < value.length() && value.charAt(start) == '/') {
            start++;
        }
        return value.substring(start);
    }
}
