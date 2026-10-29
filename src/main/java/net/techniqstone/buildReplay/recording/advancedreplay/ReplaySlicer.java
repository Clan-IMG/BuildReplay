package net.techniqstone.buildReplay.recording.advancedreplay;

import com.comphenix.protocol.wrappers.EnumWrappers;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import me.jumper251.replay.replaysystem.data.ActionData;
import me.jumper251.replay.replaysystem.data.ActionType;
import me.jumper251.replay.replaysystem.data.ReplayData;
import me.jumper251.replay.replaysystem.data.types.EntityActionData;
import me.jumper251.replay.replaysystem.data.types.EntityAnimationData;
import me.jumper251.replay.replaysystem.data.types.EntityData;
import me.jumper251.replay.replaysystem.data.types.EntityDestroyData;
import me.jumper251.replay.replaysystem.data.types.EntityItemData;
import me.jumper251.replay.replaysystem.data.types.EntityMovingData;
import me.jumper251.replay.replaysystem.data.types.FishingData;
import me.jumper251.replay.replaysystem.data.types.InvData;
import me.jumper251.replay.replaysystem.data.types.LocationData;
import me.jumper251.replay.replaysystem.data.types.MetadataUpdate;
import me.jumper251.replay.replaysystem.data.types.MovingData;
import me.jumper251.replay.replaysystem.data.types.PacketData;
import me.jumper251.replay.replaysystem.data.types.SpawnData;
import me.jumper251.replay.replaysystem.data.types.TNTSpawnData;
import me.jumper251.replay.replaysystem.data.types.VelocityData;
import me.jumper251.replay.replaysystem.data.types.WorldChangeData;
import org.jspecify.annotations.Nullable;

/**
 * Schneidet einen Tick-Bereich {@code [from, to)} aus einer Rohaufnahme als eigenständiges AdvancedReplay aus.
 *
 * <ul>
 *   <li>Ticks werden auf {@code t - from} neu verschlüsselt, als <em>neue</em> {@link ActionData}-Objekte
 *       (AR liest beim Abspielen nur den Schlüssel, BuildReplays Hook aber {@code getTickIndex()}).</li>
 *   <li>Am neuen Tick 0 wird <em>vor</em> den ursprünglichen Aktionen der Zustand am Schnitt nachgebildet:
 *       SPAWN (UUID und Skin-Signatur der ursprünglichen Spawn-Aktion, Position der letzten Bewegung samt
 *       Blickrichtung, Welt der letzten Spawn-/Weltwechsel-Aktion), das letzte Inventar, Schleichen und aktive
 *       Metadaten (Brennen, Blocken, Gleiten, Schwimmen). AR führt PACKET-Aktionen nur aus, wenn das NPC
 *       bereits existiert, und {@code Replayer.start()} nutzt die Spawn-Aktion von Tick 0 für Weltprüfung und
 *       Teleport.</li>
 *   <li>Kein Spawn, wenn der Spieler am Schnitt nicht anwesend war (DESPAWN/DEATH) oder Tick 0 bereits einen
 *       SPAWN für ihn enthält - doppelte Spawns hinterlassen in AR Geister-NPCs.</li>
 *   <li>Entity-Aktionen (Bewegung, Animation, Zerstören, Geschwindigkeit, Item-/Haken-Entfernen) für Entities,
 *       die vor dem Schnitt gespawnt wurden, werden verworfen - AR würde sonst z. B. bei einem verwaisten
 *       {@code EntityData(1)} eine neue Entity erzeugen.</li>
 *   <li>Blockänderungen und CUSTOM-Aktionen (exakte Blockzustände) bleiben erhalten.</li>
 *   <li>Dauer = {@code max(to - from, letzter Schlüssel + 1)}; {@code to} wird auf das Ende der Rohaufnahme
 *       begrenzt, damit am Ende keine leeren Ticks abgespielt werden.</li>
 * </ul>
 * {@code new ReplayData()} liest AdvancedReplays {@code ConfigManager}; die Fabrik ist deshalb injizierbar.
 */
public final class ReplaySlicer {

    /**
     * Ergebnis eines Schnitts.
     *
     * @param worldName       Welt des Spawns an Tick 0 (sonst des ersten Spawns im Abschnitt)
     * @param injectedSpawns  Anzahl am Tick 0 nachgebildeter Spieler
     * @param droppedOrphans  verworfene Entity-Aktionen ohne Spawn im Abschnitt
     */
    public record Slice(ReplayData data, int durationTicks, @Nullable String worldName, int actionCount,
                        int injectedSpawns, int droppedOrphans) {
    }

    private final Supplier<ReplayData> dataFactory;

    public ReplaySlicer(Supplier<ReplayData> dataFactory) {
        this.dataFactory = Objects.requireNonNull(dataFactory, "dataFactory");
    }

    // AR zeichnet Schleichen mit den (in ProtocolLib veralteten) Konstanten START_/STOP_SNEAKING auf und vergleicht
    // beim Abspielen genau mit diesen - daher dieselben Konstanten.
    @SuppressWarnings("deprecation")
    public Slice slice(Map<Integer, List<ActionData>> source, int sourceDuration, @Nullable String creator,
                       int fromTick, int toTick) {
        if (fromTick < 0 || toTick <= fromTick) {
            throw new IllegalArgumentException("Ungültiger Schnitt " + fromTick + ".." + toTick);
        }
        TreeSet<Integer> ticks = new TreeSet<>();
        for (Map.Entry<Integer, List<ActionData>> entry : source.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                ticks.add(entry.getKey());
            }
        }
        int sourceEnd = Math.max(sourceDuration, ticks.isEmpty() ? 0 : ticks.last() + 1);
        int effectiveTo = sourceEnd > fromTick ? Math.min(toTick, sourceEnd) : toTick;

        // 1. Zustand am Schnitt aus allen Ticks davor
        Map<String, PlayerState> states = new LinkedHashMap<>();
        Set<Integer> spawnedBeforeCut = new HashSet<>();
        for (Integer tick : ticks.headSet(fromTick, false)) {
            for (ActionData action : source.get(tick)) {
                if (action != null) {
                    track(states, spawnedBeforeCut, action);
                }
            }
        }

        // 2. Ticks im Bereich kopieren
        ReplayData out = dataFactory.get();
        HashMap<Integer, List<ActionData>> target = out.getActions();
        Set<Integer> orphans = new HashSet<>(spawnedBeforeCut);
        int dropped = 0;
        int lastKey = -1;
        for (Integer tick : ticks.subSet(fromTick, true, effectiveTo, false)) {
            int newKey = tick - fromTick;
            List<ActionData> copy = new ArrayList<>();
            for (ActionData action : source.get(tick)) {
                if (action == null) {
                    continue;
                }
                PacketData data = action.getPacketData();
                Integer spawnedId = spawnedEntityId(data);
                if (spawnedId != null) {
                    orphans.remove(spawnedId);
                } else {
                    Integer referenced = referencedEntityId(data);
                    if (referenced != null && orphans.contains(referenced)) {
                        dropped++;
                        continue;
                    }
                }
                copy.add(new ActionData(newKey, action.getType(), action.getName(), data));
            }
            if (!copy.isEmpty()) {
                target.put(newKey, copy);
                lastKey = newKey;
            }
        }

        // 3. Zustand am neuen Tick 0 vor den ursprünglichen Aktionen einfügen
        List<ActionData> originalZero = target.getOrDefault(0, List.of());
        Set<String> spawnedAtZero = new HashSet<>();
        for (ActionData action : originalZero) {
            if (action.getType() == ActionType.SPAWN && action.getName() != null) {
                spawnedAtZero.add(action.getName());
            }
        }
        List<ActionData> injected = new ArrayList<>();
        int injectedSpawns = 0;
        for (PlayerState state : states.values()) {
            if (!state.present || state.spawn == null || state.world == null || spawnedAtZero.contains(state.name)) {
                continue;
            }
            LocationData location = new LocationData(state.x, state.y, state.z, state.world);
            location.setYaw(state.yaw);
            location.setPitch(state.pitch);
            UUID uuid = state.spawn.getUuid();
            injected.add(new ActionData(0, ActionType.SPAWN, state.name,
                    new SpawnData(uuid, location, state.spawn.getSignature())));
            if (state.inventory != null) {
                injected.add(new ActionData(0, ActionType.PACKET, state.name, state.inventory));
            }
            if (state.sneaking) {
                injected.add(new ActionData(0, ActionType.PACKET, state.name,
                        new EntityActionData(EnumWrappers.PlayerAction.START_SNEAKING)));
            }
            if (state.metadata != null && isActive(state.metadata)) {
                injected.add(new ActionData(0, ActionType.PACKET, state.name, state.metadata));
            }
            injectedSpawns++;
        }
        if (!injected.isEmpty()) {
            List<ActionData> merged = new ArrayList<>(injected.size() + originalZero.size());
            merged.addAll(injected);
            merged.addAll(originalZero);
            target.put(0, merged);
            lastKey = Math.max(lastKey, 0);
        }

        int duration = Math.max(effectiveTo - fromTick, lastKey + 1);
        out.setDuration(duration);
        out.setCreator(creator);
        int actionCount = 0;
        for (List<ActionData> list : target.values()) {
            actionCount += list.size();
        }
        return new Slice(out, duration, spawnWorld(target), actionCount, injectedSpawns, dropped);
    }

    @SuppressWarnings("deprecation")
    private static void track(Map<String, PlayerState> states, Set<Integer> spawnedEntities, ActionData action) {
        PacketData data = action.getPacketData();
        Integer spawnedId = spawnedEntityId(data);
        if (spawnedId != null) {
            spawnedEntities.add(spawnedId);
        }
        String name = action.getName();
        if (name == null || action.getType() == null) {
            return;
        }
        switch (action.getType()) {
            case SPAWN -> {
                if (data instanceof SpawnData spawn) {
                    PlayerState state = states.computeIfAbsent(name, PlayerState::new);
                    state.spawn = spawn;
                    state.present = true;
                    state.sneaking = false;
                    state.metadata = null;
                    LocationData location = spawn.getLocation();
                    if (location != null) {
                        state.x = location.getX();
                        state.y = location.getY();
                        state.z = location.getZ();
                        state.yaw = location.getYaw();
                        state.pitch = location.getPitch();
                        if (location.getWorld() != null) {
                            state.world = location.getWorld();
                        }
                    }
                }
            }
            case DESPAWN, DEATH -> {
                PlayerState state = states.get(name);
                if (state != null) {
                    state.present = false;
                    state.sneaking = false;
                    state.metadata = null;
                }
            }
            case PACKET -> {
                PlayerState state = states.computeIfAbsent(name, PlayerState::new);
                if (data instanceof MovingData moving) {
                    state.x = moving.getX();
                    state.y = moving.getY();
                    state.z = moving.getZ();
                    state.yaw = moving.getYaw();
                    state.pitch = moving.getPitch();
                } else if (data instanceof WorldChangeData worldChange && worldChange.getLocation() != null) {
                    LocationData location = worldChange.getLocation();
                    state.x = location.getX();
                    state.y = location.getY();
                    state.z = location.getZ();
                    if (location.getWorld() != null) {
                        state.world = location.getWorld();
                    }
                } else if (data instanceof InvData inventory) {
                    state.inventory = inventory;
                } else if (data instanceof EntityActionData entityAction && entityAction.getAction() != null) {
                    if (entityAction.getAction() == EnumWrappers.PlayerAction.START_SNEAKING) {
                        state.sneaking = true;
                    } else if (entityAction.getAction() == EnumWrappers.PlayerAction.STOP_SNEAKING) {
                        state.sneaking = false;
                    }
                } else if (data instanceof MetadataUpdate metadata) {
                    state.metadata = metadata;
                }
            }
            default -> {
                // MESSAGE, WORLD, CUSTOM verändern den Spielerzustand nicht
            }
        }
    }

    /** Entity-ID, die diese Aktion erzeugt (Mob, Item, Angelhaken, TNT), sonst {@code null}. */
    static @Nullable Integer spawnedEntityId(@Nullable PacketData data) {
        if (data instanceof EntityData entity && entity.getAction() == 0) {
            return entity.getId();
        }
        if (data instanceof EntityItemData item && item.getAction() == 0) {
            return item.getId();
        }
        if (data instanceof FishingData fishing) {
            return fishing.getId();
        }
        if (data instanceof TNTSpawnData tnt) {
            return tnt.getId();
        }
        return null;
    }

    /** Entity-ID, auf die sich diese Aktion bezieht, ohne sie zu erzeugen, sonst {@code null}. */
    static @Nullable Integer referencedEntityId(@Nullable PacketData data) {
        if (data instanceof EntityData entity && entity.getAction() != 0) {
            return entity.getId();
        }
        if (data instanceof EntityItemData item && item.getAction() != 0) {
            return item.getId();
        }
        if (data instanceof EntityMovingData moving) {
            return moving.getId();
        }
        if (data instanceof EntityAnimationData animation) {
            return animation.getEntId();
        }
        if (data instanceof EntityDestroyData destroy) {
            return destroy.getId();
        }
        if (data instanceof VelocityData velocity) {
            return velocity.getId();
        }
        return null;
    }

    private static boolean isActive(MetadataUpdate metadata) {
        return metadata.isBurning() || metadata.isBlocking() || metadata.isGliding() || metadata.isSwimming();
    }

    private static @Nullable String spawnWorld(Map<Integer, List<ActionData>> actions) {
        for (Integer tick : new TreeSet<>(actions.keySet())) {
            for (ActionData action : actions.get(tick)) {
                if (action.getPacketData() instanceof SpawnData spawn && spawn.getLocation() != null
                        && spawn.getLocation().getWorld() != null) {
                    return spawn.getLocation().getWorld();
                }
            }
        }
        return null;
    }

    /** Zustand eines aufgezeichneten Spielers bis zum Schnitt. */
    private static final class PlayerState {
        private final String name;
        private @Nullable SpawnData spawn;
        private boolean present;
        private @Nullable String world;
        private double x;
        private double y;
        private double z;
        private float yaw;
        private float pitch;
        private @Nullable InvData inventory;
        private boolean sneaking;
        private @Nullable MetadataUpdate metadata;

        private PlayerState(String name) {
            this.name = name;
        }
    }
}
