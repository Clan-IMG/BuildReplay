package net.techniqstone.buildReplay.command;

import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.techniqstone.buildReplay.config.Messages;
import org.bukkit.command.CommandSender;

/**
 * Ein Text aus der {@code messages.yml}: Schlüssel plus deutscher Fallback (identisch mit dem Wert in der
 * mitgelieferten Datei, siehe Test). Platzhalter werden paarweise übergeben ({@code "name", "Steve"}).
 */
public record Text(String key, String fallback) {

    public Text {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(fallback, "fallback");
    }

    /** Text ohne Präfix als Komponente. */
    public Component render(Messages messages, String... placeholders) {
        return messages.component(key, fallback, placeholders);
    }

    /** Rohtext mit {@code &}-Farbcodes, z. B. um ihn als Platzhalter in einen anderen Text einzusetzen. */
    public String raw(Messages messages, String... placeholders) {
        return messages.raw(key, fallback, placeholders);
    }

    /** Mit Präfix an den Empfänger senden. */
    public void send(Messages messages, CommandSender to, String... placeholders) {
        messages.send(to, key, fallback, placeholders);
    }
}
