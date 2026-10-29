package net.techniqstone.buildReplay.session.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import net.techniqstone.buildReplay.session.NetworkSession;

/** Kurzreferenzen für Befehle (siehe {@link NetworkSession#shortRef(String)}). */
final class SessionRefs {

    private static final int MIN_LENGTH = 8;

    private SessionRefs() {
    }

    /** Ohne Bindestriche, klein geschrieben - für robuste Präfixvergleiche. */
    static String compact(String value) {
        return value.replace("-", "").toLowerCase(Locale.ROOT);
    }

    /** Ob die Referenz eine SimpleCloud-Session-ID sein kann (Hex-Ziffern und Bindestriche). */
    static boolean isCloudRef(String ref) {
        return !ref.isEmpty() && compact(ref).length() > 0
                && ref.chars().allMatch(c -> c == '-' || Character.digit(c, 16) >= 0);
    }

    /**
     * Kürzeste eindeutige Referenzen (mindestens 8 Zeichen) für mehrdeutige Treffer. Es sind echte Präfixe der
     * Schlüssel (Bindestriche bleiben erhalten), damit eine erneute Suche mit dem längeren Präfix trifft.
     */
    static List<String> candidates(List<String> sessionKeys) {
        int maxLength = 0;
        for (String key : sessionKeys) {
            maxLength = Math.max(maxLength, key.length());
        }
        for (int length = MIN_LENGTH; length <= maxLength; length++) {
            List<String> refs = new ArrayList<>(sessionKeys.size());
            boolean endsWithDash = false;
            for (String key : sessionKeys) {
                String ref = prefix(key, length);
                endsWithDash |= ref.endsWith("-");
                refs.add(ref);
            }
            if (!endsWithDash && new HashSet<>(refs).size() == refs.size()) {
                return refs;
            }
        }
        List<String> full = new ArrayList<>(sessionKeys.size());
        for (String key : sessionKeys) {
            full.add(prefix(key, maxLength));
        }
        return full;
    }

    static String prefix(String sessionKey, int length) {
        if (sessionKey.startsWith(NetworkSession.PROVISIONAL_PREFIX)) {
            String rest = sessionKey.substring(NetworkSession.PROVISIONAL_PREFIX.length());
            return "p" + rest.substring(0, Math.max(0, Math.min(length - 1, rest.length()))).toLowerCase(Locale.ROOT);
        }
        return sessionKey.substring(0, Math.min(length, sessionKey.length())).toLowerCase(Locale.ROOT);
    }
}
