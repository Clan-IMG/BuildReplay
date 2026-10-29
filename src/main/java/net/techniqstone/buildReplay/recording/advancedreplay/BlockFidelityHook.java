package net.techniqstone.buildReplay.recording.advancedreplay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import me.jumper251.replay.api.IReplayHook;
import me.jumper251.replay.replaysystem.data.ActionData;
import me.jumper251.replay.replaysystem.data.ActionType;
import me.jumper251.replay.replaysystem.data.types.ChatData;
import me.jumper251.replay.replaysystem.data.types.PacketData;
import me.jumper251.replay.replaysystem.replaying.Replayer;
import net.techniqstone.buildReplay.core.TaskRunner;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * Exakte Blockzustände für AdvancedReplay. AR speichert bei Blockänderungen nur das Material - Treppen, Stufen,
 * Türen, Ausrichtungen und {@code waterlogged} gehen verloren, Nachbarn (Zäune, Scheiben, Türoberteile) fehlen ganz.
 *
 * <p><b>Aufnahme:</b> Solange für den Spieler eine BuildReplay-Pufferaufnahme läuft, merkt ein MONITOR-Listener bei
 * Platzieren, Abbauen, Eimern und Rechtsklick auf Blöcke die betroffenen Positionen (Block + Nachbarn) mit ihrem
 * Zustand vor der Änderung vor und liest einen Tick später den endgültigen Zustand (Abbau-"nachher", Physik,
 * Nachbarformen). Echte Änderungen landen in einer Warteschlange je Spielername, die AR's Aufnahme-Timer über
 * {@link #onRecord} (asynchron, jeden Tick für jeden aufgezeichneten Namen jeder Aufnahme) als CUSTOM-
 * {@code ChatData} mit {@link FidelityPayload#MARKER} abholt.
 *
 * <p><b>Wiedergabe:</b> {@link #onPlay} (AR-Timer oder Hauptthread) erkennt die Nutzlast und übergibt sie an
 * {@link PlaybackOverlays}, die einen Tick nach AR's eigener Material-Änderung den exakten Zustand per
 * {@code sendMultiBlockChange} an {@code replayer.getWatchingPlayer()} sendet. Zur Richtung (vor/zurück) siehe
 * {@link PlaybackOverlays} - AR übergibt {@code onPlay} keinen Wiedergabemodus.
 */
public final class BlockFidelityHook implements IReplayHook, Listener {

    private static final BlockFace[] NEIGHBOURS = {
            BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
    private static final int MAX_QUEUED_PER_PLAYER = 20_000;
    private static final int MAX_DRAIN_PER_TICK = 2_048;
    private static final int MAX_CHANGES_PER_PAYLOAD = 128;

    private final TaskRunner tasks;
    private final Logger logger;
    private final Predicate<String> recordedPlayer;
    private final PlaybackOverlays overlays;
    /** Hauptthread: vorgemerkte Positionen je Spielername (Zustand vor der Änderung). */
    private final Map<String, Map<BlockKey, Watched>> watching = new HashMap<>();
    private boolean flushScheduled;
    /** Hauptthread schreibt, AR-Timer liest. */
    private final Map<String, PlayerQueue> queues = new ConcurrentHashMap<>();
    private final AtomicBoolean recordErrorLogged = new AtomicBoolean();

    private record Watched(Block block, String before) {
    }

    /** Warteschlange exakter Änderungen eines Spielers mit Größenzähler (Queue#size ist O(n)). */
    private static final class PlayerQueue {
        private final ConcurrentLinkedQueue<FidelityChange> changes = new ConcurrentLinkedQueue<>();
        private final AtomicInteger size = new AtomicInteger();

        private void addAll(List<FidelityChange> added) {
            for (FidelityChange change : added) {
                changes.add(change);
                size.incrementAndGet();
            }
            while (size.get() > MAX_QUEUED_PER_PLAYER && changes.poll() != null) {
                size.decrementAndGet();
            }
        }

        private List<FidelityChange> drain(int max) {
            List<FidelityChange> drained = new ArrayList<>();
            FidelityChange change;
            while (drained.size() < max && (change = changes.poll()) != null) {
                size.decrementAndGet();
                drained.add(change);
            }
            return drained;
        }
    }

    /**
     * @param recordedPlayer ob für diesen Spielernamen gerade eine BuildReplay-Pufferaufnahme läuft (Hauptthread)
     */
    BlockFidelityHook(TaskRunner tasks, Logger logger, Predicate<String> recordedPlayer, PlaybackOverlays overlays) {
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.recordedPlayer = Objects.requireNonNull(recordedPlayer, "recordedPlayer");
        this.overlays = Objects.requireNonNull(overlays, "overlays");
    }

    // ------------------------------------------------------------------ Aufnahme (Hauptthread)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (!recordedPlayer.test(player.getName())) {
            return;
        }
        Map<BlockKey, Watched> watched = watchMap(player.getName());
        if (event instanceof BlockMultiPlaceEvent multi) {
            for (BlockState replaced : multi.getReplacedBlockStates()) {
                watch(watched, replaced.getBlock(), replaced.getBlockData().getAsString());
            }
        }
        watch(watched, event.getBlockPlaced(), event.getBlockReplacedState().getBlockData().getAsString());
        watchNeighbours(watched, event.getBlockPlaced());
        scheduleFlush();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!recordedPlayer.test(player.getName())) {
            return;
        }
        Map<BlockKey, Watched> watched = watchMap(player.getName());
        watchCurrent(watched, event.getBlock());
        watchNeighbours(watched, event.getBlock());
        scheduleFlush();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        watchBucket(event.getPlayer(), event.getBlockClicked(), event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        watchBucket(event.getPlayer(), event.getBlockClicked(), event.getBlock());
    }

    /** Türen, Falltüren, Hebel, Repeater, Ackerboden usw. - AR zeichnet solche Zustandswechsel gar nicht auf. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.useInteractedBlock() == Event.Result.DENY) {
            return;
        }
        Block clicked = event.getClickedBlock();
        Player player = event.getPlayer();
        if (clicked == null || !recordedPlayer.test(player.getName())) {
            return;
        }
        Map<BlockKey, Watched> watched = watchMap(player.getName());
        watchCurrent(watched, clicked);
        watchCurrent(watched, clicked.getRelative(BlockFace.UP));
        watchCurrent(watched, clicked.getRelative(BlockFace.DOWN));
        scheduleFlush();
    }

    private void watchBucket(Player player, Block clicked, Block target) {
        if (!recordedPlayer.test(player.getName())) {
            return;
        }
        Map<BlockKey, Watched> watched = watchMap(player.getName());
        watchCurrent(watched, clicked);
        watchCurrent(watched, target);
        scheduleFlush();
    }

    private Map<BlockKey, Watched> watchMap(String playerName) {
        return watching.computeIfAbsent(playerName, name -> new LinkedHashMap<>());
    }

    private static void watch(Map<BlockKey, Watched> watched, Block block, String before) {
        watched.putIfAbsent(BlockKey.of(block), new Watched(block, before));
    }

    private static void watchCurrent(Map<BlockKey, Watched> watched, Block block) {
        BlockKey key = BlockKey.of(block);
        if (!watched.containsKey(key)) {
            watched.put(key, new Watched(block, block.getBlockData().getAsString()));
        }
    }

    private static void watchNeighbours(Map<BlockKey, Watched> watched, Block center) {
        for (BlockFace face : NEIGHBOURS) {
            watchCurrent(watched, center.getRelative(face));
        }
    }

    private void scheduleFlush() {
        if (!flushScheduled) {
            flushScheduled = true;
            tasks.runOnMainLater(this::flush, 1L);
        }
    }

    /** Hauptthread, einen Tick nach den Ereignissen: endgültige Zustände lesen, echte Änderungen einreihen. */
    private void flush() {
        flushScheduled = false;
        if (watching.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Map<BlockKey, Watched>> entry : watching.entrySet()) {
            List<FidelityChange> changes = new ArrayList<>();
            for (Map.Entry<BlockKey, Watched> watched : entry.getValue().entrySet()) {
                Block block = watched.getValue().block();
                try {
                    if (!block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) {
                        continue;
                    }
                    String after = block.getBlockData().getAsString();
                    if (!after.equals(watched.getValue().before())) {
                        changes.add(new FidelityChange(watched.getKey(), watched.getValue().before(), after));
                    }
                } catch (RuntimeException e) {
                    logger.log(Level.FINE, "Blockzustand bei " + watched.getKey() + " nicht lesbar", e);
                }
            }
            if (!changes.isEmpty()) {
                queues.computeIfAbsent(entry.getKey(), name -> new PlayerQueue()).addAll(changes);
            }
        }
        watching.clear();
    }

    /** Hauptthread: vorgemerkte und wartende Änderungen eines Spielers verwerfen (keine Aufnahme mehr). */
    public void clearPlayer(String playerName) {
        watching.remove(playerName);
        queues.remove(playerName);
    }

    /** Hauptthread: alles verwerfen (Deinstallation). */
    public void clearAll() {
        watching.clear();
        queues.clear();
    }

    /**
     * AR-Aufnahme-Timer (asynchron), jeden Tick für jeden aufgezeichneten Namen jeder laufenden Aufnahme; darf nie
     * {@code null} liefern oder werfen. Zeichnen mehrere AR-Aufnahmen denselben Spieler auf, erhält die zuerst
     * abfragende Aufnahme die Änderungen (AR übergibt die Aufnahme nicht).
     */
    @Override
    public List<PacketData> onRecord(String playerName) {
        try {
            PlayerQueue queue = playerName == null ? null : queues.get(playerName);
            if (queue == null || queue.size.get() <= 0) {
                return List.of();
            }
            List<FidelityChange> drained = queue.drain(MAX_DRAIN_PER_TICK);
            if (drained.isEmpty()) {
                return List.of();
            }
            List<PacketData> payloads = new ArrayList<>();
            for (String message : FidelityPayload.encode(drained, MAX_CHANGES_PER_PAYLOAD)) {
                payloads.add(new ChatData(message));
            }
            return payloads;
        } catch (Throwable t) {
            if (recordErrorLogged.compareAndSet(false, true)) {
                logger.log(Level.WARNING, "Exakte Blockzustände konnten nicht an AdvancedReplay übergeben werden", t);
            }
            return List.of();
        }
    }

    /** AR-Wiedergabe-Timer oder Hauptthread (Springen); für jede CUSTOM-Aktion in jedem Modus. */
    @Override
    public void onPlay(ActionData data, Replayer replayer) {
        try {
            if (data == null || replayer == null || data.getType() != ActionType.CUSTOM
                    || !(data.getPacketData() instanceof ChatData chat) || !FidelityPayload.isPayload(chat.getMessage())) {
                return;
            }
            Player viewer = replayer.getWatchingPlayer();
            if (viewer == null) {
                return;
            }
            List<FidelityChange> changes = FidelityPayload.decode(chat.getMessage());
            overlays.enqueue(viewer, replayer, data.getTickIndex(), changes);
        } catch (Throwable t) {
            logger.log(Level.FINE, "Exakter Blockzustand konnte nicht abgespielt werden", t);
        }
    }
}
