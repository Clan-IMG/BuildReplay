package net.techniqstone.buildReplay.state.redis;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Schlüssellayout von BuildReplay in Redis, alle Schlüssel unter dem konfigurierten Präfix (Standard
 * {@code buildreplay:}).
 */
final class RedisKeys {

    static final String DEFAULT_PREFIX = "buildreplay:";

    private final String prefix;

    RedisKeys(@Nullable String prefix) {
        this.prefix = prefix == null || prefix.isBlank() ? DEFAULT_PREFIX : prefix.trim();
    }

    String prefix() {
        return prefix;
    }

    String session(UUID player) {
        return prefix + "session:" + player;
    }

    String presence(UUID player) {
        return prefix + "presence:" + player;
    }

    String recording(UUID player) {
        return prefix + "rec:" + player;
    }

    String activity(UUID player) {
        return prefix + "activity:" + player;
    }

    String lock(String name) {
        return prefix + "lock:" + name;
    }

    String leader(String role) {
        return prefix + "leader:" + role;
    }

    String instance(String instanceId) {
        return prefix + "instance:" + instanceId;
    }

    /** Muster für {@code SCAN ... MATCH}; Glob-Sonderzeichen im Präfix werden maskiert. */
    String instancePattern() {
        return escapeGlob(prefix) + "instance:*";
    }

    String handoff(UUID player) {
        return prefix + "handoff:" + player;
    }

    String eventChannel() {
        return prefix + "events";
    }

    static String escapeGlob(String text) {
        StringBuilder out = new StringBuilder(text.length() + 4);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '*' || c == '?' || c == '[' || c == ']' || c == '\\') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }
}
