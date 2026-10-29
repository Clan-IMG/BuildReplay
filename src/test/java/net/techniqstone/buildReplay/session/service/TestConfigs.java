package net.techniqstone.buildReplay.session.service;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.techniqstone.buildReplay.config.EnvResolver;
import net.techniqstone.buildReplay.config.PluginConfig;
import net.techniqstone.buildReplay.server.ServerIdentity;
import org.bukkit.configuration.MemoryConfiguration;

/** Konfiguration, Identitäten und Logger für die Sitzungstests. */
final class TestConfigs {

    private TestConfigs() {
    }

    /** Standardkonfiguration mit überschriebenen config.yml-Pfaden (z. B. {@code "display.page-size", 3}). */
    static PluginConfig config(Map<String, Object> overrides) {
        MemoryConfiguration root = new MemoryConfiguration();
        overrides.forEach(root::set);
        return PluginConfig.from(root, new EnvResolver(name -> null), new ArrayList<>());
    }

    static PluginConfig defaults() {
        return config(Map.of());
    }

    static ServerIdentity server(String name) {
        return new ServerIdentity(name, name + "-" + UUID.randomUUID(), name, "-1", true);
    }

    static Logger quietLogger(String name) {
        Logger logger = Logger.getLogger("buildreplay.test." + name + "." + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.OFF);
        return logger;
    }
}
