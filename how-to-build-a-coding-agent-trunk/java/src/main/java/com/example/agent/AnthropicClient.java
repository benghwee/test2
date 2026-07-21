package com.example.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Minimal HTTP wrapper around the Anthropic Messages API, replacing the Go
 * {@code anthropics/anthropic-sdk-go} client. Uses only the JDK
 * {@code java.net.http} stack -- no third-party HTTP dependency.
 *
 * Returns the parsed "content" array of the response so the Agent can iterate
 * over text and tool_use blocks exactly like the Go {@code message.Content}.
 */
public final class AnthropicClient {
    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String VERSION = "2023-06-01";
    private static final String MODEL = "claude-opus-4-6";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    public AnthropicClient() {
        if (System.getenv("ANTHROPIC_API_KEY") == null) {
            throw new IllegalStateException("ANTHROPIC_API_KEY environment variable is not set");
        }
    }

    /**
     * Send a full conversation plus tools, return the response content array.
     */
    public JsonNode createMessage(List<JsonNode> messages, List<Tool> tools, int maxTokens)
            throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", MODEL);
        body.put("max_tokens", maxTokens);

        ArrayNode msgs = body.putArray("messages");
        for (JsonNode m : messages) {
            msgs.add(m);
        }

        if (!tools.isEmpty()) {
            ArrayNode toolArr = body.putArray("tools");
            for (Tool t : tools) {
                ObjectNode toolObj = toolArr.addObject();
                toolObj.put("name", t.name());
                toolObj.put("description", t.description());
                toolObj.set("input_schema", t.inputSchema());
            }
        }

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", "application/json")
                .header("x-api-key", System.getenv("ANTHROPIC_API_KEY"))
                .header("anthropic-version", VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));

        HttpResponse<String> resp = http.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Anthropic API error " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode root = mapper.readTree(resp.body());
        return root.get("content");
    }
}
