package net.techniqstone.buildReplay.activity.impl;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Predicate;
import net.techniqstone.buildReplay.activity.ActivityTracking;
import net.techniqstone.buildReplay.activity.ActivityType;
import net.techniqstone.buildReplay.config.PluginConfig;
import net.techniqstone.buildReplay.core.TaskRunner;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Übersetzt Bukkit-/Paper-Ereignisse in {@link ActivityType}s und meldet sie an den {@link ActivityTracking}.
 *
 * <p>Alle Handler laufen auf {@link EventPriority#MONITOR}, ändern nichts und lesen nur. Weltverändernde Ereignisse
 * werden mit {@code ignoreCancelled = true} registriert (abgebrochene Aktionen haben nichts verändert), reine
 * Indikatoren ohne, weil auch eine abgelehnte Eingabe (gesperrter Befehl, GUI-Klick) zeigt, dass der Spieler da ist.
 * {@code AsyncChatEvent} kommt asynchron und wird über {@link TaskRunner#runOnMain} auf den Hauptthread gebracht.
 * Bewegungen werden pro Spieler höchstens alle {@link #MOVE_CHECK_INTERVAL} geprüft (siehe {@link MovementFilter}).
 * NPCs (Metadaten {@code NPC}) werden ignoriert.
 *
 * <p>{@link ActivityTracking#start}/{@link ActivityTracking#forget} ruft dieser Listener nicht auf - das übernimmt
 * die Aufnahmesteuerung bei Join/Quit; er räumt beim Verlassen nur seine eigenen Bewegungsdaten auf.
 */
public final class ActivityListener implements Listener {

    /** Mindestabstand zwischen zwei Bewegungsprüfungen pro Spieler. */
    public static final Duration MOVE_CHECK_INTERVAL = Duration.ofMillis(250);

    private final ActivityReporter reporter;
    private final EditCommandMatcher editCommands;
    private final MovementFilter movement;
    private final Predicate<Material> interactable;
    private final boolean worldEditWanted;

    public ActivityListener(ActivityTracking tracker, PluginConfig.ActivitySettings settings, TaskRunner tasks,
            Clock clock) {
        this(tracker, settings, tasks, clock, ActivityListener::isInteractableMaterial);
    }

    /** Für Tests: {@link Material#isInteractable()} braucht die Registries eines laufenden Servers. */
    ActivityListener(ActivityTracking tracker, PluginConfig.ActivitySettings settings, TaskRunner tasks, Clock clock,
            Predicate<Material> interactable) {
        Objects.requireNonNull(settings, "settings");
        this.reporter = new ActivityReporter(tracker, settings, tasks, clock);
        this.editCommands = new EditCommandMatcher(settings);
        this.movement = new MovementFilter(settings.movementThreshold(), settings.lookThresholdDegrees(),
                MOVE_CHECK_INTERVAL.toMillis(), reporter.wants(ActivityType.MOVE), reporter.wants(ActivityType.LOOK));
        this.interactable = Objects.requireNonNull(interactable, "interactable");
        this.worldEditWanted = reporter.wants(ActivityType.WORLDEDIT);
    }

    public EditCommandMatcher editCommandMatcher() {
        return editCommands;
    }

    // --- weltverändernde Aktionen (standardmäßig relevant)

    /** Deckt auch {@code BlockMultiPlaceEvent} ab (Unterklasse ohne eigene HandlerList). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        reporter.report(event.getPlayer(), ActivityType.BLOCK_PLACE);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        reporter.report(event.getPlayer(), ActivityType.BLOCK_BREAK);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        reporter.report(event.getPlayer(), ActivityType.BUCKET);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        reporter.report(event.getPlayer(), ActivityType.BUCKET);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        reporter.report(event.getPlayer(), ActivityType.SIGN_EDIT);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        reporter.report(event.getPlayer(), ActivityType.HANGING);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        if (event.getRemover() instanceof Player player) {
            reporter.report(player, ActivityType.HANGING);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        reporter.report(event.getPlayer(), ActivityType.ARMOR_STAND);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        reporter.report(event.getPlayer(), ActivityType.ENTITY_PLACE);
    }

    /**
     * Rechtsklick auf einen benutzbaren Block (Tür, Hebel, Truhe ...). Paper wertet {@code isCancelled()} als
     * {@code useInteractedBlock() == DENY}; die zusätzliche Prüfung hält das auch dann ein, falls sich das ändert.
     * Ein abgelehntes Item ({@code useItemInHand() == DENY}) verhindert die Blockbenutzung nicht.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.useInteractedBlock() == Event.Result.DENY) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        Player player = event.getPlayer();
        // Schleichen mit Item in der Hand benutzt den Block nicht (Vanilla), sondern setzt bzw. benutzt das Item
        if (player.isSneaking() && event.hasItem()) {
            return;
        }
        if (interactable.test(block.getType())) {
            reporter.report(player, ActivityType.BLOCK_INTERACT);
        }
    }

    // --- Befehle und Chat

    /** Bearbeitungsbefehle zählen als {@link ActivityType#WORLDEDIT}, alles andere (auch abgelehnte) als Indikator. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        boolean edit = worldEditWanted && !event.isCancelled() && editCommands.matches(event.getMessage());
        reporter.report(event.getPlayer(), edit ? ActivityType.WORLDEDIT : ActivityType.COMMAND);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncChatEvent event) {
        reporter.report(event.getPlayer(), ActivityType.CHAT);
    }

    // --- Indikatoren

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!movement.enabled() || (!event.hasChangedPosition() && !event.hasChangedOrientation())) {
            return;
        }
        Player player = event.getPlayer();
        Location to = event.getTo();
        World world = to.getWorld();
        ActivityType type = movement.check(player.getUniqueId(), world == null ? null : world.getUID(),
                to.getX(), to.getY(), to.getZ(), to.getYaw(), to.getPitch(), reporter.clock().millis());
        if (type != null) {
            reporter.report(player, type);
        }
    }

    /** Teleports sind keine Eingabe: Bezugspunkt verwerfen, die nächste Bewegung verankert neu. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        movement.reset(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            reporter.report(player, ActivityType.INVENTORY);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            reporter.report(player, ActivityType.INVENTORY);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemHeld(PlayerItemHeldEvent event) {
        reporter.report(event.getPlayer(), ActivityType.ITEM_HELD);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(PlayerAnimationEvent event) {
        reporter.report(event.getPlayer(), ActivityType.SWING);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        movement.reset(event.getPlayer().getUniqueId());
    }

    /** Anzahl Spieler mit Bewegungs-Bezugspunkt (Diagnose, Tests). */
    int trackedMovementAnchors() {
        return movement.trackedPlayers();
    }

    /**
     * Paper markiert {@link Material#isInteractable()} als unvollständig (viele Interaktionen hängen am Item oder am
     * Weltzustand). Für die Einstufung als Bauaktivität reicht die Näherung.
     */
    @SuppressWarnings("deprecation")
    private static boolean isInteractableMaterial(Material material) {
        return material.isInteractable();
    }
}
