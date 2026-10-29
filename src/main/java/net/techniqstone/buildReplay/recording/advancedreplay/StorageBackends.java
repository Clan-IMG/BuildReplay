package net.techniqstone.buildReplay.recording.advancedreplay;

import me.jumper251.replay.filesystem.saving.IReplaySaver;
import org.jspecify.annotations.Nullable;

/**
 * Bestimmt AdvancedReplays Speicherart aus der Saver-Klasse. Vergleich über Klassennamen (inkl. Oberklassen),
 * nicht über {@code instanceof}: {@code S3ReplaySaver} referenziert MinIO-Klassen, die AR erst zur Laufzeit
 * nachlädt - schon das Laden der Klasse für einen Typtest könnte ohne MinIO scheitern.
 */
final class StorageBackends {

    static final String FILE = "FILE";
    static final String MYSQL = "MYSQL";
    static final String S3 = "S3";
    static final String CUSTOM = "CUSTOM";

    private static final String SAVING = "me.jumper251.replay.filesystem.saving.";
    private static final String DEFAULT_SAVER = SAVING + "DefaultReplaySaver";
    private static final String DATABASE_SAVER = SAVING + "DatabaseReplaySaver";
    private static final String S3_SAVER = SAVING + "S3ReplaySaver";

    private StorageBackends() {
    }

    /** Eigene Decorator werden ausgepackt; {@code null} (kein Saver registriert) ergibt {@link #CUSTOM}. */
    static String of(@Nullable IReplaySaver saver) {
        IReplaySaver current = saver;
        while (current instanceof SaverInterceptor interceptor) {
            current = interceptor.delegate();
        }
        return current == null ? CUSTOM : ofClass(current.getClass());
    }

    static String ofClass(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            String backend = ofClassName(c.getName());
            if (!CUSTOM.equals(backend)) {
                return backend;
            }
        }
        return CUSTOM;
    }

    static String ofClassName(@Nullable String className) {
        if (className == null) {
            return CUSTOM;
        }
        return switch (className) {
            case DEFAULT_SAVER -> FILE;
            case DATABASE_SAVER -> MYSQL;
            case S3_SAVER -> S3;
            default -> CUSTOM;
        };
    }

    /** Informative, nicht netzwerkweit gültige Ablage-Referenz. */
    static @Nullable String storageRef(String backend, String replayName) {
        return switch (backend) {
            case FILE -> "plugins/AdvancedReplay/replays/" + replayName + ".replay";
            case MYSQL -> "mysql:replays/" + replayName;
            case S3 -> "s3:" + replayName + ".replay";
            default -> null;
        };
    }
}
