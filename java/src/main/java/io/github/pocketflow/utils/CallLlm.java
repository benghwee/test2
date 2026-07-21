package io.github.pocketflow.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Reimplementation of {@code utils/call_llm.py}.
 *
 * <p>Supports the same providers via environment variables:
 * <ul>
 *   <li>{@code LLM_PROVIDER=GEMINI} with {@code GEMINI_API_KEY} or
 *       {@code GEMINI_PROJECT_ID}/{@code GEMINI_LOCATION} (Vertex AI)</li>
 *   <li>Any OpenAI-compatible provider via {@code <PROVIDER>_MODEL},
 *       {@code <PROVIDER>_BASE_URL}, {@code <PROVIDER>_API_KEY}</li>
 * </ul>
 *
 * <p>Responses are cached in {@code llm_cache.json}, matching the Python cache
 * keyed by the prompt string.
 */
public final class CallLlm {

    private static final Logger logger = Logger.getLogger("llm_logger");
    private static final String CACHE_FILE = "llm_cache.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(600))
            .build();

    static {
        try {
            String logDir = System.getenv().getOrDefault("LOG_DIR", "logs");
            Files.createDirectories(Paths.get(logDir));
            String logFile = logDir + "/llm_calls_"
                    + java.time.LocalDate.now().toString().replace("-", "") + ".log";
            FileHandler fh = new FileHandler(logFile, true);
            fh.setFormatter(new Formatter() {
                @Override
                public String format(LogRecord record) {
                    return record.getLevel() + " " + record.getMessage() + "\n";
                }
            });
            logger.addHandler(fh);
            logger.setLevel(Level.INFO);
            logger.setUseParentHandlers(false);
        } catch (IOException e) {
            System.err.println("Warning: could not set up LLM logger: " + e.getMessage());
        }
    }

    private CallLlm() {
    }

    private static Map<String, String> loadCache() {
        try {
            String content = Files.readString(Path.of(CACHE_FILE));
            @SuppressWarnings("unchecked")
            Map<String, String> map = MAPPER.readValue(content, Map.class);
            return map;
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private static void saveCache(Map<String, String> cache) {
        try {
            Files.writeString(Path.of(CACHE_FILE), MAPPER.writeValueAsString(cache));
        } catch (IOException e) {
            System.err.println("Warning: could not save LLM cache: " + e.getMessage());
        }
    }

    private static String getProvider() {
        String provider = System.getenv("LLM_PROVIDER");
        if (provider == null || provider.isEmpty()) {
            if (System.getenv("GEMINI_PROJECT_ID") != null
                    || System.getenv("GEMINI_API_KEY") != null) {
                provider = "GEMINI";
            }
        }
        return provider;
    }

    /** Call the configured LLM. {@code useCache} enables the on-disk cache. */
    public static String callLlm(String prompt, boolean useCache) {
        logger.info("PROMPT: " + prompt);
        if (useCache) {
            Map<String, String> cache = loadCache();
            if (cache.containsKey(prompt)) {
                String cached = cache.get(prompt);
                logger.info("RESPONSE (cached): " + cached);
                return cached;
            }
        }

        String provider = getProvider();
        String response;
        if ("GEMINI".equalsIgnoreCase(provider)) {
            response = callLlmGemini(prompt);
        } else {
            if (provider == null || provider.isEmpty()) {
                throw new IllegalArgumentException("LLM_PROVIDER environment variable is required");
            }
            response = callLlmProvider(provider, prompt);
        }
        logger.info("RESPONSE: " + response);

        if (useCache) {
            Map<String, String> cache = loadCache();
            cache.put(prompt, response);
            saveCache(cache);
        }
        return response;
    }

    public static String callLlm(String prompt) {
        return CallLlm.callLlm(prompt, true);
    }

    /** Generic OpenAI-compatible chat completions endpoint. */
    private static String callLlmProvider(String provider, String prompt) {
        String model = envOrThrow(provider + "_MODEL");
        String baseUrl = envOrThrow(provider + "_BASE_URL");
        String apiKey = System.getenv(provider + "_API_KEY");

        String url = baseUrl.replaceAll("/+$", "") + "/v1/chat/completions";

        Map<String, Object> payload = new HashMap<>();
        payload.put("model", model);
        Map<String, Object> message = new HashMap<>();
        message.put("role", "user");
        message.put("content", prompt);
        payload.put("messages", new Object[]{message});
        payload.put("temperature", 0.7);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(1200))
                .header("Content-Type", "application/json");
        if (apiKey != null && !apiKey.isEmpty()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }

        try {
            String payloadStr = MAPPER.writeValueAsString(payload);
            HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(
                    payloadStr)).build();
            HttpResponse<String> response = HTTP.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new RuntimeException("HTTP error " + response.statusCode()
                        + ": " + response.body());
            }
            JsonNode root = MAPPER.readTree(response.body());
            return root.path("choices").path(0).path("message").path("content").asText();
        } catch (IOException e) {
            throw new RuntimeException("Failed to connect to " + provider + " API: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Request to " + provider + " API timed out", e);
        }
    }

    /** Google Gemini provider (AI Studio key or Vertex AI). */
    private static String callLlmGemini(String prompt) {
        String apiKey = System.getenv("GEMINI_API_KEY");
        String projectId = System.getenv("GEMINI_PROJECT_ID");
        String model = System.getenv().getOrDefault("GEMINI_MODEL", "gemini-2.5-pro");

        String url;
        if (projectId != null && !projectId.isEmpty()) {
            String location = System.getenv().getOrDefault("GEMINI_LOCATION", "us-central1");
            url = "https://" + location + "-aiplatform.googleapis.com/v1/projects/"
                    + projectId + "/locations/" + location + "/publishers/google/models/"
                    + model + ":generateContent";
        } else if (apiKey != null && !apiKey.isEmpty()) {
            url = "https://generativelanguage.googleapis.com/v1beta/models/"
                    + model + ":generateContent?key=" + apiKey;
        } else {
            throw new IllegalArgumentException(
                    "Either GEMINI_PROJECT_ID or GEMINI_API_KEY must be set");
        }

        Map<String, Object> contents = new HashMap<>();
        Map<String, Object> part = new HashMap<>();
        part.put("text", prompt);
        contents.put("parts", new Object[]{part});
        Map<String, Object> body = new HashMap<>();
        body.put("contents", new Object[]{contents});

        try {
            String bodyStr = MAPPER.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
                    .build();

            HttpResponse<String> response = HTTP.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new RuntimeException("Gemini HTTP error " + response.statusCode()
                        + ": " + response.body());
            }
            JsonNode root = MAPPER.readTree(response.body());
            return root.path("candidates").path(0).path("content").path("parts").path(0)
                    .path("text").asText();
        } catch (IOException e) {
            throw new RuntimeException("Failed to call Gemini API: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Gemini request timed out", e);
        }
    }

    private static String envOrThrow(String name) {
        String v = System.getenv(name);
        if (v == null || v.isEmpty()) {
            throw new IllegalArgumentException(name + " environment variable is required");
        }
        return v;
    }
}
