package net.techniqstone.buildReplay.recording.advancedreplay;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Text-Kodierung exakter Blockänderungen für AdvancedReplays {@code ChatData}. BuildReplay legt sie als
 * {@code CUSTOM}-Aktion ab: AR spielt CUSTOM-Aktionen nie selbst ab (nur über {@code IReplayHook#onPlay}), und da
 * {@code ChatData} eine AR-eigene Klasse ist, bleiben die Replay-Dateien auch ohne BuildReplay lesbar.
 *
 * <p>Format (Zeilen mit {@code \n}, Felder mit Tabulator getrennt, {@code %}, Tab, CR und LF prozentkodiert):
 * <pre>
 * BRFID1
 * W  &lt;welt&gt;
 * &lt;x&gt; &lt;y&gt; &lt;z&gt; &lt;vorher&gt; &lt;nachher&gt;
 * </pre>
 * Eine {@code W}-Zeile gilt für alle folgenden Einträge bis zur nächsten {@code W}-Zeile.
 */
final class FidelityPayload {

    /** Kennung am Anfang jeder Nutzlast (Version 1). */
    static final String MARKER = "BRFID1";

    private static final String HEADER = MARKER + "\n";
    private static final char FIELD = '\t';
    private static final char LINE = '\n';

    private FidelityPayload() {
    }

    static boolean isPayload(@Nullable String message) {
        return message != null && message.startsWith(HEADER);
    }

    /**
     * Kodiert die Änderungen in Reihenfolge; höchstens {@code maxPerMessage} Einträge pro Nachricht.
     * Leere Eingabe liefert eine leere Liste.
     */
    static List<String> encode(List<FidelityChange> changes, int maxPerMessage) {
        if (changes.isEmpty()) {
            return List.of();
        }
        int perMessage = Math.max(1, maxPerMessage);
        List<String> messages = new ArrayList<>((changes.size() + perMessage - 1) / perMessage);
        StringBuilder out = null;
        String currentWorld = null;
        int inMessage = 0;
        for (FidelityChange change : changes) {
            if (out == null || inMessage >= perMessage) {
                if (out != null) {
                    messages.add(out.toString());
                }
                out = new StringBuilder(64 + perMessage * 96).append(HEADER);
                currentWorld = null;
                inMessage = 0;
            }
            BlockKey key = change.key();
            if (!key.world().equals(currentWorld)) {
                out.append('W').append(FIELD).append(escape(key.world())).append(LINE);
                currentWorld = key.world();
            }
            out.append(key.x()).append(FIELD).append(key.y()).append(FIELD).append(key.z()).append(FIELD)
                    .append(escape(change.before())).append(FIELD).append(escape(change.after())).append(LINE);
            inMessage++;
        }
        messages.add(out.toString());
        return messages;
    }

    /** Dekodiert eine Nutzlast; fehlerhafte Zeilen werden übersprungen, fremde Nachrichten ergeben eine leere Liste. */
    static List<FidelityChange> decode(@Nullable String message) {
        if (!isPayload(message)) {
            return List.of();
        }
        List<FidelityChange> changes = new ArrayList<>();
        String world = null;
        int start = HEADER.length();
        int length = message.length();
        while (start < length) {
            int end = message.indexOf(LINE, start);
            if (end < 0) {
                end = length;
            }
            if (end > start) {
                String line = message.substring(start, end);
                if (line.startsWith("W\t")) {
                    world = unescape(line.substring(2));
                } else if (world != null) {
                    FidelityChange change = parseEntry(world, line);
                    if (change != null) {
                        changes.add(change);
                    }
                }
            }
            start = end + 1;
        }
        return changes;
    }

    private static @Nullable FidelityChange parseEntry(String world, String line) {
        String[] fields = line.split("\t", -1);
        if (fields.length != 5) {
            return null;
        }
        try {
            BlockKey key = new BlockKey(world, Integer.parseInt(fields[0]), Integer.parseInt(fields[1]),
                    Integer.parseInt(fields[2]));
            String before = unescape(fields[3]);
            String after = unescape(fields[4]);
            if (before.isEmpty() || after.isEmpty()) {
                return null;
            }
            return new FidelityChange(key, before, after);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String escape(String value) {
        StringBuilder out = null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            String replacement = switch (c) {
                case '%' -> "%25";
                case '\t' -> "%09";
                case '\n' -> "%0A";
                case '\r' -> "%0D";
                default -> null;
            };
            if (replacement != null) {
                if (out == null) {
                    out = new StringBuilder(value.length() + 8).append(value, 0, i);
                }
                out.append(replacement);
            } else if (out != null) {
                out.append(c);
            }
        }
        return out == null ? value : out.toString();
    }

    static String unescape(String value) {
        if (value.indexOf('%') < 0) {
            return value;
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%' && i + 2 < value.length()) {
                String code = value.substring(i + 1, i + 3);
                char decoded = switch (code.toUpperCase(Locale.ROOT)) {
                    case "25" -> '%';
                    case "09" -> '\t';
                    case "0A" -> '\n';
                    case "0D" -> '\r';
                    default -> 0;
                };
                if (decoded != 0) {
                    out.append(decoded);
                    i += 2;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }
}
