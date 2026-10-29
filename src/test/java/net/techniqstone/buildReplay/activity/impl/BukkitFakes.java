package net.techniqstone.buildReplay.activity.impl;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * Bukkit-Objekte als {@link Proxy}: beantwortet nur die Methoden, die ein Test hinterlegt, sonst Standardwerte
 * ({@code null}, {@code false}, 0). {@code equals}/{@code hashCode}/{@code toString} arbeiten mit Identität.
 */
final class BukkitFakes {

    private BukkitFakes() {
    }

    /** Hinterlegte Antworten je Methodenname. */
    static final class Answers {
        final Map<String, Function<Object[], Object>> byName = new HashMap<>();

        Answers on(String method, Object value) {
            byName.put(method, args -> value);
            return this;
        }

        Answers on(String method, Function<Object[], Object> answer) {
            byName.put(method, answer);
            return this;
        }
    }

    static <T> T fake(Class<T> type, Answers answers) {
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                String name = method.getName();
                Object[] safeArgs = args == null ? new Object[0] : args;
                switch (name) {
                    case "equals":
                        if (safeArgs.length == 1) {
                            return proxy == safeArgs[0];
                        }
                        break;
                    case "hashCode":
                        if (safeArgs.length == 0) {
                            return System.identityHashCode(proxy);
                        }
                        break;
                    case "toString":
                        if (safeArgs.length == 0) {
                            return "Fake" + type.getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(proxy));
                        }
                        break;
                    default:
                        break;
                }
                Function<Object[], Object> answer = answers.byName.get(name);
                if (answer != null) {
                    return answer.apply(safeArgs);
                }
                return defaultValue(method.getReturnType());
            }
        };
        return type.cast(Proxy.newProxyInstance(BukkitFakes.class.getClassLoader(), new Class<?>[] {type}, handler));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == void.class) {
            return null;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == double.class) {
            return 0.0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        return (byte) 0;
    }

    /** Veränderlicher Spieler-Zustand hinter einem Spieler-Proxy. */
    static final class PlayerState {
        final UUID id = UUID.randomUUID();
        boolean online = true;
        boolean npc;
        boolean sneaking;
    }

    static Player player(PlayerState state) {
        return fake(Player.class, new Answers()
                .on("getUniqueId", args -> state.id)
                .on("getName", "Tester")
                .on("isOnline", args -> state.online)
                .on("isSneaking", args -> state.sneaking)
                .on("hasMetadata", args -> state.npc && ActivityReporter.NPC_METADATA.equals(args[0])));
    }

    static World world(String name) {
        UUID id = UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return fake(World.class, new Answers().on("getName", name).on("getUID", id));
    }

    static Block block(Material type) {
        return fake(Block.class, new Answers().on("getType", type));
    }

    static Entity entity() {
        return fake(Entity.class, new Answers().on("getUniqueId", UUID.randomUUID()));
    }
}
