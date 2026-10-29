package net.techniqstone.buildReplay.simplecloud.platform;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.techniqstone.buildReplay.config.PluginConfig;
import net.techniqstone.buildReplay.simplecloud.SimpleCloudPlatform;
import org.bukkit.plugin.Plugin;

/**
 * Wählt die {@link SimpleCloudPlatform}: die SimpleCloud-Java-API ({@link CloudApiPlatform}), wenn sie aktiviert,
 * das Plugin {@code simplecloud-api} geladen und {@code app.simplecloud.api.CloudApi} sichtbar ist, sonst
 * {@link SimpleCloudPlatform#NONE}. Diese Klasse selbst verweist auf keinen {@code app.simplecloud.api}-Typ, damit
 * BuildReplay auch ohne das Plugin lädt.
 *
 * <p>Aufruf einmal in {@code onEnable} auf dem Hauptthread (blockiert nicht); das Ergebnis beim Deaktivieren mit
 * {@link SimpleCloudPlatform#close()} schließen.
 */
public final class SimpleCloudPlatforms {

    /** Name des SimpleCloud-API-Plugins (paper-plugin.yml). */
    public static final String API_PLUGIN = "simplecloud-api";

    private static final String API_CLASS = "app.simplecloud.api.CloudApi";

    private SimpleCloudPlatforms() {
    }

    public static SimpleCloudPlatform create(Plugin plugin, PluginConfig config, Logger logger) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(logger, "logger");

        if (!config.simpleCloud().useJavaApi()) {
            logger.info("SimpleCloud-Java-API per Konfiguration deaktiviert (simplecloud.use-java-api) - "
                    + "ohne NATS-Spielerevents und Server-Connect");
            return SimpleCloudPlatform.NONE;
        }
        if (!isPluginEnabled(plugin)) {
            logger.info("Plugin " + API_PLUGIN + " nicht aktiv - ohne NATS-Spielerevents und Server-Connect "
                    + "(Sitzungen kommen weiter über REST)");
            return SimpleCloudPlatform.NONE;
        }
        if (!isApiVisible()) {
            logger.warning("Plugin " + API_PLUGIN + " ist aktiv, aber " + API_CLASS + " ist nicht ladbar - "
                    + "SimpleCloud-Java-API deaktiviert");
            return SimpleCloudPlatform.NONE;
        }
        try {
            return CloudApiPlatform.create(config.simpleCloud(), logger);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError fatal) {
                throw fatal;
            }
            logger.log(Level.WARNING, "SimpleCloud-Java-API konnte nicht gestartet werden - weiter ohne "
                    + "NATS-Spielerevents und Server-Connect", t);
            return SimpleCloudPlatform.NONE;
        }
    }

    private static boolean isPluginEnabled(Plugin plugin) {
        try {
            return plugin.getServer().getPluginManager().isPluginEnabled(API_PLUGIN);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean isApiVisible() {
        try {
            Class.forName(API_CLASS, false, SimpleCloudPlatforms.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
