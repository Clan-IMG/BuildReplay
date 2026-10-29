package net.techniqstone.buildReplay.playback.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import org.bukkit.entity.Player;

/** Inventar-Sicherung, die nur protokolliert (ItemStacks brauchen einen Server). */
final class FakeInventoryProtection implements InventoryProtection {

    final Set<UUID> backups = new HashSet<>();
    final Set<UUID> orphans = new HashSet<>();
    final List<String> calls = new ArrayList<>();

    long count(String call) {
        return calls.stream().filter(call::equals).count();
    }

    @Override
    public boolean backup(Player viewer) {
        calls.add("backup");
        backups.add(viewer.getUniqueId());
        return true;
    }

    @Override
    public boolean hasBackup(UUID viewer) {
        return backups.contains(viewer);
    }

    @Override
    public boolean restore(Player viewer) {
        boolean had = backups.remove(viewer.getUniqueId());
        calls.add(had ? "restore" : "restore-none");
        return had;
    }

    @Override
    public void discard(UUID viewer) {
        if (backups.remove(viewer)) {
            calls.add("discard");
        }
    }

    @Override
    public CompletableFuture<Boolean> restoreOrphan(Player viewer, BooleanSupplier allowed) {
        if (orphans.contains(viewer.getUniqueId()) && allowed.getAsBoolean()) {
            orphans.remove(viewer.getUniqueId());
            calls.add("restoreOrphan");
            return CompletableFuture.completedFuture(true);
        }
        return CompletableFuture.completedFuture(false);
    }

    @Override
    public void flush(long timeoutMillis) {
        calls.add("flush");
    }
}
