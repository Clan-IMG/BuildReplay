package net.techniqstone.buildReplay.recording.advancedreplay;

import java.util.Objects;
import me.jumper251.replay.replaysystem.data.types.LocationData;
import org.bukkit.block.Block;

/** Blockposition inklusive Weltname (unabhängig von geladenen Welten, daher auch in Tests verwendbar). */
record BlockKey(String world, int x, int y, int z) {

    BlockKey {
        Objects.requireNonNull(world, "world");
    }

    static BlockKey of(Block block) {
        return new BlockKey(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    /** AdvancedReplay speichert Blockpositionen als {@code double} (Block-Location, also ganzzahlig). */
    static BlockKey of(LocationData location) {
        return new BlockKey(location.getWorld() == null ? "" : location.getWorld(),
                (int) Math.floor(location.getX()), (int) Math.floor(location.getY()), (int) Math.floor(location.getZ()));
    }

    int chunkX() {
        return x >> 4;
    }

    int chunkZ() {
        return z >> 4;
    }

    boolean inChunk(String worldName, int chunkX, int chunkZ) {
        return world.equals(worldName) && chunkX() == chunkX && chunkZ() == chunkZ;
    }
}
