package net.techniqstone.buildReplay.recording.advancedreplay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import me.jumper251.replay.replaysystem.data.ActionData;
import me.jumper251.replay.replaysystem.data.ActionType;
import me.jumper251.replay.replaysystem.data.ReplayData;
import me.jumper251.replay.replaysystem.data.types.SpawnData;
import net.techniqstone.buildReplay.recording.RawRecording;
import org.jspecify.annotations.Nullable;

/**
 * Von AdvancedReplay unabhängige Kopie einer abgeschlossenen Pufferaufnahme. Die Aktionstabelle ist eine tiefe
 * Kopie (neue Map, neue Liste je Tick); die {@code PacketData}-Objekte selbst werden geteilt, da AR sie nach dem
 * Stoppen nicht mehr verändert. Nur lesend verwendet und damit threadsicher.
 */
final class RawSnapshot implements RawRecording {

    /** Vom Engine-Zustand bekannte Identität einer Pufferaufnahme (Vorrang vor der Spawn-Aktion). */
    record KnownPlayer(String name, @Nullable UUID uuid, @Nullable String world) {
    }

    private static final int COPY_ATTEMPTS = 5;

    private final String rawName;
    private final String playerName;
    private final @Nullable UUID playerUuid;
    private final int durationTicks;
    private final int actionCount;
    private final @Nullable String worldName;
    private final @Nullable String creator;
    private final Map<Integer, List<ActionData>> actions;

    private RawSnapshot(String rawName, String playerName, @Nullable UUID playerUuid, int durationTicks,
                        int actionCount, @Nullable String worldName, @Nullable String creator,
                        Map<Integer, List<ActionData>> actions) {
        this.rawName = rawName;
        this.playerName = playerName;
        this.playerUuid = playerUuid;
        this.durationTicks = durationTicks;
        this.actionCount = actionCount;
        this.worldName = worldName;
        this.creator = creator;
        this.actions = actions;
    }

    /** Kopiert die Daten einer gestoppten (oder geladenen) Aufnahme. */
    static RawSnapshot of(String rawName, ReplayData data, @Nullable KnownPlayer known) {
        Map<Integer, List<ActionData>> copy = copyActions(data.getActions());
        int count = 0;
        int lastKey = -1;
        for (Map.Entry<Integer, List<ActionData>> entry : copy.entrySet()) {
            count += entry.getValue().size();
            lastKey = Math.max(lastKey, entry.getKey());
        }
        SpawnData firstSpawn = null;
        String spawnName = null;
        for (Integer tick : new TreeSet<>(copy.keySet())) {
            for (ActionData action : copy.get(tick)) {
                if (action.getType() == ActionType.SPAWN && action.getPacketData() instanceof SpawnData spawn) {
                    firstSpawn = spawn;
                    spawnName = action.getName();
                    break;
                }
            }
            if (firstSpawn != null) {
                break;
            }
        }
        String name = known != null ? known.name()
                : spawnName != null ? spawnName
                : data.getCreator() != null ? data.getCreator() : "?";
        UUID uuid = known != null && known.uuid() != null ? known.uuid()
                : firstSpawn != null ? firstSpawn.getUuid() : null;
        String spawnWorld = firstSpawn != null && firstSpawn.getLocation() != null
                ? firstSpawn.getLocation().getWorld() : null;
        String world = spawnWorld != null ? spawnWorld : known != null ? known.world() : null;
        int duration = data.getDuration() > 0 ? data.getDuration() : lastKey + 1;
        return new RawSnapshot(rawName, name, uuid, Math.max(0, duration), count, world, data.getCreator(),
                Collections.unmodifiableMap(copy));
    }

    /**
     * Neue Map und neue Liste je Tick. AR schreibt die Tabelle ohne Synchronisierung (Timer-Thread und
     * Hauptthread); läuft beim Stoppen noch ein Timer-Durchlauf, wird die Kopie wiederholt.
     */
    static Map<Integer, List<ActionData>> copyActions(Map<Integer, List<ActionData>> source) {
        ConcurrentModificationException last = null;
        for (int attempt = 0; attempt < COPY_ATTEMPTS; attempt++) {
            try {
                Map<Integer, List<ActionData>> copy = new HashMap<>(Math.max(16, source.size() * 2));
                for (Map.Entry<Integer, List<ActionData>> entry : new ArrayList<>(source.entrySet())) {
                    if (entry.getKey() == null || entry.getValue() == null) {
                        continue;
                    }
                    List<ActionData> list = new ArrayList<>(entry.getValue().size());
                    for (ActionData action : entry.getValue()) {
                        if (action != null) {
                            list.add(action);
                        }
                    }
                    if (!list.isEmpty()) {
                        copy.put(entry.getKey(), list);
                    }
                }
                return copy;
            } catch (ConcurrentModificationException e) {
                last = e;
                Thread.onSpinWait();
            }
        }
        throw last;
    }

    /** Unveränderliche Sicht auf die kopierte Aktionstabelle. */
    Map<Integer, List<ActionData>> actions() {
        return actions;
    }

    @Nullable String creator() {
        return creator;
    }

    /** Ersteller für {@code ReplayInfo}: AR-Ersteller (Name des aufnehmenden Senders) oder Spielername. */
    String creatorOrPlayer() {
        return creator != null && !creator.isBlank() ? creator : playerName;
    }

    @Override
    public String rawName() {
        return rawName;
    }

    @Override
    public String playerName() {
        return playerName;
    }

    @Override
    public @Nullable UUID playerUuid() {
        return playerUuid;
    }

    @Override
    public int durationTicks() {
        return durationTicks;
    }

    @Override
    public int actionCount() {
        return actionCount;
    }

    @Override
    public @Nullable String worldName() {
        return worldName;
    }

    @Override
    public String toString() {
        return "RawSnapshot[" + rawName + ", " + playerName + ", " + durationTicks + " Ticks, " + actionCount
                + " Aktionen]";
    }
}
