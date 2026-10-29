package net.techniqstone.buildReplay.simplecloud.rest;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.techniqstone.buildReplay.config.PluginConfig;
import net.techniqstone.buildReplay.simplecloud.ClientHealth;
import net.techniqstone.buildReplay.simplecloud.CloudOnlinePlayer;
import net.techniqstone.buildReplay.simplecloud.CloudSession;
import net.techniqstone.buildReplay.simplecloud.CloudSessionPage;
import net.techniqstone.buildReplay.simplecloud.SimpleCloudClient;
import net.techniqstone.buildReplay.simplecloud.SimpleCloudException;
import net.techniqstone.buildReplay.simplecloud.SimpleCloudException.Reason;
import org.jspecify.annotations.Nullable;

/**
 * {@link SimpleCloudClient} über {@link HttpClient} gegen {@code https://controller.simplecloud.app}.
 *
 * <p>Jede Anfrage sendet {@code X-Network-ID}, {@code X-Network-Credential} (Netzwerk-Secret - nie als
 * {@code Authorization}, nie im Log), {@code Accept: application/json} und {@code User-Agent: BuildReplay/<version>}.
 * Weiterleitungen werden nicht verfolgt, damit das Secret nie an einen anderen Host geht.
 *
 * <p>Fehlerbehandlung: wiederholbare Fehler ({@link SimpleCloudException#retryable()}) werden bis zu
 * {@code maxRetries}-mal mit exponentiellem Backoff (Full Jitter) erneut versucht, {@code Retry-After} bei 429/503
 * wird beachtet (höchstens {@code retryMaxDelay}). Wartezeiten laufen über einen eigenen Scheduler, der Aufrufer
 * wird nie blockiert. Nach {@code circuitFailureThreshold} aufeinanderfolgenden fehlgeschlagenen Aufrufen öffnet
 * der Circuit-Breaker für {@code circuitOpenDuration} ({@link Reason#CIRCUIT_OPEN} ohne Anfrage), danach darf genau
 * ein Probeaufruf (ohne Wiederholungen) durch.
 *
 * <p>Threads: Die Futures werden auf internen Threads abgeschlossen (HTTP-Pool bzw. Retry-Scheduler). Aufrufer
 * dürfen dort nicht blockieren und müssen für Bukkit-Aufrufe selbst auf den Hauptthread wechseln.
 *
 * <p>Lebenszyklus: Konstruktor (startet noch keine Anfrage), beliebig viele Aufrufe, {@link #close()} beim
 * Deaktivieren (offene Futures schlagen mit {@link Reason#UNAVAILABLE} fehl, Threads werden beendet).
 */
public final class RestSimpleCloudClient implements SimpleCloudClient {

    /** Serverseitige Obergrenze für {@code limit} bei {@code /v0/players/sessions}. */
    static final int MAX_SESSION_LIMIT = 100;

    static final String SESSIONS_PATH = "/v0/players/sessions";
    static final String CURRENT_SESSION_PATH = "/v0/players/sessions/current";
    static final String ONLINE_PATH = "/v0/players/online";

    private static final String HEADER_NETWORK_ID = "X-Network-ID";
    private static final String HEADER_CREDENTIAL = "X-Network-Credential";
    private static final String HEADER_REQUEST_ID = "x-request-id";
    private static final int MAX_ERROR_TEXT = 200;
    private static final int HTTP_THREADS = 2;

    private final Clock clock;
    private final Logger logger;
    private final SimpleCloudJson json;
    private final boolean configured;
    private final String baseUrl;
    private final String networkId;
    private final String networkSecret;
    private final String userAgent;
    private final Duration requestTimeout;
    private final int maxRetries;
    private final RetryBackoff backoff;
    private final CircuitBreaker circuit;
    private final ThreadPoolExecutor httpExecutor;
    private final ScheduledThreadPoolExecutor scheduler;
    private final @Nullable HttpClient http;

    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean unauthorizedLogged = new AtomicBoolean();
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private volatile @Nullable Instant lastSuccess;
    private volatile @Nullable Instant lastFailure;
    private volatile @Nullable String lastError;

    /**
     * @param settings      SimpleCloud-Einstellungen (base-url, network-id, network-secret, Timeouts, Retries, Breaker)
     * @param pluginVersion Version für den {@code User-Agent}
     * @param clock         Zeitquelle für Circuit-Breaker und {@link #health()}
     * @param logger        Plugin-Logger
     */
    public RestSimpleCloudClient(PluginConfig.SimpleCloudSettings settings, String pluginVersion, Clock clock,
                                 Logger logger) {
        Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.json = new SimpleCloudJson(logger);
        this.baseUrl = trimTrailingSlashes(settings.baseUrl());
        this.networkId = settings.networkId() == null ? "" : settings.networkId().trim();
        this.networkSecret = settings.networkSecret() == null ? "" : settings.networkSecret().trim();
        this.userAgent = "BuildReplay/" + sanitizeToken(pluginVersion);
        this.requestTimeout = positiveOr(settings.requestTimeout(), Duration.ofSeconds(10));
        this.maxRetries = Math.max(0, settings.maxRetries());
        this.backoff = new RetryBackoff(nonNegative(settings.retryBaseDelay()), nonNegative(settings.retryMaxDelay()),
                () -> ThreadLocalRandom.current().nextDouble());
        this.circuit = new CircuitBreaker(settings.circuitFailureThreshold(),
                nonNegative(settings.circuitOpenDuration()), clock);

        this.configured = settings.configured() && validateConfiguration();

        this.httpExecutor = new ThreadPoolExecutor(HTTP_THREADS, HTTP_THREADS, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), daemonThreads("BuildReplay-SimpleCloud-HTTP"));
        this.httpExecutor.allowCoreThreadTimeOut(true);
        this.scheduler = new ScheduledThreadPoolExecutor(1, daemonThreads("BuildReplay-SimpleCloud-Retry"));
        this.scheduler.setRemoveOnCancelPolicy(true);
        this.scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);

        if (configured) {
            this.http = HttpClient.newBuilder()
                    .connectTimeout(positiveOr(settings.connectTimeout(), Duration.ofSeconds(5)))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .executor(httpExecutor)
                    .build();
        } else {
            this.http = null;
            logger.fine("SimpleCloud-REST nicht konfiguriert (base-url/network-id/network-secret) - "
                    + "Anfragen schlagen mit NOT_CONFIGURED fehl");
        }
    }

    @Override
    public boolean isConfigured() {
        return configured;
    }

    @Override
    public CompletableFuture<CloudSessionPage> listSessions(UUID playerId, int limit, int offset) {
        Objects.requireNonNull(playerId, "playerId");
        int clampedLimit = Math.max(1, Math.min(MAX_SESSION_LIMIT, limit));
        int clampedOffset = Math.max(0, offset);
        String query = "player_id=" + playerId + "&limit=" + clampedLimit + "&offset=" + clampedOffset;
        return execute(new Call<>(SESSIONS_PATH, query,
                body -> json.sessionPage(body, playerId, clampedOffset), null));
    }

    @Override
    public CompletableFuture<Optional<CloudSession>> currentSession(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return execute(new Call<>(CURRENT_SESSION_PATH, "player_id=" + playerId,
                body -> json.currentSession(body, playerId), Optional::empty));
    }

    @Override
    public CompletableFuture<List<CloudOnlinePlayer>> onlinePlayers() {
        return execute(new Call<>(ONLINE_PATH, null, json::onlinePlayers, null));
    }

    /**
     * {@code requests}: tatsächlich gesendete HTTP-Anfragen inklusive Wiederholungen; {@code failures}: davon
     * fehlgeschlagene (ohne Ergebnis). Abgelehnte Aufrufe (nicht konfiguriert, Circuit offen) senden nichts und
     * zählen nicht. {@code lastError} enthält nie das Secret.
     */
    @Override
    public ClientHealth health() {
        return new ClientHealth(configured, circuit.isOpen(), lastSuccess, lastFailure, lastError,
                requests.get(), failures.get());
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        SimpleCloudException error = new SimpleCloudException(Reason.UNAVAILABLE, -1,
                "SimpleCloud-Client wurde geschlossen");
        for (CompletableFuture<?> future : List.copyOf(pending)) {
            future.completeExceptionally(error);
        }
        pending.clear();
        scheduler.shutdownNow();
        if (http != null) {
            http.shutdownNow();
        }
        httpExecutor.shutdownNow();
    }

    // ------------------------------------------------------------------------------------------------------------

    /** Liest den Antwortkörper eines erfolgreichen Aufrufs. */
    @FunctionalInterface
    private interface ResponseParser<T> {
        T parse(String body) throws SimpleCloudJson.MalformedResponseException;
    }

    /**
     * Ein logischer Aufruf. {@code notOnline}: Ergebnis für HTTP 404 mit JSON-Fehlerkörper
     * ({@code {"error":"Player is not online"}}); {@code null} = 404 ist ein Fehler.
     */
    private record Call<T>(String path, @Nullable String query, ResponseParser<T> parser,
                           @Nullable Supplier<T> notOnline) {

        String describe() {
            return "GET " + path;
        }
    }

    /** Ergebnis eines einzelnen Versuchs. */
    private record Outcome<T>(@Nullable T value, @Nullable SimpleCloudException error, @Nullable Duration retryAfter) {

        static <T> Outcome<T> success(T value) {
            return new Outcome<>(value, null, null);
        }

        static <T> Outcome<T> failure(SimpleCloudException error, @Nullable Duration retryAfter) {
            return new Outcome<>(null, error, retryAfter);
        }
    }

    private <T> CompletableFuture<T> execute(Call<T> call) {
        if (!configured) {
            return CompletableFuture.failedFuture(new SimpleCloudException(Reason.NOT_CONFIGURED, -1,
                    "SimpleCloud ist nicht konfiguriert (simplecloud.base-url/network-id/network-secret)"));
        }
        if (closed.get()) {
            return CompletableFuture.failedFuture(closedError());
        }
        CircuitBreaker.Permit permit = circuit.tryAcquire();
        if (permit == CircuitBreaker.Permit.REJECTED) {
            Instant until = circuit.openUntil();
            return CompletableFuture.failedFuture(new SimpleCloudException(Reason.CIRCUIT_OPEN, -1,
                    "SimpleCloud vorübergehend gesperrt (Circuit-Breaker offen"
                            + (until != null ? " bis " + until : "") + ") - " + call.describe() + " nicht gesendet"));
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        pending.add(result);
        result.whenComplete((value, error) -> pending.remove(result));
        if (closed.get()) {
            result.completeExceptionally(closedError());
            return result;
        }
        attempt(call, permit, 0, result);
        return result;
    }

    private <T> void attempt(Call<T> call, CircuitBreaker.Permit permit, int retry, CompletableFuture<T> result) {
        if (result.isDone()) {
            return;
        }
        HttpClient client = Objects.requireNonNull(http, "http");
        CompletableFuture<HttpResponse<String>> response;
        requests.incrementAndGet();
        try {
            HttpRequest request = HttpRequest.newBuilder(uri(call))
                    .GET()
                    .timeout(requestTimeout)
                    .header(HEADER_NETWORK_ID, networkId)
                    .header(HEADER_CREDENTIAL, networkSecret)
                    .header("Accept", "application/json")
                    .header("User-Agent", userAgent)
                    .build();
            response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            response = CompletableFuture.failedFuture(e);
        }
        CompletableFuture<HttpResponse<String>> exchange = response;
        ScheduledFuture<?> guard = scheduleGuard(exchange);
        exchange.whenComplete((httpResponse, error) -> {
            if (guard != null) {
                guard.cancel(false);
            }
            Outcome<T> outcome;
            try {
                outcome = error != null ? Outcome.failure(transportFailure(call, error), null)
                        : evaluate(call, httpResponse);
            } catch (RuntimeException e) {
                outcome = Outcome.failure(new SimpleCloudException(Reason.INVALID_RESPONSE,
                        httpResponse == null ? -1 : httpResponse.statusCode(),
                        "SimpleCloud: Antwort auf " + call.describe() + " nicht verarbeitbar ("
                                + e.getClass().getSimpleName() + ")"), null);
            }
            finish(call, permit, retry, result, outcome);
        });
    }

    /**
     * Sicherheitsnetz: {@link HttpRequest#timeout} deckt nicht jede Phase (z. B. langsamen Körper) ab. Nach der
     * doppelten Anfragezeit plus einer Sekunde wird der Austausch abgebrochen.
     */
    private @Nullable ScheduledFuture<?> scheduleGuard(CompletableFuture<HttpResponse<String>> exchange) {
        if (exchange.isDone()) {
            return null;
        }
        long guardMillis = Math.min(Long.MAX_VALUE / 4, requestTimeout.toMillis()) * 2 + 1_000;
        try {
            return scheduler.schedule(() -> exchange.cancel(true), guardMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            return null;
        }
    }

    private <T> Outcome<T> evaluate(Call<T> call, HttpResponse<String> response) {
        int status = response.statusCode();
        String body = response.body() == null ? "" : response.body();
        if (status >= 200 && status < 300) {
            try {
                return Outcome.success(call.parser().parse(body));
            } catch (SimpleCloudJson.MalformedResponseException e) {
                logRequestId(call, status, response.headers());
                return Outcome.failure(new SimpleCloudException(Reason.INVALID_RESPONSE, status,
                        "SimpleCloud: unerwartete Antwort auf " + call.describe() + " (" + e.getMessage() + ")"), null);
            }
        }
        if (status == 404 && call.notOnline() != null && SimpleCloudJson.errorMessage(body) != null) {
            // {"error":"Player is not online"} - kein Fehler, der Spieler hat nur keine aktive Sitzung
            return Outcome.success(call.notOnline().get());
        }
        logRequestId(call, status, response.headers());
        Reason reason = reasonFor(status);
        if (reason == Reason.UNAUTHORIZED && unauthorizedLogged.compareAndSet(false, true)) {
            logger.severe("SimpleCloud lehnt die Anmeldung ab (HTTP " + status + ") - network-id und network-secret "
                    + "prüfen (Umgebungsvariablen SIMPLECLOUD_NETWORK_ID/SIMPLECLOUD_NETWORK_SECRET bzw. "
                    + "simplecloud.network-id/network-secret in der config.yml)");
        }
        if (status == 404 && SimpleCloudJson.errorMessage(body) == null && call.notOnline() != null) {
            logger.fine(() -> "SimpleCloud: " + call.describe() + " liefert 404 ohne JSON-Fehler - "
                    + "simplecloud.base-url prüfen");
        }
        Duration retryAfter = null;
        if (status == 429 || status == 503) {
            retryAfter = RetryBackoff.parseRetryAfter(response.headers().firstValue("Retry-After").orElse(null),
                    clock.instant());
        }
        String detail = errorDetail(body, response.headers());
        return Outcome.failure(new SimpleCloudException(reason, status,
                "SimpleCloud: HTTP " + status + " bei " + call.describe() + (detail != null ? " - " + detail : "")),
                retryAfter);
    }

    private <T> void finish(Call<T> call, CircuitBreaker.Permit permit, int retry, CompletableFuture<T> result,
                            Outcome<T> outcome) {
        SimpleCloudException error = outcome.error();
        if (error == null) {
            lastSuccess = clock.instant();
            unauthorizedLogged.set(false);
            logCircuitChange(circuit.onSuccess(permit));
            result.complete(outcome.value());
            return;
        }
        failures.incrementAndGet();
        lastFailure = clock.instant();
        lastError = error.reason() + ": " + error.getMessage();

        if (shouldRetry(error, permit, retry, result)) {
            Duration delay = outcome.retryAfter() != null ? backoff.capped(outcome.retryAfter())
                    : backoff.delayFor(retry + 1);
            logger.fine(() -> "SimpleCloud: " + call.describe() + " fehlgeschlagen (" + error.reason()
                    + "), Versuch " + (retry + 2) + "/" + (maxRetries + 1) + " in " + delay.toMillis() + " ms");
            try {
                scheduler.schedule(() -> attempt(call, permit, retry + 1, result), delay.toMillis(),
                        TimeUnit.MILLISECONDS);
                return;
            } catch (RejectedExecutionException e) {
                // Client wird geschlossen - Fehler unten melden
            }
        }
        if (countsAgainstCircuit(error.reason())) {
            logCircuitChange(circuit.onFailure(permit));
        } else {
            // der Server hat geantwortet (z. B. 400/404) - das belegt die Erreichbarkeit
            logCircuitChange(circuit.onSuccess(permit));
        }
        result.completeExceptionally(error);
    }

    private boolean shouldRetry(SimpleCloudException error, CircuitBreaker.Permit permit, int retry,
                                CompletableFuture<?> result) {
        return error.retryable()
                && permit != CircuitBreaker.Permit.TRIAL
                && retry < maxRetries
                && !closed.get()
                && !result.isDone()
                && !circuit.isOpen();
    }

    private void logCircuitChange(CircuitBreaker.Change change) {
        switch (change) {
            case OPENED -> logger.warning("SimpleCloud wiederholt nicht erreichbar - Anfragen werden für "
                    + circuit.openDuration().toSeconds() + " s ausgesetzt (letzter Fehler: " + lastError + ")");
            case REOPENED -> logger.fine(() -> "SimpleCloud weiterhin nicht erreichbar - Circuit-Breaker bleibt offen");
            case CLOSED -> logger.info("SimpleCloud wieder erreichbar");
            case NONE -> {
                // nichts zu melden
            }
        }
    }

    private void logRequestId(Call<?> call, int status, HttpHeaders headers) {
        if (!logger.isLoggable(Level.FINE)) {
            return;
        }
        String requestId = headers.firstValue(HEADER_REQUEST_ID).map(RestSimpleCloudClient::sanitizeToken)
                .orElse("-");
        logger.fine("SimpleCloud: HTTP " + status + " bei " + call.describe() + " (x-request-id: " + requestId + ")");
    }

    private SimpleCloudException transportFailure(Call<?> call, Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof HttpConnectTimeoutException) {
            return new SimpleCloudException(Reason.UNAVAILABLE, -1, "SimpleCloud: Zeitüberschreitung beim "
                    + "Verbindungsaufbau für " + call.describe(), cause);
        }
        if (cause instanceof HttpTimeoutException || cause instanceof TimeoutException
                || cause instanceof CancellationException) {
            return new SimpleCloudException(Reason.UNAVAILABLE, -1, "SimpleCloud: Zeitüberschreitung bei "
                    + call.describe() + " (nach " + requestTimeout.toMillis() + " ms)");
        }
        if (cause instanceof IOException) {
            String message = cause.getMessage();
            return new SimpleCloudException(Reason.UNAVAILABLE, -1, "SimpleCloud nicht erreichbar bei "
                    + call.describe() + " (" + cause.getClass().getSimpleName()
                    + (message != null && !message.isBlank() ? ": " + truncate(redact(message)) : "") + ")", cause);
        }
        if (cause instanceof IllegalArgumentException) {
            // Ursache bewusst nicht anhängen: Meldungen zu Header-Werten könnten Konfigurationswerte enthalten
            return new SimpleCloudException(Reason.INVALID_REQUEST, -1, "SimpleCloud: Anfrage " + call.describe()
                    + " ungültig (Konfiguration prüfen)");
        }
        return new SimpleCloudException(Reason.UNKNOWN, -1, "SimpleCloud: unerwarteter Fehler bei " + call.describe()
                + " (" + cause.getClass().getSimpleName() + ")");
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /** Statuscode -> Fehlergrund (siehe {@link Reason}). */
    static Reason reasonFor(int status) {
        return switch (status) {
            case 400, 422 -> Reason.INVALID_REQUEST;
            case 401, 403 -> Reason.UNAUTHORIZED;
            case 404 -> Reason.NOT_FOUND;
            case 429 -> Reason.RATE_LIMITED;
            case 408, 502, 503, 504 -> Reason.UNAVAILABLE;
            default -> {
                if (status >= 500) {
                    yield Reason.UNKNOWN;
                }
                yield status >= 400 ? Reason.INVALID_REQUEST : Reason.INVALID_RESPONSE;
            }
        };
    }

    /** Fehler, die auf ein Problem mit SimpleCloud (oder den Zugangsdaten) hindeuten und den Breaker füttern. */
    private static boolean countsAgainstCircuit(Reason reason) {
        return switch (reason) {
            case UNAVAILABLE, RATE_LIMITED, UNKNOWN, UNAUTHORIZED, INVALID_RESPONSE -> true;
            default -> false;
        };
    }

    private @Nullable String errorDetail(String body, HttpHeaders headers) {
        String error = SimpleCloudJson.errorMessage(body);
        if (error == null) {
            String contentType = headers.firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
            if (!contentType.startsWith("text/plain") || body.isBlank()) {
                return null;
            }
            error = body.strip().lines().findFirst().orElse("");
        }
        String cleaned = truncate(redact(error.replaceAll("\\p{Cntrl}", " ").strip()));
        return cleaned.isEmpty() ? null : cleaned;
    }

    private URI uri(Call<?> call) {
        return URI.create(baseUrl + call.path() + (call.query() != null ? "?" + call.query() : ""));
    }

    private SimpleCloudException closedError() {
        return new SimpleCloudException(Reason.UNAVAILABLE, -1, "SimpleCloud-Client wurde geschlossen");
    }

    /** Entfernt das Secret aus Texten, die in Logs, Fehlermeldungen oder {@link #health()} landen. */
    private String redact(String text) {
        if (networkSecret.length() < 4 || text.indexOf(networkSecret) < 0) {
            return text;
        }
        return text.replace(networkSecret, "***");
    }

    private static String truncate(String text) {
        return text.length() <= MAX_ERROR_TEXT ? text : text.substring(0, MAX_ERROR_TEXT) + "...";
    }

    /** Prüft base-url und Header-Werte; Probleme werden ohne die Werte selbst gemeldet. */
    private boolean validateConfiguration() {
        URI base;
        try {
            base = URI.create(baseUrl + SESSIONS_PATH);
        } catch (IllegalArgumentException e) {
            logger.warning("simplecloud.base-url ist keine gültige URL - SimpleCloud-REST deaktiviert");
            return false;
        }
        String scheme = base.getScheme() == null ? "" : base.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("https") || scheme.equals("http")) || base.getHost() == null) {
            logger.warning("simplecloud.base-url muss eine http(s)-URL mit Host sein - SimpleCloud-REST deaktiviert");
            return false;
        }
        if (base.getRawUserInfo() != null) {
            logger.warning("simplecloud.base-url darf keine Zugangsdaten enthalten - SimpleCloud-REST deaktiviert");
            return false;
        }
        if (!isValidHeaderValue(networkId) || !isValidHeaderValue(networkSecret)) {
            logger.warning("simplecloud.network-id oder network-secret enthält ungültige Zeichen "
                    + "(Zeilenumbrüche/Steuerzeichen) - SimpleCloud-REST deaktiviert");
            return false;
        }
        if (scheme.equals("http") && !isLoopback(base.getHost())) {
            logger.warning("simplecloud.base-url verwendet kein HTTPS - das Netzwerk-Secret wird unverschlüsselt "
                    + "übertragen");
        }
        return true;
    }

    private static boolean isValidHeaderValue(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f || c > 0xff) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLoopback(String host) {
        String h = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (h.equalsIgnoreCase("localhost")) {
            return true;
        }
        if (!h.matches("[0-9.]+") && !h.contains(":")) {
            return false; // Hostname - keine DNS-Auflösung im Konstruktor
        }
        try {
            return InetAddress.getByName(h).isLoopbackAddress();
        } catch (UnknownHostException | SecurityException e) {
            return false;
        }
    }

    private static String trimTrailingSlashes(@Nullable String url) {
        String result = url == null ? "" : url.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /** Macht einen Wert headertauglich (druckbares ASCII ohne Leerzeichen). */
    private static String sanitizeToken(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        StringBuilder out = new StringBuilder(Math.min(value.length(), 64));
        for (int i = 0; i < value.length() && out.length() < 64; i++) {
            char c = value.charAt(i);
            out.append(c > 0x20 && c < 0x7f ? c : '_');
        }
        return out.toString();
    }

    private static Duration positiveOr(@Nullable Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }

    private static Duration nonNegative(@Nullable Duration value) {
        return value == null || value.isNegative() ? Duration.ZERO : value;
    }

    private static ThreadFactory daemonThreads(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
