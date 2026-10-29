package net.techniqstone.buildReplay.recording.advancedreplay;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import me.jumper251.replay.replaysystem.data.ActionData;
import me.jumper251.replay.replaysystem.data.ActionType;
import me.jumper251.replay.replaysystem.data.ReplayData;
import me.jumper251.replay.replaysystem.data.types.ItemData;
import me.jumper251.replay.replaysystem.data.types.LocationData;
import me.jumper251.replay.replaysystem.data.types.PacketData;
import me.jumper251.replay.replaysystem.data.types.SerializableItemStack;
import me.jumper251.replay.replaysystem.data.types.SignatureData;
import me.jumper251.replay.replaysystem.data.types.SpawnData;

/**
 * Testhilfen für AdvancedReplay-Daten ohne laufendes AR: {@code new ReplayData()} liest {@code ConfigManager},
 * dessen statischer Initialisierer {@code ReplaySystem.getInstance()} braucht. Daher wird die Instanz ohne
 * Konstruktor angelegt und die Felder per Reflexion gesetzt (nur in Tests).
 */
final class TestReplayData {

    static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-00000000beef");
    static final SignatureData SIGNATURE = new SignatureData("textures", "skin-value", "skin-signature");

    private TestReplayData() {
    }

    static ReplayData create() {
        try {
            Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) theUnsafe.get(null);
            ReplayData data = (ReplayData) unsafe.allocateInstance(ReplayData.class);
            set(data, "actions", new HashMap<Integer, List<ActionData>>());
            set(data, "watchers", new HashMap<>());
            return data;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("ReplayData konnte für den Test nicht erzeugt werden", e);
        }
    }

    private static void set(Object target, String field, Object value) throws ReflectiveOperationException {
        Field f = ReplayData.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** Fluent-Builder für Aktionstabellen. */
    static final class Actions {
        private final Map<Integer, List<ActionData>> map = new HashMap<>();

        Actions add(int tick, ActionType type, String name, PacketData data) {
            map.computeIfAbsent(tick, t -> new ArrayList<>()).add(new ActionData(tick, type, name, data));
            return this;
        }

        Actions packet(int tick, PacketData data) {
            return add(tick, ActionType.PACKET, "Steve", data);
        }

        Actions spawn(int tick, double x, double y, double z, String world) {
            return add(tick, ActionType.SPAWN, "Steve", new SpawnData(STEVE, new LocationData(x, y, z, world), SIGNATURE));
        }

        Map<Integer, List<ActionData>> map() {
            return map;
        }

        ReplayData toData(int duration, String creator) {
            ReplayData data = create();
            data.getActions().putAll(map);
            data.setDuration(duration);
            data.setCreator(creator);
            return data;
        }
    }

    static Actions actions() {
        return new Actions();
    }

    static ItemData item(String material) {
        return new ItemData(new SerializableItemStack(new HashMap<>(Map.of("type", material, "v", 4189))));
    }

    static List<ActionType> types(List<ActionData> actions) {
        return actions.stream().map(ActionData::getType).toList();
    }

    static Set<Integer> keys(ReplayData data) {
        return data.getActions().keySet();
    }
}
