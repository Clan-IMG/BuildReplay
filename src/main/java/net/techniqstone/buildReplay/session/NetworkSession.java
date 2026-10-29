package net.techniqstone.buildReplay.session;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import net.techniqstone.buildReplay.storage.model.SessionSource;
import org.jspecify.annotations.Nullable;

/**
 * Netzwerkweite Sitzung eines Spielers - der Schlüssel, unter dem alle Replay-Abschnitte gruppiert werden.
 * {@code sessionKey} ist die SimpleCloud-Session-ID ({@code source=SIMPLECLOUD}) oder {@code "prov-" + UUID}
 * ({@code source=PROVISIONAL}, wenn SimpleCloud nicht erreichbar war; wird später zusammengeführt).
 */
public record NetworkSession(
        String sessionKey,
        @Nullable String cloudSessionId,
        UUID playerUuid,
        SessionSource source,
        @Nullable Instant loginTime) {

    public static final String PROVISIONAL_PREFIX = "prov-";

    public boolean provisional() {
        return source == SessionSource.PROVISIONAL;
    }

    /** Kurzreferenz für Befehle: 8 Zeichen der SimpleCloud-ID bzw. "p" + 7 Zeichen bei vorläufigen Sitzungen. */
    public String shortRef() {
        return shortRef(sessionKey);
    }

    public static String shortRef(String sessionKey) {
        if (sessionKey.startsWith(PROVISIONAL_PREFIX)) {
            String rest = sessionKey.substring(PROVISIONAL_PREFIX.length()).replace("-", "");
            return "p" + rest.substring(0, Math.min(7, rest.length())).toLowerCase(Locale.ROOT);
        }
        String compact = sessionKey.replace("-", "");
        return compact.substring(0, Math.min(8, compact.length())).toLowerCase(Locale.ROOT);
    }

    public static String newProvisionalKey() {
        return PROVISIONAL_PREFIX + UUID.randomUUID();
    }
}
