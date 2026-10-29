package net.techniqstone.buildReplay.recording.advancedreplay;

import io.papermc.paper.math.BlockPosition;
import io.papermc.paper.math.Position;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import me.jumper251.replay.replaysystem.Replay;
import me.jumper251.replay.replaysystem.replaying.ReplayHelper;
import me.jumper251.replay.replaysystem.replaying.Replayer;
import net.techniqstone.buildReplay.core.TaskRunner;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Clientseitige Blockzustände je Zuschauer während einer Wiedergabe:
 * <ul>
 *   <li>Anfangszustand (erstes "vorher" je Position), damit der Bau sichtbar wächst statt von Anfang an fertig
 *       dazustehen (AR spielt auf der heutigen Welt),</li>
 *   <li>exakte Zustände aus BuildReplays Nutzlasten (AR selbst sendet nur Material-Standardzustände),</li>
 *   <li>erneutes Senden, wenn der Client einen Chunk neu lädt (sonst zeigt er wieder die echte Welt),</li>
 *   <li>am Ende die echten Blöcke für alle berührten Positionen (AR stellt clientseitige Änderungen nicht zurück).</li>
 * </ul>
 * Zustand im Hauptthread; {@link #enqueue} und {@link #onChunkLoad} dürfen von beliebigen Threads kommen.
 */
final class PlaybackOverlays {

    private static final int PARSE_CACHE_LIMIT = 8_192;
    /** AR plant seine eigenen Blockänderungen per {@code runTask}; wir senden einen Tick später, also danach. */
    private static final long APPLY_DELAY_TICKS = 1L;
    /** Nach dem Ende warten, bis AR's letzte geplante Blockänderungen gelaufen sind. */
    private static final long RESTORE_DELAY_TICKS = 2L;

    private final TaskRunner tasks;
    private final Logger logger;
    private final Map<UUID, ViewerOverlay> overlays = new ConcurrentHashMap<>();
    private final Map<String, Optional<BlockData>> parsed = new HashMap<>();

    PlaybackOverlays(TaskRunner tasks, Logger logger) {
        this.tasks = tasks;
        this.logger = logger;
    }

    /** Anstehende exakte Änderungen eines {@code onPlay}-Aufrufs. */
    private record PendingBatch(Replayer replayer, int tick, List<FidelityChange> changes) {
    }

    /** Overlay eines Zuschauers. */
    static final class ViewerOverlay {
        private final UUID viewer;
        private final BlockTimeline timeline;
        private final boolean timelineStates;
        private volatile @Nullable Replayer replayer;
        private volatile boolean ended;
        private final Map<BlockKey, String> applied = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<PendingBatch> pending = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean flushScheduled = new AtomicBoolean();

        private ViewerOverlay(UUID viewer, BlockTimeline timeline, boolean timelineStates) {
            this.viewer = viewer;
            this.timeline = timeline;
            this.timelineStates = timelineStates;
        }

        /** Positionen, deren echte Blöcke am Ende wiederhergestellt werden. */
        private Set<BlockKey> touched() {
            Set<BlockKey> keys = new HashSet<>(timeline.keys());
            keys.addAll(applied.keySet());
            return keys;
        }

        private boolean knows(BlockKey key) {
            return !timeline.changesAt(key).isEmpty() || applied.containsKey(key);
        }

        /** Wiedergabeposition für die Zeitachse; Tick 0 führt AR beim Start synchron aus. */
        private int effectiveTick() {
            Replayer current = replayer;
            return current == null ? 0 : Math.max(1, current.getCurrentTicks());
        }
    }

    /**
     * Hauptthread, vor {@code Replay#play}: Overlay registrieren (Chunk-Ladungen während des Teleports werden
     * damit bereits bedient). {@code timelineStates=true} zeigt den Anfangszustand und alle Positionen gemäß
     * Zeitachse, sonst nur bereits abgespielte exakte Änderungen.
     */
    ViewerOverlay begin(Player viewer, BlockTimeline timeline, boolean timelineStates) {
        ViewerOverlay overlay = new ViewerOverlay(viewer.getUniqueId(), timeline, timelineStates);
        ViewerOverlay previous = overlays.put(viewer.getUniqueId(), overlay);
        if (previous != null) {
            previous.ended = true;
            previous.pending.clear();
        }
        return overlay;
    }

    /** Hauptthread, direkt nach dem Start: Replayer für Positionsabfragen setzen. */
    void attach(ViewerOverlay overlay, Replayer replayer) {
        overlay.replayer = replayer;
    }

    /** Hauptthread: alle Overlay-Zustände der aktuellen Welt des Zuschauers senden. */
    void sendAll(Player viewer) {
        ViewerOverlay overlay = overlays.get(viewer.getUniqueId());
        if (overlay == null || overlay.ended || !viewer.isOnline()) {
            return;
        }
        String world = viewer.getWorld().getName();
        Map<BlockKey, String> states = new LinkedHashMap<>();
        if (overlay.timelineStates) {
            int tick = overlay.effectiveTick();
            for (BlockKey key : overlay.timeline.keys()) {
                if (key.world().equals(world)) {
                    String state = overlay.timeline.stateAt(key, tick);
                    if (state != null) {
                        states.put(key, state);
                    }
                }
            }
        }
        overlay.applied.forEach((key, state) -> {
            if (key.world().equals(world)) {
                states.putIfAbsent(key, state);
            }
        });
        send(viewer, states);
    }

    /**
     * Beliebiger Thread (AR-Timer beim Abspielen, Hauptthread beim Springen): exakte Änderungen eines Ticks
     * vormerken; angewendet wird gebündelt einen Tick später auf dem Hauptthread.
     */
    void enqueue(Player viewer, Replayer replayer, int tick, List<FidelityChange> changes) {
        if (changes.isEmpty()) {
            return;
        }
        ViewerOverlay overlay = overlays.computeIfAbsent(viewer.getUniqueId(),
                id -> new ViewerOverlay(id, BlockTimeline.empty(), false));
        if (overlay.ended) {
            return;
        }
        overlay.pending.add(new PendingBatch(replayer, tick, changes));
        if (overlay.flushScheduled.compareAndSet(false, true)) {
            tasks.runOnMainLater(() -> flush(viewer, overlay), APPLY_DELAY_TICKS);
        }
    }

    /**
     * Regel für Rückwärtssprünge: AR ruft {@code onPlay} ohne Wiedergabemodus auf. Beim Abspielen (Timer) gilt
     * während des Aufrufs {@code currentTicks = tick + 1}; beim Vorspulen werden die Ticks {@code [c, ziel)} und
     * beim Zurückspulen {@code (ziel, c]} ausgeführt, {@code currentTicks} wird erst nach der Schleife auf das Ziel
     * gesetzt. Deshalb wird erst einen Tick später entschieden: mit Zeitachse exakt über
     * {@link BlockTimeline#stateAt}, sonst "nachher" genau dann, wenn {@code tick < currentTicks}
     * ({@link BlockTimeline#showsAfter}); "vorher"-Zustände eines Ticks in umgekehrter Reihenfolge.
     */
    private void flush(Player viewer, ViewerOverlay overlay) {
        overlay.flushScheduled.set(false);
        if (overlay.ended || !viewer.isOnline()) {
            overlay.pending.clear();
            return;
        }
        Map<BlockKey, String> states = new LinkedHashMap<>();
        PendingBatch batch;
        while ((batch = overlay.pending.poll()) != null) {
            Replayer active = ReplayHelper.replaySessions.get(viewer.getName());
            if (active != batch.replayer()) {
                continue;
            }
            int current = batch.replayer().getCurrentTicks();
            if (overlay.timelineStates && overlay.replayer == batch.replayer()) {
                int tick = Math.max(1, current);
                for (FidelityChange change : batch.changes()) {
                    String state = overlay.timeline.stateAt(change.key(), tick);
                    if (state == null) {
                        state = BlockTimeline.showsAfter(batch.tick(), current) ? change.after() : change.before();
                    }
                    states.put(change.key(), state);
                }
            } else {
                boolean after = BlockTimeline.showsAfter(batch.tick(), current);
                List<FidelityChange> ordered = batch.changes();
                if (!after) {
                    ordered = new ArrayList<>(ordered);
                    Collections.reverse(ordered);
                }
                for (FidelityChange change : ordered) {
                    String state = after ? change.after() : change.before();
                    states.put(change.key(), state);
                    overlay.applied.put(change.key(), state);
                }
            }
        }
        if (overlay.timeline.isEmpty() && overlay.applied.isEmpty() && !ReplayHelper.replaySessions
                .containsKey(viewer.getName())) {
            overlays.remove(overlay.viewer, overlay);
        }
        send(viewer, filterWorld(states, viewer.getWorld().getName()));
    }

    /** Beliebiger Thread: der Client hat einen Chunk (neu) erhalten - Overlay für diesen Chunk erneut senden. */
    void onChunkLoad(Player viewer, String world, int chunkX, int chunkZ) {
        ViewerOverlay overlay = overlays.get(viewer.getUniqueId());
        if (overlay == null || overlay.ended) {
            return;
        }
        boolean relevant = !overlay.timeline.keysInChunk(world, chunkX, chunkZ).isEmpty();
        if (!relevant) {
            for (BlockKey key : overlay.applied.keySet()) {
                if (key.inChunk(world, chunkX, chunkZ)) {
                    relevant = true;
                    break;
                }
            }
        }
        if (relevant) {
            tasks.runOnMainLater(() -> resendChunk(viewer, overlay, world, chunkX, chunkZ), APPLY_DELAY_TICKS);
        }
    }

    private void resendChunk(Player viewer, ViewerOverlay overlay, String world, int chunkX, int chunkZ) {
        if (overlay.ended || !viewer.isOnline() || !viewer.getWorld().getName().equals(world)) {
            return;
        }
        Map<BlockKey, String> states = new LinkedHashMap<>();
        if (overlay.timelineStates) {
            int tick = overlay.effectiveTick();
            for (BlockKey key : overlay.timeline.keysInChunk(world, chunkX, chunkZ)) {
                String state = overlay.timeline.stateAt(key, tick);
                if (state != null) {
                    states.put(key, state);
                }
            }
        }
        overlay.applied.forEach((key, state) -> {
            if (key.inChunk(world, chunkX, chunkZ)) {
                states.putIfAbsent(key, state);
            }
        });
        send(viewer, states);
    }

    boolean has(UUID viewer) {
        return overlays.containsKey(viewer);
    }

    /**
     * Hauptthread, Wiedergabe beendet: Overlay entfernen und zwei Ticks später die echten Blöcke aller berührten
     * Positionen senden - außer Positionen, die eine inzwischen gestartete nächste Wiedergabe selbst verwaltet.
     */
    void end(Player viewer) {
        end(viewer, null);
    }

    /**
     * Wie {@link #end(Player)}, aber nur, wenn das Overlay zu {@code replay} gehört (oder noch keinem Replayer
     * zugeordnet ist) - ein verspätetes Ende-Ereignis einer alten Sitzung räumt keine neue ab.
     */
    void end(Player viewer, @Nullable Replay replay) {
        ViewerOverlay overlay = overlays.get(viewer.getUniqueId());
        if (overlay == null) {
            return;
        }
        Replayer owner = overlay.replayer;
        if (replay != null && owner != null && owner.getReplay() != replay) {
            return;
        }
        overlays.remove(viewer.getUniqueId(), overlay);
        overlay.ended = true;
        overlay.pending.clear();
        Set<BlockKey> restore = overlay.touched();
        if (restore.isEmpty()) {
            return;
        }
        tasks.runOnMainLater(() -> restoreReal(viewer, restore), RESTORE_DELAY_TICKS);
    }

    /** Hauptthread: Overlay ohne Wiederherstellung verwerfen (Start gescheitert, noch nichts gesendet). */
    void discard(Player viewer) {
        ViewerOverlay overlay = overlays.remove(viewer.getUniqueId());
        if (overlay != null) {
            overlay.ended = true;
            overlay.pending.clear();
        }
    }

    /** Hauptthread: alle Overlays verwerfen (Deinstallation). */
    void clearAll() {
        for (ViewerOverlay overlay : overlays.values()) {
            overlay.ended = true;
            overlay.pending.clear();
        }
        overlays.clear();
        parsed.clear();
    }

    private void restoreReal(Player viewer, Set<BlockKey> keys) {
        if (!viewer.isOnline()) {
            return;
        }
        ViewerOverlay next = overlays.get(viewer.getUniqueId());
        World world = viewer.getWorld();
        String worldName = world.getName();
        Map<Position, BlockData> real = new HashMap<>();
        for (BlockKey key : keys) {
            if (!key.world().equals(worldName) || (next != null && !next.ended && next.knows(key))) {
                continue;
            }
            if (world.isChunkLoaded(key.chunkX(), key.chunkZ())) {
                real.put(Position.block(key.x(), key.y(), key.z()),
                        world.getBlockAt(key.x(), key.y(), key.z()).getBlockData());
            }
        }
        sendRaw(viewer, real);
    }

    private static Map<BlockKey, String> filterWorld(Map<BlockKey, String> states, String world) {
        if (states.isEmpty()) {
            return states;
        }
        Map<BlockKey, String> result = new LinkedHashMap<>();
        states.forEach((key, state) -> {
            if (key.world().equals(world)) {
                result.put(key, state);
            }
        });
        return result;
    }

    private void send(Player viewer, Map<BlockKey, String> states) {
        if (states.isEmpty()) {
            return;
        }
        Map<Position, BlockData> packet = new HashMap<>();
        for (Map.Entry<BlockKey, String> entry : states.entrySet()) {
            BlockData data = parse(entry.getValue());
            if (data != null) {
                BlockKey key = entry.getKey();
                BlockPosition position = Position.block(key.x(), key.y(), key.z());
                packet.put(position, data);
            }
        }
        sendRaw(viewer, packet);
    }

    private void sendRaw(Player viewer, Map<Position, BlockData> packet) {
        if (packet.isEmpty()) {
            return;
        }
        try {
            viewer.sendMultiBlockChange(packet);
        } catch (RuntimeException e) {
            logger.log(Level.FINE, "Blockänderungen an " + viewer.getName() + " konnten nicht gesendet werden", e);
        }
    }

    private @Nullable BlockData parse(String state) {
        Optional<BlockData> cached = parsed.get(state);
        if (cached == null) {
            if (parsed.size() >= PARSE_CACHE_LIMIT) {
                parsed.clear();
            }
            try {
                cached = Optional.of(Bukkit.createBlockData(state));
            } catch (IllegalArgumentException e) {
                logger.fine("Unbekannter Blockzustand im Replay: " + state);
                cached = Optional.empty();
            }
            parsed.put(state, cached);
        }
        return cached.orElse(null);
    }
}
