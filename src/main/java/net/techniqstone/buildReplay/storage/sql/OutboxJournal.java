package net.techniqstone.buildReplay.storage.sql;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Auf Platte gesicherte Outbox ({@code <dataFolder>/outbox/outbox.jsonl}, eine Zeile je Vorgang). Die Datei enthält
 * immer einen vollständigen Schnappschuss der Warteschlange und wird über eine temporäre Datei atomar ersetzt.
 *
 * <p>Schnappschüsse tragen die Version der Warteschlange, aus der sie stammen: ein älterer Stand überschreibt nie
 * einen neueren (Schreib-Thread und {@code close()} können sich überholen). Nach {@link #freeze()} (endgültiger Stand
 * beim Beenden) wird die Datei nicht mehr verändert.
 */
final class OutboxJournal {

    /** Ergebnis des Ladens: gültige Vorgänge und die Zahl verworfener Zeilen. */
    record Loaded(List<WriteOp> ops, int skippedLines) {
    }

    private final Path file;
    private final Path tempFile;
    private final WriteOpCodec codec;
    private final Logger logger;
    private long writtenVersion = Long.MIN_VALUE;
    private int persistedEntries;
    private boolean frozen;

    OutboxJournal(Path directory, WriteOpCodec codec, Logger logger) {
        this.file = directory.resolve("outbox.jsonl");
        this.tempFile = directory.resolve("outbox.jsonl.tmp");
        this.codec = codec;
        this.logger = logger;
    }

    Path file() {
        return file;
    }

    synchronized int persistedEntries() {
        return persistedEntries;
    }

    synchronized boolean exists() {
        return persistedEntries > 0 || Files.exists(file);
    }

    /** Liest die Datei; fehlt sie, ist das Ergebnis leer. Unlesbare Zeilen werden übersprungen. */
    synchronized Loaded load() throws IOException {
        if (!Files.exists(file)) {
            return new Loaded(List.of(), 0);
        }
        List<WriteOp> ops = new ArrayList<>();
        int skipped = 0;
        int lineNumber = 0;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                try {
                    ops.add(codec.decode(line));
                } catch (RuntimeException e) { // JsonParseException, aber auch Gson-Zugriffsfehler bei falscher Struktur
                    skipped++;
                    if (skipped <= 5) {
                        logger.warning("Outbox-Zeile " + lineNumber + " in " + file.getFileName()
                                + " ist unlesbar und wird verworfen: " + e.getMessage());
                    }
                }
            }
        }
        persistedEntries = ops.size();
        return new Loaded(List.copyOf(ops), skipped);
    }

    /**
     * Schreibt den Schnappschuss, sofern er neuer ist als der zuletzt geschriebene/gelöschte Stand.
     *
     * @return {@code true}, wenn die Datei jetzt diesem (oder einem neueren) Stand entspricht
     */
    synchronized boolean write(List<WriteOp> ops, long version) throws IOException {
        if (version <= writtenVersion) {
            return true; // ein mindestens ebenso neuer Stand liegt bereits auf Platte
        }
        if (frozen) {
            return false;
        }
        if (ops.isEmpty()) {
            deleteFile();
        } else {
            Files.createDirectories(file.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(tempFile, StandardCharsets.UTF_8)) {
                for (WriteOp op : ops) {
                    writer.write(codec.encode(op));
                    writer.newLine();
                }
            }
            try {
                Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        writtenVersion = version;
        persistedEntries = ops.size();
        return true;
    }

    /** Entfernt die Datei, wenn die Warteschlange beim Stand {@code version} leer war. */
    synchronized void delete(long version) throws IOException {
        write(List.of(), version);
    }

    /** Nach dem endgültigen Schnappschuss beim Beenden: keine weiteren Änderungen. */
    synchronized void freeze() {
        frozen = true;
    }

    private void deleteFile() throws IOException {
        try {
            Files.deleteIfExists(tempFile);
            Files.delete(file);
        } catch (NoSuchFileException ignored) {
            // nichts zu löschen
        }
    }
}
