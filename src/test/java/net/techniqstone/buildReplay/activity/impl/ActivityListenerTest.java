package net.techniqstone.buildReplay.activity.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.techniqstone.buildReplay.activity.ActivityType;
import net.techniqstone.buildReplay.activity.impl.BukkitFakes.PlayerState;
import net.techniqstone.buildReplay.activity.impl.TestSupport.FakeTaskRunner;
import net.techniqstone.buildReplay.activity.impl.TestSupport.MutableClock;
import net.techniqstone.buildReplay.activity.impl.TestSupport.RecordingTracker;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ActivityListenerTest {

    private static final Instant T0 = Instant.parse("2026-10-29T14:00:00Z");

    private MutableClock clock;
    private RecordingTracker tracker;
    private FakeTaskRunner tasks;
    private ActivityListener listener;
    private PlayerState state;
    private Player player;
    private World world;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        tracker = new RecordingTracker();
        tasks = new FakeTaskRunner();
        listener = new ActivityListener(tracker, TestSupport.activity(), tasks, clock,
                material -> material == Material.LEVER);
        state = new PlayerState();
        player = BukkitFakes.player(state);
        world = BukkitFakes.world("world");
    }

    private Location location(double x, double z, float yaw) {
        return new Location(world, x, 64, z, yaw, 0f);
    }

    private PlayerInteractEvent rightClick(Material material) {
        return new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, null, BukkitFakes.block(material),
                BlockFace.UP);
    }

    @Test
    void blockPlacementIsReportedWithTheCurrentTime() {
        Block placed = BukkitFakes.block(Material.STONE);
        listener.onBlockPlace(new BlockPlaceEvent(placed, null, placed, null, player, true, EquipmentSlot.HAND));

        assertEquals(List.of(new RecordingTracker.Recorded(state.id, ActivityType.BLOCK_PLACE, T0)), tracker.recorded);
    }

    @Test
    void blockBreakingIsReported() {
        listener.onBlockBreak(new BlockBreakEvent(BukkitFakes.block(Material.STONE), player));

        assertEquals(List.of(ActivityType.BLOCK_BREAK), tracker.types());
    }

    @Test
    void hangingsCountOnlyWhenAPlayerRemovesThem() {
        Hanging frame = BukkitFakes.fake(Hanging.class, new BukkitFakes.Answers());
        listener.onHangingBreak(new HangingBreakByEntityEvent(frame, BukkitFakes.entity()));
        listener.onHangingBreak(new HangingBreakByEntityEvent(frame, player));

        assertEquals(List.of(ActivityType.HANGING), tracker.types());
    }

    @Test
    void rightClickingAnInteractableBlockIsABlockInteraction() {
        listener.onInteract(rightClick(Material.LEVER));
        listener.onInteract(rightClick(Material.STONE));
        listener.onInteract(new PlayerInteractEvent(player, Action.LEFT_CLICK_BLOCK, null,
                BukkitFakes.block(Material.LEVER), BlockFace.UP));
        PlayerInteractEvent denied = rightClick(Material.LEVER);
        denied.setUseInteractedBlock(Event.Result.DENY);
        listener.onInteract(denied);

        assertEquals(List.of(ActivityType.BLOCK_INTERACT), tracker.types());
    }

    @Test
    void editCommandsAreWorldEditAndOtherCommandsAreIndicators() {
        listener.onCommand(new PlayerCommandPreprocessEvent(player, "//set stone", new HashSet<>()));
        listener.onCommand(new PlayerCommandPreprocessEvent(player, "/spawn", new HashSet<>()));
        PlayerCommandPreprocessEvent blocked = new PlayerCommandPreprocessEvent(player, "//set stone", new HashSet<>());
        blocked.setCancelled(true);
        listener.onCommand(blocked);

        assertEquals(List.of(ActivityType.WORLDEDIT, ActivityType.COMMAND, ActivityType.COMMAND), tracker.types());
    }

    @Test
    void editCommandsFallBackToCommandWhenWorldEditIsNotConfigured() {
        ActivityListener withoutWorldEdit = new ActivityListener(tracker,
                TestSupport.activityWithTypes(Set.of(ActivityType.BLOCK_PLACE), Set.of(ActivityType.COMMAND)), tasks,
                clock, material -> false);

        withoutWorldEdit.onCommand(new PlayerCommandPreprocessEvent(player, "//set stone", new HashSet<>()));

        assertEquals(List.of(ActivityType.COMMAND), tracker.types());
    }

    @Test
    void asynchronousChatIsHandedOverToTheMainThreadWithItsOriginalTime() {
        tasks.mainThread = false;
        listener.onChat(new AsyncChatEvent(true, player, new HashSet<>(), null, null, null, null));
        assertEquals(List.of(), tracker.recorded, "nie außerhalb des Hauptthreads");

        clock.advance(Duration.ofSeconds(2));
        tasks.drain();

        assertEquals(List.of(new RecordingTracker.Recorded(state.id, ActivityType.CHAT, T0)), tracker.recorded);
    }

    @Test
    void chatFromAPlayerWhoLeftBeforeTheHandOverIsDropped() {
        tasks.mainThread = false;
        listener.onChat(new AsyncChatEvent(true, player, new HashSet<>(), null, null, null, null));
        state.online = false;
        tasks.drain();

        assertEquals(List.of(), tracker.recorded);
    }

    @Test
    void npcsAreIgnored() {
        state.npc = true;
        listener.onItemHeld(new PlayerItemHeldEvent(player, 0, 1));
        listener.onBlockBreak(new BlockBreakEvent(BukkitFakes.block(Material.STONE), player));

        assertEquals(List.of(), tracker.recorded);
    }

    @Test
    void movementIsSampledAndThresholded() {
        listener.onMove(new PlayerMoveEvent(player, location(0, 0, 0), location(0.1, 0, 0)));
        clock.advance(Duration.ofMillis(100));
        listener.onMove(new PlayerMoveEvent(player, location(0.1, 0, 0), location(3, 0, 0)));
        clock.advance(Duration.ofMillis(200));
        listener.onMove(new PlayerMoveEvent(player, location(3, 0, 0), location(3.1, 0, 0)));
        clock.advance(Duration.ofMillis(300));
        listener.onMove(new PlayerMoveEvent(player, location(3.1, 0, 0), location(3.1, 0, 40f)));

        assertEquals(List.of(ActivityType.MOVE, ActivityType.LOOK), tracker.types());
        assertEquals(T0.plusMillis(300), tracker.recorded.get(0).at());
    }

    @Test
    void movesWithoutAnyChangeAreSkippedAndQuitDropsTheReference() {
        listener.onMove(new PlayerMoveEvent(player, location(0, 0, 0), location(0, 0, 0)));
        assertEquals(0, listener.trackedMovementAnchors(), "unveränderte Lage wird gar nicht erst geprüft");

        listener.onMove(new PlayerMoveEvent(player, location(0, 0, 0), location(0.1, 0, 0)));
        assertEquals(1, listener.trackedMovementAnchors());
        listener.onQuit(new PlayerQuitEvent(player, (net.kyori.adventure.text.Component) null,
                PlayerQuitEvent.QuitReason.DISCONNECTED));
        assertEquals(0, listener.trackedMovementAnchors());
    }

    @Test
    void itemSwitchesAndSwingsAreIndicators() {
        listener.onItemHeld(new PlayerItemHeldEvent(player, 0, 1));
        listener.onSwing(new PlayerAnimationEvent(player, PlayerAnimationType.ARM_SWING));

        assertEquals(List.of(ActivityType.ITEM_HELD, ActivityType.SWING), tracker.types());
    }

    @Test
    void worldChangingHandlersIgnoreCancelledEventsAndAllRunOnMonitor() throws NoSuchMethodException {
        for (String handler : List.of("onBlockPlace", "onBlockBreak", "onBucketEmpty", "onBucketFill",
                "onSignChange", "onHangingPlace", "onHangingBreak", "onArmorStandManipulate", "onEntityPlace",
                "onInteract", "onMove")) {
            EventHandler annotation = handlerAnnotation(handler);
            assertTrue(annotation.ignoreCancelled(), handler + " muss abgebrochene Ereignisse ignorieren");
        }
        for (var method : ActivityListener.class.getDeclaredMethods()) {
            EventHandler annotation = method.getAnnotation(EventHandler.class);
            if (annotation != null) {
                assertEquals(EventPriority.MONITOR, annotation.priority(), method.getName());
            }
        }
    }

    private static EventHandler handlerAnnotation(String name) {
        for (var method : ActivityListener.class.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return method.getAnnotation(EventHandler.class);
            }
        }
        throw new AssertionError("Handler fehlt: " + name);
    }
}
