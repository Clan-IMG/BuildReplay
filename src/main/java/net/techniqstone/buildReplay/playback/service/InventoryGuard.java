package net.techniqstone.buildReplay.playback.service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.techniqstone.buildReplay.core.SerialExecutor;
import net.techniqstone.buildReplay.core.TaskRunner;
import org.bukkit.GameMode;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jspecify.annotations.Nullable;

/**
 * {@link InventoryProtection} mit Ablage unter {@code inventory-backups/<uuid>.yml}, damit eine Sicherung auch einen
 * Absturz übersteht. Die Items werden mit Papers {@link ItemStack#serializeItemsAsBytes(ItemStack[])} (NBT, beim Laden
 * per DataFixer migriert) Base64-kodiert in einer {@link YamlConfiguration} gespeichert. Serialisiert wird auf dem
 * Hauptthread, geschrieben/gelöscht nacheinander auf dem I/O-Pool.
 */
final class InventoryGuard implements InventoryProtection {

    static final int FORMAT_VERSION = 1;

    /** Kodierung des Inventarinhalts (austauschbar für Tests ohne Server). */
    interface ItemCodec {

        String encode(@Nullable ItemStack[] items);

        @Nullable ItemStack[] decode(String data);

        ItemCodec PAPER = new ItemCodec() {
            @Override
            public String encode(@Nullable ItemStack[] items) {
                return Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(items));
            }

            @Override
            public @Nullable ItemStack[] decode(String data) {
                return ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(data));
            }
        };
    }

    /** Gesicherter Zustand eines Zuschauers. */
    record Snapshot(@Nullable ItemStack[] contents, int level, float exp, boolean allowFlight, boolean flying,
                    Instant createdAt) {
    }

    private final InventoryBackupFiles files;
    private final TaskRunner tasks;
    private final Clock clock;
    private final Logger logger;
    private final ItemCodec codec;
    private final Executor fileOps;
    private final Map<UUID, Snapshot> backups = new ConcurrentHashMap<>();

    InventoryGuard(Path directory, TaskRunner tasks, Clock clock, Logger logger) {
        this(directory, tasks, clock, logger, ItemCodec.PAPER);
    }

    InventoryGuard(Path directory, TaskRunner tasks, Clock clock, Logger logger, ItemCodec codec) {
        this.files = new InventoryBackupFiles(directory);
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.fileOps = new SerialExecutor(tasks.io(), logger, true);
    }

    InventoryBackupFiles files() {
        return files;
    }

    @Override
    public boolean backup(Player viewer) {
        UUID id = viewer.getUniqueId();
        if (backups.containsKey(id)) {
            return true;
        }
        Snapshot snapshot = capture(viewer);
        String text = encode(id, viewer.getName(), snapshot);
        backups.put(id, snapshot);
        fileOps.execute(() -> {
            try {
                files.write(id, text);
            } catch (IOException e) {
                logger.log(Level.WARNING, "Inventar-Sicherung von " + viewer.getName()
                        + " konnte nicht geschrieben werden - sie gilt nur bis zum nächsten Neustart", e);
            }
        });
        return true;
    }

    @Override
    public boolean hasBackup(UUID viewer) {
        return backups.containsKey(viewer);
    }

    @Override
    public boolean restore(Player viewer) {
        UUID id = viewer.getUniqueId();
        Snapshot snapshot = backups.remove(id);
        if (snapshot == null) {
            return false;
        }
        try {
            apply(viewer, snapshot);
        } catch (RuntimeException e) {
            // Sicherung behalten (Speicher und Datei) - beim nächsten Join erneut versuchen
            backups.put(id, snapshot);
            logger.log(Level.SEVERE, "Inventar von " + viewer.getName() + " konnte nicht wiederhergestellt werden", e);
            return false;
        }
        deleteLater(id);
        return true;
    }

    @Override
    public void discard(UUID viewer) {
        if (backups.remove(viewer) != null) {
            deleteLater(viewer);
        }
    }

    @Override
    public CompletableFuture<Boolean> restoreOrphan(Player viewer, BooleanSupplier allowed) {
        UUID id = viewer.getUniqueId();
        if (backups.containsKey(id)) {
            // im Speicher liegengeblieben (z. B. Wiederherstellung beim Quit scheiterte)
            return CompletableFuture.completedFuture(allowed.getAsBoolean() && restore(viewer));
        }
        CompletableFuture<Optional<String>> read = new CompletableFuture<>();
        fileOps.execute(() -> {
            try {
                read.complete(files.read(id));
            } catch (IOException | RuntimeException e) {
                read.completeExceptionally(e);
            }
        });
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        read.whenComplete((content, error) -> tasks.runOnMain(() -> {
            if (error != null) {
                result.completeExceptionally(error);
                return;
            }
            try {
                result.complete(applyOrphan(viewer, content, allowed));
            } catch (RuntimeException e) {
                result.completeExceptionally(e);
            }
        }));
        return result;
    }

    @Override
    public void flush(long timeoutMillis) {
        CompletableFuture<Void> marker = new CompletableFuture<>();
        fileOps.execute(() -> marker.complete(null));
        try {
            marker.get(Math.max(0L, timeoutMillis), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            logger.warning("Inventar-Sicherungen konnten nicht rechtzeitig geschrieben/gelöscht werden");
        }
    }

    private boolean applyOrphan(Player viewer, Optional<String> content, BooleanSupplier allowed) {
        if (content.isEmpty()) {
            return false;
        }
        UUID id = viewer.getUniqueId();
        if (!viewer.isOnline() || backups.containsKey(id) || !allowed.getAsBoolean()) {
            return false;
        }
        Snapshot snapshot;
        try {
            snapshot = decode(content.get());
        } catch (InvalidConfigurationException | RuntimeException e) {
            logger.log(Level.WARNING, "Inventar-Sicherung von " + viewer.getName() + " ist unlesbar und wird "
                    + "beiseitegelegt", e);
            long now = clock.millis();
            fileOps.execute(() -> {
                try {
                    files.quarantine(id, now);
                } catch (IOException inner) {
                    logger.log(Level.WARNING, "Unlesbare Inventar-Sicherung konnte nicht verschoben werden", inner);
                }
            });
            return false;
        }
        apply(viewer, snapshot);
        deleteLater(id);
        logger.info("Inventar von " + viewer.getName() + " aus einer unterbrochenen Wiedergabe ("
                + snapshot.createdAt() + ") wiederhergestellt");
        return true;
    }

    private Snapshot capture(Player viewer) {
        ItemStack[] contents = viewer.getInventory().getContents();
        @Nullable ItemStack[] copy = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            copy[i] = contents[i] == null ? null : contents[i].clone();
        }
        return new Snapshot(copy, viewer.getLevel(), viewer.getExp(), viewer.getAllowFlight(), viewer.isFlying(),
                clock.instant());
    }

    private void apply(Player viewer, Snapshot snapshot) {
        PlayerInventory inventory = viewer.getInventory();
        @Nullable ItemStack[] contents = snapshot.contents();
        int size = inventory.getSize();
        if (size > 0 && contents.length > size) {
            logger.warning("Inventar-Sicherung von " + viewer.getName() + " hat " + contents.length + " Plätze, das "
                    + "Inventar nur " + size + " - überzählige Plätze entfallen");
            contents = Arrays.copyOf(contents, size);
        }
        inventory.clear();
        inventory.setContents(contents);
        viewer.setLevel(Math.max(0, snapshot.level()));
        viewer.setExp(Math.max(0f, Math.min(1f, snapshot.exp())));
        GameMode mode = viewer.getGameMode();
        if (mode != GameMode.CREATIVE && mode != GameMode.SPECTATOR) {
            viewer.setAllowFlight(snapshot.allowFlight());
            viewer.setFlying(snapshot.allowFlight() && snapshot.flying());
        }
    }

    private void deleteLater(UUID id) {
        fileOps.execute(() -> {
            try {
                files.delete(id);
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Inventar-Sicherung " + files.fileOf(id) + " konnte nicht gelöscht werden - "
                        + "bitte manuell entfernen, sonst wird sie beim nächsten Join erneut angewendet", e);
            }
        });
    }

    String encode(UUID id, String name, Snapshot snapshot) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("version", FORMAT_VERSION);
        yaml.set("player", id.toString());
        yaml.set("name", name);
        yaml.set("created-at", snapshot.createdAt().toString());
        yaml.set("level", snapshot.level());
        yaml.set("exp", (double) snapshot.exp());
        yaml.set("allow-flight", snapshot.allowFlight());
        yaml.set("flying", snapshot.flying());
        yaml.set("slots", snapshot.contents().length);
        yaml.set("items", codec.encode(snapshot.contents()));
        return yaml.saveToString();
    }

    Snapshot decode(String text) throws InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        int version = yaml.getInt("version", -1);
        if (version != FORMAT_VERSION) {
            throw new IllegalArgumentException("Unbekannte Formatversion " + version);
        }
        String items = yaml.getString("items");
        if (items == null) {
            throw new IllegalArgumentException("Inventarinhalt fehlt");
        }
        Instant createdAt;
        try {
            createdAt = Instant.parse(yaml.getString("created-at", Instant.EPOCH.toString()));
        } catch (DateTimeParseException e) {
            createdAt = Instant.EPOCH;
        }
        return new Snapshot(codec.decode(items), yaml.getInt("level"), (float) yaml.getDouble("exp"),
                yaml.getBoolean("allow-flight"), yaml.getBoolean("flying"), createdAt);
    }
}
