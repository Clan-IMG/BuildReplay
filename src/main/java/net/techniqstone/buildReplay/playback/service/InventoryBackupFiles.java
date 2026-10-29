package net.techniqstone.buildReplay.playback.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Ablage der Inventar-Sicherungen unter {@code plugins/BuildReplay/inventory-backups/<uuid>.yml}. Reine Datei-I/O,
 * blockierend - nur auf dem I/O-Pool aufrufen. Geschrieben wird atomar (temporäre Datei, dann verschieben), damit
 * ein Absturz nie eine halbe Sicherung hinterlässt.
 */
final class InventoryBackupFiles {

    private static final String SUFFIX = ".yml";

    private final Path directory;

    InventoryBackupFiles(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    Path directory() {
        return directory;
    }

    Path fileOf(UUID player) {
        return directory.resolve(player + SUFFIX);
    }

    void write(UUID player, String content) throws IOException {
        Files.createDirectories(directory);
        Path target = fileOf(player);
        Path temp = Files.createTempFile(directory, player + ".", ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    Optional<String> read(UUID player) throws IOException {
        try {
            return Optional.of(Files.readString(fileOf(player), StandardCharsets.UTF_8));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
    }

    boolean exists(UUID player) {
        return Files.isRegularFile(fileOf(player));
    }

    boolean delete(UUID player) throws IOException {
        return Files.deleteIfExists(fileOf(player));
    }

    /** Unlesbare Sicherung beiseitelegen ({@code <uuid>.yml.broken-<millis>}), damit sie nicht bei jedem Join stört. */
    Optional<Path> quarantine(UUID player, long nowMillis) throws IOException {
        Path source = fileOf(player);
        if (!Files.isRegularFile(source)) {
            return Optional.empty();
        }
        Path target = directory.resolve(player + SUFFIX + ".broken-" + nowMillis);
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        return Optional.of(target);
    }

    /** Spieler mit einer Sicherung auf Platte. */
    List<UUID> list() throws IOException {
        List<UUID> result = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return result;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*" + SUFFIX)) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                try {
                    result.add(UUID.fromString(name.substring(0, name.length() - SUFFIX.length())));
                } catch (IllegalArgumentException e) {
                    // fremde Datei - ignorieren
                }
            }
        }
        return result;
    }
}
