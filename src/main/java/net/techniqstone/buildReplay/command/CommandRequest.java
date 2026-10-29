package net.techniqstone.buildReplay.command;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** Ergebnis von {@link CommandArguments#parse}: ein geprüfter Unterbefehl oder ein Eingabefehler. */
public sealed interface CommandRequest {

    /** {@code /buildreplay [help]}. */
    record Help() implements CommandRequest {
    }

    /** {@code sessions [Spieler] [Seite]}; {@code page} ist 0-basiert, {@code playerName} {@code null} = eigene. */
    record ListSessions(@Nullable String playerName, int page) implements CommandRequest {
    }

    /** {@code session <Referenz> [Spieler]}; {@code ref} ist normalisiert (Kleinbuchstaben). */
    record ShowSession(String ref, @Nullable String playerName) implements CommandRequest {
    }

    /** {@code play <Abschnitt-ID>}. */
    record PlaySegment(long segmentId) implements CommandRequest {
    }

    /** {@code play session <Referenz> [Spieler]}. */
    record PlaySession(String ref, @Nullable String playerName) implements CommandRequest {
    }

    /** {@code stop}. */
    record Stop() implements CommandRequest {
    }

    /** {@code status}. */
    record Status() implements CommandRequest {
    }

    /** {@code admin recordings}. */
    record AdminRecordings() implements CommandRequest {
    }

    /** {@code admin inspect <Spieler>}. */
    record AdminInspect(String playerName) implements CommandRequest {
    }

    /** {@code admin reload}. */
    record AdminReload() implements CommandRequest {
    }

    /** {@code admin cleanup [--dry-run]}. */
    record AdminCleanup(boolean dryRun) implements CommandRequest {
    }

    /** {@code admin sync}. */
    record AdminSync() implements CommandRequest {
    }

    /** Eingabefehler: Text plus Platzhalter-Paare; {@code usage} wird zusätzlich als Hinweis gezeigt (optional). */
    record Invalid(Text text, List<String> placeholders, @Nullable Text usage) implements CommandRequest {

        public Invalid {
            placeholders = List.copyOf(placeholders);
        }

        public static Invalid of(Text text, @Nullable Text usage, String... placeholders) {
            return new Invalid(text, List.of(placeholders), usage);
        }

        public String[] placeholderArray() {
            return placeholders.toArray(String[]::new);
        }
    }
}
