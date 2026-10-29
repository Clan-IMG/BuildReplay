package net.techniqstone.buildReplay.command;

import java.util.List;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Spielername → UUID und Namen für die Tab-Vervollständigung (nur Hauptthread). */
public interface PlayerLookup {

    /** Online-Spieler oder vom Server zwischengespeicherter Spieler ({@code Bukkit.getOfflinePlayerIfCached}). */
    Optional<KnownPlayer> find(String name);

    /** Namen der Online-Spieler, die {@code viewer} sehen darf. */
    List<String> onlineNames(CommandSender viewer);

    /** Implementierung über die Bukkit-API. */
    static PlayerLookup bukkit() {
        return new PlayerLookup() {
            @Override
            public Optional<KnownPlayer> find(String name) {
                Player online = Bukkit.getPlayerExact(name);
                if (online != null) {
                    return Optional.of(new KnownPlayer(online.getUniqueId(), online.getName()));
                }
                OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
                if (cached == null) {
                    return Optional.empty();
                }
                String knownName = cached.getName();
                return Optional.of(new KnownPlayer(cached.getUniqueId(), knownName == null ? name : knownName));
            }

            @Override
            public List<String> onlineNames(CommandSender viewer) {
                return Bukkit.getOnlinePlayers().stream()
                        .filter(other -> !(viewer instanceof Player self) || self.canSee(other))
                        .map(Player::getName)
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .toList();
            }
        };
    }
}
