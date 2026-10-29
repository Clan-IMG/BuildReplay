package net.techniqstone.buildReplay.command;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

/**
 * Handgebauter Spieler bzw. Konsole über {@link Proxy}: Name, UUID, Berechtigungen, Online-Status und empfangene
 * Nachrichten. Alle anderen Methoden liefern Standardwerte.
 */
final class FakeSender {

    final String name;
    final UUID uuid;
    final Set<String> permissions = new HashSet<>();
    final List<Component> received = new ArrayList<>();
    final boolean console;
    volatile boolean online = true;
    final CommandSender sender;

    private FakeSender(String name, UUID uuid, boolean console, String... permissions) {
        this.name = name;
        this.uuid = uuid;
        this.console = console;
        this.permissions.addAll(List.of(permissions));
        Class<?> type = console ? ConsoleCommandSender.class : Player.class;
        this.sender = (CommandSender) Proxy.newProxyInstance(FakeSender.class.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> this.name;
                    case "getUniqueId" -> this.uuid;
                    case "hasPermission" -> args != null && args[0] instanceof String permission
                            && (this.console || this.permissions.contains(permission));
                    case "isOnline" -> online;
                    case "sendMessage" -> {
                        if (args != null && args.length == 1 && args[0] instanceof ComponentLike component) {
                            received.add(component.asComponent());
                        } else if (args != null && args.length == 1 && args[0] instanceof String text) {
                            received.add(Component.text(text));
                        }
                        yield null;
                    }
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "FakeSender[" + this.name + "]";
                    default -> defaultValue(method.getReturnType());
                });
    }

    static FakeSender player(String name, UUID uuid, String... permissions) {
        return new FakeSender(name, uuid, false, permissions);
    }

    static FakeSender console() {
        return new FakeSender("CONSOLE", new UUID(0, 0), true);
    }

    Player player() {
        return (Player) sender;
    }

    List<String> lines() {
        return received.stream().map(c -> PlainTextComponentSerializer.plainText().serialize(c)).toList();
    }

    String text() {
        return String.join("\n", lines());
    }

    void clear() {
        received.clear();
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
