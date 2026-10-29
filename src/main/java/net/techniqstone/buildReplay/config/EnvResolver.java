package net.techniqstone.buildReplay.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Löst Platzhalter in Konfigurationswerten auf, damit Geheimnisse nicht im Code stehen müssen.
 *
 * <ul>
 *   <li>{@code ${env:NAME}} und {@code ${env:NAME:-standard}}: Umgebungsvariable des Serverprozesses.</li>
 *   <li>{@code {{ secrets.NAME }}} und {@code {{ env.NAME }}}: SimpleCloud-Schreibweise. SimpleCloud ersetzt sie beim
 *       Serverstart ({@code apply-secrets}), aber nur in Dateien, die per Template in den Server kopiert wurden.
 *       Steht ein solcher Platzhalter noch roh in der Datei, wird ersatzweise eine gleichnamige Umgebungsvariable
 *       verwendet; gibt es keine, bleibt der Wert leer und der Platzhalter wird als unaufgelöst gemeldet.</li>
 * </ul>
 * SimpleCloud gibt jedem Server {@code SIMPLECLOUD_NETWORK_ID}/{@code SIMPLECLOUD_NETWORK_SECRET} mit; eigene
 * Gruppen-Properties erscheinen als {@code SIMPLECLOUD_<key>}.
 */
public final class EnvResolver {

    private static final Pattern ENV_PLACEHOLDER =
            Pattern.compile("\\$\\{env:([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?}");
    private static final Pattern SIMPLECLOUD_PLACEHOLDER =
            Pattern.compile("\\{\\{\\s*(env|secrets)\\.([A-Za-z_][A-Za-z0-9_.-]*)\\s*}}");

    private final Function<String, String> environment;

    public EnvResolver(Function<String, String> environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    public static EnvResolver system() {
        return new EnvResolver(System::getenv);
    }

    /** Ergebnis einer Auflösung; {@code unresolved} enthält nur Platzhalter-Namen, nie Werte. */
    public record Resolved(String value, List<String> unresolved) {

        public Resolved {
            unresolved = List.copyOf(unresolved);
        }

        public boolean complete() {
            return unresolved.isEmpty();
        }
    }

    /** Ersetzt alle Platzhalter; nicht auflösbare werden zu einem leeren String. */
    public String resolve(String raw) {
        return resolveDetailed(raw).value();
    }

    /** Wie {@link #resolve}, meldet aber zusätzlich nicht auflösbare SimpleCloud-Platzhalter. */
    public Resolved resolveDetailed(String raw) {
        if (raw == null || (raw.indexOf("${env:") < 0 && raw.indexOf("{{") < 0)) {
            return new Resolved(raw, List.of());
        }
        List<String> unresolved = new ArrayList<>();

        Matcher env = ENV_PLACEHOLDER.matcher(raw);
        StringBuilder afterEnv = new StringBuilder();
        while (env.find()) {
            String value = variable(env.group(1));
            if (value == null) {
                value = env.group(2) != null ? env.group(2) : "";
            }
            env.appendReplacement(afterEnv, Matcher.quoteReplacement(value));
        }
        env.appendTail(afterEnv);

        Matcher cloud = SIMPLECLOUD_PLACEHOLDER.matcher(afterEnv.toString());
        StringBuilder out = new StringBuilder();
        while (cloud.find()) {
            String value = variable(cloud.group(2));
            if (value == null) {
                unresolved.add("{{ " + cloud.group(1) + "." + cloud.group(2) + " }}");
                value = "";
            }
            cloud.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        cloud.appendTail(out);
        return new Resolved(out.toString(), unresolved);
    }

    /** Wert einer Umgebungsvariable oder {@code null}. */
    public String variable(String name) {
        String value = environment.apply(name);
        return value == null || value.isBlank() ? null : value.trim();
    }
}
