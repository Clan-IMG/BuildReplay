package net.techniqstone.buildReplay.recording;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Netzwerkweit eindeutige AdvancedReplay-Namen. AR erlaubt nur {@code [a-zA-Z0-9._-]} (sonst liefert der Datei-Saver
 * {@code exists()=false}) und die MySQL-Spalte hat 40 Zeichen; gleiche Namen würden serverübergreifend still
 * überschrieben - daher Zeitstempel plus Zufall.
 */
public final class ReplayNames {

    /** Rohaufnahmen (werden vom Saver-Decorator abgefangen). */
    public static final String RAW_PREFIX = "brraw-";
    /** Gespeicherte Abschnitte. */
    public static final String SEGMENT_PREFIX = "br-";

    private static final Pattern VALID = Pattern.compile("^[a-zA-Z0-9._-]{1,40}$");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();

    private ReplayNames() {
    }

    public static String newRawName() {
        return RAW_PREFIX + Long.toString(System.currentTimeMillis(), 36) + "-" + random(10);
    }

    public static String newSegmentName() {
        return SEGMENT_PREFIX + Long.toString(System.currentTimeMillis(), 36) + "-" + random(12);
    }

    public static boolean isRaw(String name) {
        return name != null && name.startsWith(RAW_PREFIX);
    }

    public static boolean isValid(String name) {
        return name != null && VALID.matcher(name).matches();
    }

    private static String random(int length) {
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) {
            chars[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return new String(chars).toLowerCase(Locale.ROOT);
    }
}
