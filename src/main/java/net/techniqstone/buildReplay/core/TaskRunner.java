package net.techniqstone.buildReplay.core;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

/**
 * Thread-Modell von BuildReplay. Bukkit- und AdvancedReplay-Aufrufe laufen ausschließlich auf dem Hauptthread,
 * Netzwerk, Datenbank, Redis und Serialisierung auf dem begrenzten I/O-Pool. Tests ersetzen die Implementierung
 * durch eine synchrone Variante.
 */
public interface TaskRunner {

    /** Führt die Aufgabe auf dem Minecraft-Hauptthread aus (sofort, wenn der Aufrufer bereits dort ist). */
    void runOnMain(Runnable task);

    /** Führt die Aufgabe nach {@code delayTicks} Server-Ticks auf dem Hauptthread aus. */
    void runOnMainLater(Runnable task, long delayTicks);

    boolean isMainThread();

    /** Begrenzter Pool für blockierende Arbeit (DB, Redis, HTTP, Replay-Serialisierung). Nie Bukkit-API darin. */
    ExecutorService io();

    /** Für periodische Hintergrundaufgaben; die eigentliche Arbeit wird an {@link #io()} weitergereicht. */
    ScheduledExecutorService scheduler();

    default Executor mainExecutor() {
        return this::runOnMain;
    }

    default <T> CompletableFuture<T> supplyIo(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, io());
    }

    default CompletableFuture<Void> runIo(Runnable runnable) {
        return CompletableFuture.runAsync(runnable, io());
    }
}
