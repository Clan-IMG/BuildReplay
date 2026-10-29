package net.techniqstone.buildReplay.simplecloud.rest;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * Nachgebauter SimpleCloud-Controller auf {@code 127.0.0.1} (zufälliger Port) mit vorgegebenen Antworten pro Pfad.
 * Die Antworten eines Pfads werden der Reihe nach verwendet, die letzte wiederholt sich. Alle Anfragen werden
 * mitgeschnitten.
 */
final class FakeController implements AutoCloseable {

    /** Eine vorgegebene Antwort. {@code hold}: Antwort erst nach Freigabe (oder nie) senden. */
    record Reply(int status, String body, String contentType, Map<String, String> headers,
                 @Nullable CountDownLatch hold) {

        static Reply json(int status, String body) {
            return new Reply(status, body, "application/json", Map.of(), null);
        }

        static Reply ok(String body) {
            return json(200, body);
        }

        static Reply error(int status, String message) {
            return json(status, "{\"error\":\"" + message + "\"}");
        }

        static Reply text(int status, String body) {
            return new Reply(status, body, "text/plain; charset=utf-8", Map.of(), null);
        }

        /** Antwortet erst, wenn {@code latch} freigegeben wird (für Zeitüberschreitungen). */
        static Reply hang(CountDownLatch latch) {
            return new Reply(200, "{}", "application/json", Map.of(), latch);
        }

        Reply withHeader(String name, String value) {
            Map<String, String> copy = new LinkedHashMap<>(headers);
            copy.put(name, value);
            return new Reply(status, body, contentType, Map.copyOf(copy), hold);
        }

        Reply heldBy(CountDownLatch latch) {
            return new Reply(status, body, contentType, headers, latch);
        }
    }

    /** Eine mitgeschnittene Anfrage. */
    record Recorded(String method, String path, @Nullable String query, Map<String, List<String>> headers,
                    long receivedNanos) {

        @Nullable String header(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                    return entry.getValue().getFirst();
                }
            }
            return null;
        }

        boolean hasHeader(String name) {
            return headers.keySet().stream().anyMatch(key -> key.equalsIgnoreCase(name));
        }
    }

    private final HttpServer server;
    private final ExecutorService executor;
    private final Map<String, List<Reply>> scripts = new ConcurrentHashMap<>();
    private final Map<String, Integer> served = new ConcurrentHashMap<>();
    private final List<Recorded> recorded = new CopyOnWriteArrayList<>();
    private final List<CountDownLatch> latches = new CopyOnWriteArrayList<>();

    private FakeController(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    static FakeController start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "FakeController");
            thread.setDaemon(true);
            return thread;
        });
        FakeController controller = new FakeController(server, executor);
        server.createContext("/", controller::handle);
        server.setExecutor(executor);
        server.start();
        return controller;
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Legt die Antworten für einen Pfad fest (ersetzt vorherige). */
    FakeController on(String path, Reply... replies) {
        for (Reply reply : replies) {
            if (reply.hold() != null) {
                latches.add(reply.hold());
            }
        }
        scripts.put(path, List.of(replies));
        served.put(path, 0);
        return this;
    }

    List<Recorded> requests() {
        return List.copyOf(recorded);
    }

    List<Recorded> requests(String path) {
        return recorded.stream().filter(r -> r.path().equals(path)).toList();
    }

    int count(String path) {
        return requests(path).size();
    }

    int total() {
        return recorded.size();
    }

    private void handle(HttpExchange exchange) throws IOException {
        long now = System.nanoTime();
        String path = exchange.getRequestURI().getPath();
        recorded.add(new Recorded(exchange.getRequestMethod(), path, exchange.getRequestURI().getRawQuery(),
                copy(exchange.getRequestHeaders()), now));
        Reply reply = next(path);
        try (exchange) {
            if (reply.hold() != null) {
                try {
                    if (!reply.hold().await(30, TimeUnit.SECONDS)) {
                        return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", reply.contentType());
            exchange.getResponseHeaders().set("x-request-id", "req-" + recorded.size());
            reply.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
            exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
        } catch (IOException e) {
            // Client hat die Verbindung bereits beendet (z. B. nach Zeitüberschreitung)
        }
    }

    private synchronized Reply next(String path) {
        List<Reply> script = scripts.get(path);
        if (script == null || script.isEmpty()) {
            return Reply.text(404, "404 page not found");
        }
        int index = served.merge(path, 1, Integer::sum) - 1;
        return script.get(Math.min(index, script.size() - 1));
    }

    private static Map<String, List<String>> copy(Headers headers) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        headers.forEach((name, values) -> result.put(name.toLowerCase(Locale.ROOT), new ArrayList<>(values)));
        return Map.copyOf(result);
    }

    /** Hilfsfunktion für Tests: wartet, bis {@code expected} Anfragen auf {@code path} eingegangen sind. */
    boolean awaitRequests(String path, int expected, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (count(path) >= expected) {
                return true;
            }
            Thread.sleep(10);
        }
        return count(path) >= expected;
    }

    @Override
    public void close() {
        latches.forEach(latch -> {
            while (latch.getCount() > 0) {
                latch.countDown();
            }
        });
        server.stop(0);
        executor.shutdownNow();
    }
}
