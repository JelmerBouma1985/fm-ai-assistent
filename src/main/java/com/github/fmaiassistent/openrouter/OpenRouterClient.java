package com.github.fmaiassistent.openrouter;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** No retries, redirects, provider error bodies, or credential logging. */
public final class OpenRouterClient implements AutoCloseable {
    private final HttpClient http;
    private final URI endpoint;
    private final ObjectMapper json;
    private final Duration timeout;

    public OpenRouterClient(ObjectMapper json) {
        this(json, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(120)).build(),
                URI.create("https://openrouter.ai/api/v1/chat/completions"));
    }

    OpenRouterClient(ObjectMapper json, HttpClient http, URI endpoint) {
        this(json, http, endpoint, Duration.ofSeconds(120));
    }

    OpenRouterClient(ObjectMapper json, HttpClient http, URI endpoint, Duration timeout) {
        this.json = json;
        this.http = http;
        this.endpoint = endpoint;
        this.timeout = timeout;
    }

    void stream(String key, String body, Consumer<JsonNode> chunks) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .header("Accept", "text/event-stream").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        // A separate task bounds the entire body read, including a stalled SSE stream.
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> task = executor.submit(() -> {
                try {
                    var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                    try (var input = response.body()) {
                        if (response.statusCode() != 200) throw new Failure(statusMessage(response.statusCode()));
                        var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
                        StringBuilder data = new StringBuilder();
                        int total = 0;
                        // Character-wise read avoids unbounded readLine allocations on malformed responses.
                        StringBuilder line = new StringBuilder();
                        for (int c; (c = reader.read()) != -1;) {
                            if (++total > 2 * 1024 * 1024) throw new Failure("Response too large. Ask a narrower question.");
                            if (c != '\n') { if (c != '\r') line.append((char) c); continue; }
                            String value = line.toString();
                            line.setLength(0);
                            if (value.isEmpty() && !data.isEmpty()) {
                                String payload = data.toString().stripTrailing();
                                data.setLength(0);
                                if (payload.equals("[DONE]")) return;
                                JsonNode chunk = json.readTree(payload);
                                if (chunk.has("error")) throw new Failure(statusMessage(chunk.path("error").path("code").asInt(502)));
                                chunks.accept(chunk);
                            } else if (value.startsWith("data:")) {
                                data.append(value.substring(5).stripLeading()).append('\n');
                            }
                        }
                        throw new Failure("OpenRouter stream ended unexpectedly. Start a new chat; completed tool writes remain completed.");
                    }
                } catch (Failure e) { throw e; }
                catch (Exception e) { throw new Failure("OpenRouter connection or stream failed. Check connectivity and start a new chat."); }
            });
            try { task.get(timeout.toNanos(), TimeUnit.NANOSECONDS); }
            catch (ExecutionException e) {
                if (e.getCause() instanceof Failure failure) throw failure;
                throw new Failure("OpenRouter response was malformed. Start a new chat.");
            } catch (TimeoutException e) {
                task.cancel(true);
                throw new Failure("OpenRouter request timed out after 120 seconds. Start a new chat.");
            } catch (InterruptedException e) { task.cancel(true); throw e; }
        }
    }

    JsonNode models(String key) {
        URI catalog = endpoint.resolve(endpoint.getPath().replaceFirst("/chat(?:/completions)?$", "/models/user"));
        HttpRequest request = HttpRequest.newBuilder(catalog).timeout(timeout)
                .header("Authorization", "Bearer " + key).header("Accept", "application/json")
                .GET().build();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var input = response.body()) {
                if (response.statusCode() != 200) throw new Failure(response.statusCode() == 404
                        ? "OpenRouter model catalog is unavailable. Try again later."
                        : statusMessage(response.statusCode()));
                byte[] data = input.readNBytes(8 * 1024 * 1024 + 1);
                if (data.length > 8 * 1024 * 1024) throw new Failure("OpenRouter model catalog is too large. Try again later.");
                return json.readTree(data);
            }
        } catch (Failure e) { throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new Failure("Model loading stopped."); }
        catch (Exception e) { throw new Failure("Could not load OpenRouter models. Check connectivity and try again."); }
    }

    @Override public void close() { http.shutdownNow(); }

    static String statusMessage(int status) {
        return switch (status) {
            case 401 -> "Invalid OpenRouter API key. Disconnect and enter a valid key.";
            case 402 -> "OpenRouter reports insufficient credits. Check your account; Free will not switch to paid routing.";
            case 403 -> "OpenRouter access denied. Check key permissions and account privacy restrictions.";
            case 429 -> "OpenRouter rate limit reached. Wait before trying a new chat; no paid fallback was used.";
            case 503, 404 -> "No OpenRouter capacity matches this request or your privacy settings. Try later; no settings were relaxed.";
            default -> "OpenRouter request failed (HTTP " + status + "). Check your account or try a new chat later.";
        };
    }

    static final class Failure extends RuntimeException {
        Failure(String message) { super(message); }
    }
}
