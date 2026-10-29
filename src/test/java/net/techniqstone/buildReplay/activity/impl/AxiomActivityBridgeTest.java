package net.techniqstone.buildReplay.activity.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import net.techniqstone.buildReplay.activity.ActivityType;
import net.techniqstone.buildReplay.activity.impl.BukkitFakes.Answers;
import net.techniqstone.buildReplay.activity.impl.BukkitFakes.PlayerState;
import net.techniqstone.buildReplay.activity.impl.TestSupport.CapturingLogger;
import net.techniqstone.buildReplay.activity.impl.TestSupport.FakeTaskRunner;
import net.techniqstone.buildReplay.activity.impl.TestSupport.MutableClock;
import net.techniqstone.buildReplay.activity.impl.TestSupport.RecordingTracker;
import net.techniqstone.buildReplay.config.PluginConfig;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AxiomActivityBridgeTest {

    private static final Instant T0 = Instant.parse("2026-10-29T14:00:00Z");

    /** Nachbau von {@code com.moulberry.axiom.event.AxiomModifyWorldEvent} (gleiche Signatur wie in 6.0.1). */
    public static final class FakeModifyWorldEvent extends Event implements Cancellable {
        private static final HandlerList HANDLERS = new HandlerList();
        private final Player player;
        private final World world;
        private boolean cancelled;

        public FakeModifyWorldEvent(Player player, World world) {
            this.player = player;
            this.world = world;
        }

        public Player getPlayer() {
            return player;
        }

        public World getWorld() {
            return world;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void setCancelled(boolean cancel) {
            this.cancelled = cancel;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }
    }

    /** Ereignis ohne Spieler-Getter (falsche Axiom-Version). */
    public static final class EventWithoutPlayer extends Event {
        private static final HandlerList HANDLERS = new HandlerList();

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }
    }

    private record Registration(Class<?> eventClass, Listener listener, EventPriority priority,
            EventExecutor executor, Plugin plugin, boolean ignoreCancelled) {
    }

    private final List<Registration> registrations = new ArrayList<>();
    private final List<Listener> annotatedListeners = new ArrayList<>();
    private RecordingTracker tracker;
    private FakeTaskRunner tasks;
    private CapturingLogger log;
    private Plugin axiom;
    private boolean axiomEnabled;
    private boolean axiomInstalled;
    private Plugin owner;
    private PlayerState state;
    private Player player;

    @BeforeEach
    void setUp() {
        tracker = new RecordingTracker();
        tasks = new FakeTaskRunner();
        log = new CapturingLogger("axiom");
        axiomEnabled = true;
        axiomInstalled = true;
        axiom = BukkitFakes.fake(Plugin.class, new Answers()
                .on("getName", AxiomActivityBridge.PLUGIN_NAME)
                .on("isEnabled", args -> axiomEnabled));
        PluginManager plugins = BukkitFakes.fake(PluginManager.class, new Answers()
                .on("getPlugin", args -> axiomInstalled && AxiomActivityBridge.PLUGIN_NAME.equals(args[0]) ? axiom : null)
                .on("registerEvents", args -> {
                    annotatedListeners.add((Listener) args[0]);
                    return null;
                })
                .on("registerEvent", args -> {
                    registrations.add(new Registration((Class<?>) args[0], (Listener) args[1],
                            (EventPriority) args[2], (EventExecutor) args[3], (Plugin) args[4],
                            args.length > 5 && (Boolean) args[5]));
                    return null;
                }));
        Server server = BukkitFakes.fake(Server.class, new Answers().on("getPluginManager", plugins));
        owner = BukkitFakes.fake(Plugin.class, new Answers().on("getName", "BuildReplay").on("getServer", server));
        state = new PlayerState();
        player = BukkitFakes.player(state);
    }

    private AxiomActivityBridge bridge(PluginConfig.ActivitySettings settings, Class<?> eventClass) {
        return new AxiomActivityBridge(tracker, settings, tasks, new MutableClock(T0), log.logger,
                eventClass.getName());
    }

    @Test
    void axiomWorldModificationsAreReportedAsAxiomActivity() throws Exception {
        AxiomActivityBridge bridge = bridge(TestSupport.activity(), FakeModifyWorldEvent.class);

        assertTrue(bridge.register(owner));
        assertTrue(bridge.active());

        assertEquals(1, registrations.size());
        Registration registration = registrations.get(0);
        assertSame(FakeModifyWorldEvent.class, registration.eventClass());
        assertSame(bridge, registration.listener());
        assertEquals(EventPriority.MONITOR, registration.priority());
        assertSame(owner, registration.plugin());
        assertTrue(registration.ignoreCancelled());

        registration.executor().execute(bridge, new FakeModifyWorldEvent(player, BukkitFakes.world("world")));
        registration.executor().execute(bridge, new EventWithoutPlayer());

        assertEquals(List.of(new RecordingTracker.Recorded(state.id, ActivityType.AXIOM, T0)), tracker.recorded);
    }

    @Test
    void anAxiomPluginThatIsEnabledLaterIsPickedUp() {
        axiomEnabled = false;
        AxiomActivityBridge bridge = bridge(TestSupport.activity(), FakeModifyWorldEvent.class);

        assertFalse(bridge.register(owner));
        assertEquals(List.of(), registrations);
        assertEquals(List.of(bridge), annotatedListeners, "wartet per PluginEnableEvent");

        bridge.pluginEnabled(owner);
        assertFalse(bridge.active(), "anderes Plugin");

        axiomEnabled = true;
        bridge.pluginEnabled(axiom);
        assertTrue(bridge.active());
        assertEquals(1, registrations.size());

        bridge.pluginEnabled(axiom);
        assertEquals(1, registrations.size(), "nur einmal registrieren");
    }

    @Test
    void withoutAxiomNothingIsRegistered() {
        axiomInstalled = false;
        AxiomActivityBridge bridge = bridge(TestSupport.activity(), FakeModifyWorldEvent.class);

        assertFalse(bridge.register(owner));
        assertFalse(bridge.active());
        assertEquals(List.of(), registrations);
    }

    @Test
    void aMissingEventClassIsReportedAsWarningInsteadOfFailing() {
        AxiomActivityBridge bridge = new AxiomActivityBridge(tracker, TestSupport.activity(), tasks,
                new MutableClock(T0), log.logger, "com.moulberry.axiom.event.DoesNotExist");

        assertFalse(bridge.register(owner));
        assertEquals(List.of(), registrations);
        assertEquals(1, log.count(Level.WARNING));
    }

    @Test
    void anEventWithoutPlayerGetterIsRejected() {
        AxiomActivityBridge bridge = bridge(TestSupport.activity(), EventWithoutPlayer.class);

        assertFalse(bridge.register(owner));
        assertEquals(List.of(), registrations);
        assertEquals(1, log.count(Level.WARNING));
    }

    @Test
    void theBridgeStaysOffWhenAxiomIsNotAConfiguredActivity() {
        AxiomActivityBridge bridge = bridge(
                TestSupport.activityWithTypes(Set.of(ActivityType.BLOCK_PLACE), Set.of(ActivityType.MOVE)),
                FakeModifyWorldEvent.class);

        assertFalse(bridge.register(owner));
        assertEquals(List.of(), registrations);
        assertEquals(List.of(), annotatedListeners);
    }

    @Test
    void axiomEventsFromOtherThreadsAreHandedOverToTheMainThread() throws Exception {
        AxiomActivityBridge bridge = bridge(TestSupport.activity(), FakeModifyWorldEvent.class);
        bridge.register(owner);
        tasks.mainThread = false;

        registrations.get(0).executor().execute(bridge, new FakeModifyWorldEvent(player, BukkitFakes.world("w")));
        assertEquals(List.of(), tracker.recorded);

        tasks.drain();
        assertEquals(List.of(ActivityType.AXIOM), tracker.types());
    }
}
