package net.techniqstone.buildReplay.recording.advancedreplay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import me.jumper251.replay.replaysystem.data.ActionData;
import me.jumper251.replay.replaysystem.data.ActionType;
import me.jumper251.replay.replaysystem.data.types.BlockChangeData;
import me.jumper251.replay.replaysystem.data.types.ChatData;
import me.jumper251.replay.replaysystem.data.types.ItemData;
import me.jumper251.replay.replaysystem.data.types.PacketData;
import me.jumper251.replay.replaysystem.data.types.SerializableItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Blockzustände eines Replays je Position über die Zeit, aus den Aktionen vorberechnet. Quellen:
 * <ul>
 *   <li>BuildReplays exakte Nutzlasten ({@link FidelityPayload} in CUSTOM-{@code ChatData}) - haben Vorrang,</li>
 *   <li>sonst AdvancedReplays {@code BlockChangeData} (nur Material; Standardzustand des Materials).</li>
 * </ul>
 * Damit lassen sich der Anfangszustand (erstes "vorher" je Position, damit der Bau sichtbar wächst) und der
 * Zustand zu einem beliebigen Wiedergabe-Tick bestimmen - unabhängig davon, ob AR vor- oder zurückspult.
 * Unveränderlich und damit threadsicher.
 */
final class BlockTimeline {

    /** Eine Änderung an einer Position (Tick = Schlüssel in AdvancedReplays Aktionstabelle). */
    record Change(int tick, String before, String after) {
    }

    /** Chunk-Schlüssel für den Index der Positionen pro Chunk. */
    private record ChunkKey(String world, int x, int z) {
    }

    private static final BlockTimeline EMPTY = new BlockTimeline(Map.of());

    private final Map<BlockKey, List<Change>> changes;
    private final Map<ChunkKey, List<BlockKey>> byChunk;

    private BlockTimeline(Map<BlockKey, List<Change>> changes) {
        this.changes = changes;
        Map<ChunkKey, List<BlockKey>> index = new HashMap<>();
        for (BlockKey key : changes.keySet()) {
            index.computeIfAbsent(new ChunkKey(key.world(), key.chunkX(), key.chunkZ()), k -> new ArrayList<>()).add(key);
        }
        this.byChunk = index;
    }

    static BlockTimeline empty() {
        return EMPTY;
    }

    /** Liest alle Blockänderungen aus den Aktionen (Ticks aufsteigend, innerhalb eines Ticks in Listenreihenfolge). */
    static BlockTimeline fromActions(Map<Integer, List<ActionData>> actions) {
        Map<BlockKey, List<Change>> exact = new LinkedHashMap<>();
        Map<BlockKey, List<Change>> materialOnly = new LinkedHashMap<>();
        for (Integer tick : new TreeSet<>(actions.keySet())) {
            List<ActionData> list = actions.get(tick);
            if (tick == null || list == null) {
                continue;
            }
            for (ActionData action : list) {
                if (action == null) {
                    continue;
                }
                PacketData data = action.getPacketData();
                if (action.getType() == ActionType.CUSTOM && data instanceof ChatData chat
                        && FidelityPayload.isPayload(chat.getMessage())) {
                    for (FidelityChange change : FidelityPayload.decode(chat.getMessage())) {
                        exact.computeIfAbsent(change.key(), k -> new ArrayList<>())
                                .add(new Change(tick, change.before(), change.after()));
                    }
                } else if (action.getType() == ActionType.PACKET && data instanceof BlockChangeData block
                        && block.getLocation() != null) {
                    String before = materialState(block.getBefore());
                    String after = materialState(block.getAfter());
                    if (before != null && after != null) {
                        materialOnly.computeIfAbsent(BlockKey.of(block.getLocation()), k -> new ArrayList<>())
                                .add(new Change(tick, before, after));
                    }
                }
            }
        }
        Map<BlockKey, List<Change>> merged = new LinkedHashMap<>();
        exact.forEach((key, list) -> merged.put(key, List.copyOf(list)));
        materialOnly.forEach((key, list) -> merged.putIfAbsent(key, List.copyOf(list)));
        return merged.isEmpty() ? EMPTY : new BlockTimeline(Collections.unmodifiableMap(merged));
    }

    /**
     * Blockzustand-Text für das Material einer AR-{@code ItemData} ({@code "minecraft:oak_stairs"}, also den
     * Standardzustand) oder {@code null}, wenn nur eine alte Zahlen-ID vorliegt.
     */
    static @Nullable String materialState(@Nullable ItemData item) {
        if (item == null) {
            return null;
        }
        SerializableItemStack stack = item.getItemStack();
        if (stack == null || stack.getItemStack() == null) {
            return null;
        }
        Object type = stack.getItemStack().get("type");
        if (!(type instanceof String name) || name.isBlank()) {
            return null;
        }
        return "minecraft:" + name.trim().toLowerCase(Locale.ROOT);
    }

    boolean isEmpty() {
        return changes.isEmpty();
    }

    int size() {
        return changes.size();
    }

    Set<BlockKey> keys() {
        return changes.keySet();
    }

    List<Change> changesAt(BlockKey key) {
        return changes.getOrDefault(key, List.of());
    }

    List<BlockKey> keysInChunk(String world, int chunkX, int chunkZ) {
        return byChunk.getOrDefault(new ChunkKey(world, chunkX, chunkZ), List.of());
    }

    /** Anfangszustand: erstes "vorher" je Position (in Reihenfolge des ersten Auftretens). */
    Map<BlockKey, String> initialStates() {
        Map<BlockKey, String> result = new LinkedHashMap<>();
        changes.forEach((key, list) -> result.put(key, list.getFirst().before()));
        return result;
    }

    /**
     * Zustand, den der Zuschauer bei Wiedergabeposition {@code tick} sehen soll: "nachher" der letzten Änderung mit
     * {@code change.tick < tick} (AR hat bei {@code currentTicks = t} die Ticks {@code [0, t)} ausgeführt), sonst das
     * erste "vorher". {@code null}, wenn die Position im Replay nie verändert wird.
     */
    @Nullable String stateAt(BlockKey key, int tick) {
        List<Change> list = changes.get(key);
        if (list == null || list.isEmpty()) {
            return null;
        }
        String state = list.getFirst().before();
        for (Change change : list) {
            if (change.tick() >= tick) {
                break;
            }
            state = change.after();
        }
        return state;
    }

    /**
     * Regel ohne vollständige Zeitachse (z. B. Wiedergaben, die nicht BuildReplay gestartet hat): eine Änderung
     * von Tick {@code actionTick} zeigt "nachher", wenn sie bei Wiedergabeposition {@code replayerTick} bereits
     * ausgeführt ist. Tick 0 führt AR genau einmal beim Start aus und spult ihn nie zurück.
     */
    static boolean showsAfter(int actionTick, int replayerTick) {
        return actionTick == 0 || actionTick < replayerTick;
    }
}
