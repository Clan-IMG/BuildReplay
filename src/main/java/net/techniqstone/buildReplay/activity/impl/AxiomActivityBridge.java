package net.techniqstone.buildReplay.activity.impl;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Clock;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.techniqstone.buildReplay.activity.ActivityTracking;
import net.techniqstone.buildReplay.activity.ActivityType;
import net.techniqstone.buildReplay.config.PluginConfig;
import net.techniqstone.buildReplay.core.TaskRunner;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.jspecify.annotations.Nullable;

/**
 * Optionale Brücke zu AxiomPaper: Axiom-Bearbeitungen (Blockpuffer, Einzelblöcke, Entitäten) zählen als
 * {@link ActivityType#AXIOM}.
 *
 * <p>AxiomPaper 6.0.1 feuert vor jeder Weltbearbeitung {@code com.moulberry.axiom.event.AxiomModifyWorldEvent}
 * (Bukkit-Ereignis, {@code Cancellable}, {@code getPlayer()}/{@code getWorld()}) aus
 * {@code AxiomPaper#canModifyWorld(Player, World)} - per javap am deployten Jar geprüft. Blockpuffer werden dabei
 * über {@code MinecraftServer#execute} auf dem Hauptthread angewendet. Achtung: dieselbe Prüfung läuft auch für
 * lesende Axiom-Anfragen (Chunk-/Entitätsdaten anfordern), die damit ebenfalls als Axiom-Aktivität zählen - sie
 * entstehen aber nur bei aktiver Benutzung des Axiom-Editors.
 *
 * <p>Keine Compile-Abhängigkeit: die Ereignisklasse wird per Reflexion aus dem Klassenlader von AxiomPaper geladen
 * und mit {@link PluginManager#registerEvent(Class, Listener, EventPriority, org.bukkit.plugin.EventExecutor, Plugin,
 * boolean)} registriert, nur wenn das Plugin {@value #PLUGIN_NAME} aktiv ist. Wird AxiomPaper erst später aktiviert,
 * holt {@link #onPluginEnable} die Registrierung nach. Abgebrochene Ereignisse werden ignoriert.
 */
public final class AxiomActivityBridge implements Listener {

    public static final String PLUGIN_NAME = "AxiomPaper";
    public static final String MODIFY_WORLD_EVENT = "com.moulberry.axiom.event.AxiomModifyWorldEvent";

    private final ActivityReporter reporter;
    private final Logger logger;
    private final String eventClassName;
    private @Nullable Plugin owner;
    private @Nullable Method playerGetter;
    private @Nullable Class<? extends Event> eventClass;
    private boolean hooked;

    public AxiomActivityBridge(ActivityTracking tracker, PluginConfig.ActivitySettings settings, TaskRunner tasks,
            Clock clock, Logger logger) {
        this(tracker, settings, tasks, clock, logger, MODIFY_WORLD_EVENT);
    }

    /** Für Tests mit einer eigenen Ereignisklasse. */
    AxiomActivityBridge(ActivityTracking tracker, PluginConfig.ActivitySettings settings, TaskRunner tasks,
            Clock clock, Logger logger, String eventClassName) {
        this.reporter = new ActivityReporter(tracker, settings, tasks, clock);
        this.logger = Objects.requireNonNull(logger, "logger");
        this.eventClassName = Objects.requireNonNull(eventClassName, "eventClassName");
    }

    /**
     * Startet die Brücke (Hauptthread, im {@code onEnable} von BuildReplay). Ist {@code axiom} in der Konfiguration
     * weder relevant noch Indikator, passiert nichts.
     *
     * @return {@code true}, wenn das Axiom-Ereignis jetzt registriert ist
     */
    public boolean register(Plugin owner) {
        Objects.requireNonNull(owner, "owner");
        if (!reporter.wants(ActivityType.AXIOM)) {
            logger.fine("Axiom-Brücke aus: 'axiom' ist weder relevante Aktion noch Indikator");
            return false;
        }
        if (this.owner != null) {
            return hooked;
        }
        this.owner = owner;
        PluginManager plugins = owner.getServer().getPluginManager();
        plugins.registerEvents(this, owner);
        Plugin axiom = plugins.getPlugin(PLUGIN_NAME);
        if (axiom == null || !axiom.isEnabled()) {
            logger.fine("AxiomPaper nicht aktiv - Axiom-Brücke wartet auf dessen Aktivierung");
            return false;
        }
        return hook(axiom);
    }

    /** Meldet alle Handler dieser Brücke ab (onDisable). */
    public void close() {
        if (owner != null) {
            HandlerList.unregisterAll(this);
        }
        hooked = false;
        owner = null;
    }

    public boolean active() {
        return hooked;
    }

    /** Holt die Registrierung nach, wenn AxiomPaper nach BuildReplay aktiviert wird. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginEnable(PluginEnableEvent event) {
        pluginEnabled(event.getPlugin());
    }

    /** Package-sichtbar für Tests ({@link PluginEnableEvent} braucht einen laufenden Server). */
    void pluginEnabled(Plugin plugin) {
        if (!hooked && owner != null && PLUGIN_NAME.equals(plugin.getName())) {
            hook(plugin);
        }
    }

    private boolean hook(Plugin axiom) {
        Plugin self = owner;
        if (hooked || self == null) {
            return hooked;
        }
        try {
            Class<?> raw = Class.forName(eventClassName, true, axiom.getClass().getClassLoader());
            if (!Event.class.isAssignableFrom(raw)) {
                logger.warning("Axiom-Brücke: " + eventClassName + " ist kein Bukkit-Ereignis - Axiom wird nicht erfasst");
                return false;
            }
            Method getter = raw.getMethod("getPlayer");
            if (!Player.class.isAssignableFrom(getter.getReturnType())) {
                logger.warning("Axiom-Brücke: " + eventClassName + "#getPlayer liefert keinen Spieler - Axiom wird "
                        + "nicht erfasst");
                return false;
            }
            Class<? extends Event> type = raw.asSubclass(Event.class);
            this.playerGetter = getter;
            this.eventClass = type;
            self.getServer().getPluginManager().registerEvent(type, this, EventPriority.MONITOR,
                    (listener, event) -> handle(event), self, true);
            hooked = true;
            logger.info("AxiomPaper erkannt - Axiom-Bearbeitungen zählen als Bauaktivität");
            return true;
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            logger.warning("Axiom-Brücke: " + eventClassName + " nicht gefunden (andere AxiomPaper-Version?) - "
                    + "Axiom wird nicht erfasst");
            return false;
        } catch (RuntimeException | LinkageError e) {
            logger.log(Level.WARNING, "Axiom-Brücke konnte nicht registriert werden - Axiom wird nicht erfasst", e);
            return false;
        }
    }

    /** Wird vom registrierten {@code EventExecutor} aufgerufen; package-sichtbar für Tests. */
    void handle(Event event) {
        Class<? extends Event> type = eventClass;
        Method getter = playerGetter;
        if (type == null || getter == null || !type.isInstance(event)) {
            return;
        }
        try {
            if (getter.invoke(event) instanceof Player player) {
                reporter.report(player, ActivityType.AXIOM);
            }
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException e) {
            logger.log(Level.FINE, "Axiom-Ereignis konnte nicht ausgewertet werden", e);
        }
    }
}
