package net.techniqstone.buildReplay.simplecloud;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Eintrag aus {@code GET /v0/players/online}. Servernamen sind die beim Proxy registrierten Namen ("dev", "lobby-1"). */
public record CloudOnlinePlayer(
        UUID uuid,
        String name,
        @Nullable String sessionId,
        @Nullable String serverName,
        @Nullable String proxyName) {
}
