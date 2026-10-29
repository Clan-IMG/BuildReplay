package net.techniqstone.buildReplay.recording.service.fakes;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Handgemachter Spieler ohne Server: ein {@link Proxy} auf {@link Player}, der nur die von BuildReplay benutzten
 * Methoden beantwortet (Identität, Rechte, Welt, Spielmodus, Nachrichten, Level/XP, Flug). Alle anderen Methoden
 * liefern Standardwerte.
 */
public final class FakePlayer {

    public final UUID uuid;
    public final String name;
    public String world = "world";
    public GameMode gameMode = GameMode.SURVIVAL;
    public final Set<String> permissions = new HashSet<>();
    public boolean online = true;
    public final List<Component> messages = new ArrayList<>();
    public int level;
    public float exp;
    public boolean allowFlight;
    public boolean flying;
    /** Inhalt des Spielerinventars (41 Plätze wie in 1.21). */
    public ItemStack[] inventory = new ItemStack[41];
    public int inventoryClears;
    public int inventorySets;
    private final Player proxy;
    private final PlayerInventory inventoryProxy;

    public FakePlayer(String name) {
        this(UUID.nameUUIDFromBytes(("player:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8)), name);
    }

    public FakePlayer(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
        this.proxy = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (self, method, args) -> handle(self, method, args));
        this.inventoryProxy = (PlayerInventory) Proxy.newProxyInstance(PlayerInventory.class.getClassLoader(),
                new Class<?>[]{PlayerInventory.class}, (self, method, args) -> switch (method.getName()) {
                    case "getContents", "getStorageContents" -> inventory.clone();
                    case "setContents", "setStorageContents" -> {
                        ItemStack[] items = (ItemStack[]) args[0];
                        inventory = Arrays.copyOf(items, Math.max(items.length, 41));
                        inventorySets++;
                        yield null;
                    }
                    case "clear" -> {
                        if (args == null || args.length == 0) {
                            Arrays.fill(inventory, null);
                            inventoryClears++;
                        }
                        yield null;
                    }
                    case "getSize" -> 41;
                    case "getHolder" -> proxy;
                    case "equals" -> self == args[0];
                    case "hashCode" -> System.identityHashCode(self);
                    case "toString" -> "FakeInventory[" + name + "]";
                    default -> defaultValue(method.getReturnType());
                });
    }

    public Player player() {
        return proxy;
    }

    public FakePlayer grant(String... nodes) {
        permissions.addAll(List.of(nodes));
        return this;
    }

    public List<String> plainMessages() {
        return messages.stream().map(component -> PlainTextComponentSerializer.plainText().serialize(component)).toList();
    }

    public boolean received(String fragment) {
        return plainMessages().stream().anyMatch(text -> text.contains(fragment));
    }

    private Object handle(Object self, Method method, Object[] args) {
        String name = method.getName();
        int count = args == null ? 0 : args.length;
        switch (name) {
            case "getUniqueId":
                return uuid;
            case "getName":
                return this.name;
            case "isOnline":
                return online;
            case "hasPermission":
            case "isPermissionSet":
                return count == 1 && args[0] instanceof String node && permissions.contains(node);
            case "getWorld":
                return world == null ? null : FakePlayer.world(world);
            case "getGameMode":
                return gameMode;
            case "getInventory":
                return inventoryProxy;
            case "sendMessage":
                if (count == 1 && args[0] instanceof Component component) {
                    messages.add(component);
                } else if (count == 1 && args[0] instanceof ComponentLike like) {
                    messages.add(like.asComponent());
                } else if (count == 1 && args[0] instanceof String text) {
                    messages.add(Component.text(text));
                } else if (count == 2 && args[1] instanceof Component component) {
                    messages.add(component);
                }
                return null;
            case "getLevel":
                return level;
            case "setLevel":
                level = (Integer) args[0];
                return null;
            case "getExp":
                return exp;
            case "setExp":
                exp = (Float) args[0];
                return null;
            case "getAllowFlight":
                return allowFlight;
            case "setAllowFlight":
                allowFlight = (Boolean) args[0];
                return null;
            case "isFlying":
                return flying;
            case "setFlying":
                flying = (Boolean) args[0];
                return null;
            case "equals":
                return self == args[0];
            case "hashCode":
                return System.identityHashCode(self);
            case "toString":
                return "FakePlayer[" + this.name + "]";
            default:
                return defaultValue(method.getReturnType());
        }
    }

    /** Welt-Proxy, der nur seinen Namen kennt. */
    public static World world(String name) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                (self, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "equals" -> self == args[0];
                    case "hashCode" -> System.identityHashCode(self);
                    case "toString" -> "FakeWorld[" + name + "]";
                    default -> defaultValue(method.getReturnType());
                });
    }

    static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        return 0;
    }
}
