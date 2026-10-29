package net.techniqstone.buildReplay.command;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import net.techniqstone.buildReplay.activity.ActivityType;
import net.techniqstone.buildReplay.config.PluginConfig;
import net.techniqstone.buildReplay.storage.model.VisitRow;
import org.jspecify.annotations.Nullable;

/**
 * Reine Formatierung für die Befehlsausgaben (ohne Bukkit): Datum/Uhrzeit in {@code display.time-zone} mit
 * {@code display.date-time-pattern}, Dauern wie im SimpleCloud-Dashboard ("&lt;1 min", "14 min", "1 h 05 min").
 */
public final class Formatters {

    public static final String NONE = "–";
    private static final Locale LOCALE = Locale.GERMANY;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss", LOCALE);
    private static final DateTimeFormatter SHORT_DATE_TIME = DateTimeFormatter.ofPattern("dd.MM. HH:mm:ss", LOCALE);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd", LOCALE);

    private final ZoneId zone;
    private final DateTimeFormatter dateTime;

    public Formatters(ZoneId zone, String dateTimePattern) {
        this.zone = Objects.requireNonNull(zone, "zone");
        this.dateTime = DateTimeFormatter.ofPattern(dateTimePattern, LOCALE).withZone(zone);
    }

    public static Formatters of(PluginConfig.DisplaySettings display) {
        return new Formatters(display.timeZone(), display.dateTimePattern());
    }

    public ZoneId zone() {
        return zone;
    }

    /** Datum und Uhrzeit, z. B. "29.10.2026 16:58"; {@code null} → "–". */
    public String dateTime(@Nullable Instant instant) {
        return instant == null ? NONE : dateTime.format(instant);
    }

    /** Uhrzeit mit Sekunden, z. B. "16:58:03". */
    public String time(@Nullable Instant instant) {
        return instant == null ? NONE : TIME.withZone(zone).format(instant);
    }

    /** Ob beide Zeitpunkte in der Anzeige-Zeitzone auf denselben Kalendertag fallen. */
    public boolean sameDay(Instant a, Instant b) {
        DateTimeFormatter day = DAY.withZone(zone);
        return day.format(a).equals(day.format(b));
    }

    /**
     * Uhrzeit eines Abschnitts relativ zum Sitzungstag: nur die Uhrzeit, wenn er am selben Tag liegt, sonst mit
     * Tag und Monat ("30.10. 00:01:12").
     */
    public String timeOnDay(Instant instant, @Nullable Instant reference) {
        if (reference == null || sameDay(instant, reference)) {
            return time(instant);
        }
        return SHORT_DATE_TIME.withZone(zone).format(instant);
    }

    /**
     * Dauer wie im SimpleCloud-Dashboard: unter einer Minute "&lt;1 min", unter einer Stunde "14 min", sonst
     * "1 h 05 min" (Minuten abgerundet, Stunden ohne Obergrenze).
     */
    public static String sessionDuration(long seconds) {
        long s = Math.max(0, seconds);
        if (s < 60) {
            return "<1 min";
        }
        long minutes = s / 60;
        if (minutes < 60) {
            return minutes + " min";
        }
        return (minutes / 60) + " h " + String.format(LOCALE, "%02d", minutes % 60) + " min";
    }

    /** Dauer eines Replay-Abschnitts (sekundengenau): "45 s", "4 min 05 s", "1 h 05 min". */
    public static String clipDuration(long seconds) {
        long s = Math.max(0, seconds);
        if (s < 60) {
            return s + " s";
        }
        if (s < 3_600) {
            return (s / 60) + " min " + String.format(LOCALE, "%02d", s % 60) + " s";
        }
        long minutes = s / 60;
        return (minutes / 60) + " h " + String.format(LOCALE, "%02d", minutes % 60) + " min";
    }

    /** Vor-/Nachlauf in Millisekunden → "10 s" bzw. "1,5 s". */
    public static String roll(int millis) {
        int ms = Math.max(0, millis);
        if (ms % 1000 == 0) {
            return (ms / 1000) + " s";
        }
        return String.format(LOCALE, "%.1f s", ms / 1000.0);
    }

    /** Deutsche Bezeichnung einer Aktivität aus der Aktionsübersicht (Konfigurationsschlüssel oder Enum-Name). */
    public static String actionLabel(String key) {
        return ActivityType.fromConfigKey(key).map(type -> switch (type) {
            case BLOCK_PLACE -> "Blöcke gesetzt";
            case BLOCK_BREAK -> "Blöcke abgebaut";
            case BUCKET -> "Eimer";
            case SIGN_EDIT -> "Schilder";
            case HANGING -> "Bilder/Rahmen";
            case ARMOR_STAND -> "Rüstungsständer";
            case ENTITY_PLACE -> "Objekte platziert";
            case BLOCK_INTERACT -> "Block-Interaktionen";
            case WORLDEDIT -> "WorldEdit";
            case AXIOM -> "Axiom";
            case MOVE -> "Bewegung";
            case LOOK -> "Umsehen";
            case CHAT -> "Chat";
            case COMMAND -> "Befehle";
            case INVENTORY -> "Inventar";
            case ITEM_HELD -> "Itemwechsel";
            case SWING -> "Schwingen";
            default -> type.configKey();
        }).orElse(key);
    }

    /** Aktionsübersicht, häufigste zuerst: "30× Blöcke gesetzt, 12× Blöcke abgebaut"; leer → "–". */
    public static String actionSummary(Map<String, Integer> summary) {
        if (summary == null || summary.isEmpty()) {
            return NONE;
        }
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(summary.entrySet());
        entries.removeIf(e -> e.getValue() == null || e.getValue() <= 0);
        if (entries.isEmpty()) {
            return NONE;
        }
        entries.sort(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()));
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : entries) {
            parts.add(entry.getValue() + "× " + actionLabel(entry.getKey()));
        }
        return String.join(", ", parts);
    }

    /** Servernamen alphabetisch, kommagetrennt; leer → "–". */
    public static String servers(@Nullable Collection<String> servers) {
        if (servers == null || servers.isEmpty()) {
            return NONE;
        }
        TreeSet<String> sorted = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        sorted.addAll(servers);
        return String.join(", ", sorted);
    }

    /** Reihenfolge der besuchten Server ("dev → build → dev"), aufeinanderfolgende Duplikate zusammengefasst. */
    public static String route(List<VisitRow> visits) {
        if (visits == null || visits.isEmpty()) {
            return NONE;
        }
        List<VisitRow> sorted = new ArrayList<>(visits);
        sorted.sort(Comparator.comparing(VisitRow::joinedAt));
        List<String> route = new ArrayList<>();
        for (VisitRow visit : sorted) {
            if (route.isEmpty() || !route.getLast().equals(visit.serverName())) {
                route.add(visit.serverName());
            }
        }
        return String.join(" → ", route);
    }

    /** Byte-Angabe für die Diagnose. */
    public static String bytes(@Nullable Long bytes) {
        if (bytes == null || bytes < 0) {
            return NONE;
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kib = bytes / 1024.0;
        if (kib < 1024) {
            return String.format(LOCALE, "%.1f KiB", kib);
        }
        return String.format(LOCALE, "%.1f MiB", kib / 1024.0);
    }

    /** Lange IDs (SimpleCloud-Instanz) für die Anzeige kürzen. */
    public static String shortId(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return NONE;
        }
        return id.length() <= 12 ? id : id.substring(0, 12) + "…";
    }

    /** Entfernt Farbcodes aus Benutzereingaben, bevor sie als Platzhalter in {@code &}-Texte eingesetzt werden. */
    public static String plain(@Nullable String input) {
        if (input == null) {
            return "";
        }
        return input.replace('&', '＆').replace('§', '?');
    }
}
