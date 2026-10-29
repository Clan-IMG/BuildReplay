package net.techniqstone.buildReplay.server;

import java.util.Map;
import java.util.UUID;

/**
 * Identität dieser Serverinstanz.
 *
 * <ul>
 *   <li>{@code serverName}: stabiler, netzwerkweit gültiger Name, wie ihn der Proxy registriert ("dev", "build",
 *       "lobby-1"). Persistente Server haben {@code SIMPLECLOUD_NUMERICAL_ID=-1}, dort ist der Name die Gruppe.
 *       {@code SimpleCloudRuntime.serverName()} liefert dort "dev--1" und wird deshalb nicht verwendet.</li>
 *   <li>{@code instanceId}: {@code SIMPLECLOUD_UNIQUE_ID} - ändert sich bei jedem Start, auch bei persistenten
 *       Servern. Nur für Eigentümerschaft/Herzschlag, nie als dauerhafter Schlüssel.</li>
 * </ul>
 */
public record ServerIdentity(String serverName, String instanceId, String group, String numericalId,
                             boolean simpleCloudManaged) {

    public static ServerIdentity detect(Map<String, String> environment, String serverNameOverride) {
        String group = trim(environment.get("SIMPLECLOUD_GROUP"));
        String numericalId = trim(environment.get("SIMPLECLOUD_NUMERICAL_ID"));
        String uniqueId = trim(environment.get("SIMPLECLOUD_UNIQUE_ID"));

        String name;
        if (serverNameOverride != null && !serverNameOverride.isBlank()) {
            name = serverNameOverride.trim();
        } else if (!group.isEmpty()) {
            boolean persistent = numericalId.isEmpty() || "-1".equals(numericalId);
            name = persistent ? group : group + "-" + numericalId;
        } else {
            name = "local";
        }
        boolean managed = !uniqueId.isEmpty();
        String instance = managed ? uniqueId : "local-" + UUID.randomUUID();
        return new ServerIdentity(name, instance, group, numericalId, managed);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
