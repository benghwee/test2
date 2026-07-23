package com.example.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
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
 * Minimal HTTP wrapper around an OpenAI-compatible chat completions API,
 * targeting local LLMs (Ollama, LM Studio, vLLM, LocalAI, etc.).
 * Uses only the JDK {@code java.net.http} stack -- no third-party HTTP dependency.
 *
 * Returns a parsed "content" array matching the Anthropic-style format so the
 * Agent can iterate over text and tool_use blocks uniformly.
 *
 * Environment variables:
 *   LOCAL_LLM_BASE_URL - base URL (default: http://localhost:11434)
 *   LOCAL_LLM_MODEL    - model name (default: llama3.1)
 *   LOCAL_LLM_API_KEY  - optional API key (default: "no-key")
 */
public final class OpenAILocalClient {

    //private static final String DEFAULT_BASE_URL = "https://openrouter.ai/api";
    //private static final String DEFAULT_MODEL = "poolside/laguna-xs-2.1:free";
    //private static final String COMPLETIONS_PATH = "/v1/chat/completions";

    private static final String DEFAULT_BASE_URL = "http://localhost:1234";
    private static final String DEFAULT_MODEL = "openai/gpt-oss-20b";
    private static final String COMPLETIONS_PATH = "/v1/chat/completions";

    private final String baseUrl;
    private final String model;
    private final String apiKey;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenAILocalClient() {
        this.baseUrl = envOrDefault("LOCAL_LLM_BASE_URL", DEFAULT_BASE_URL);
        this.model = envOrDefault("LOCAL_LLM_MODEL", DEFAULT_MODEL);
        this.apiKey = envOrDefault("LOCAL_LLM_API_KEY", "");
    }

    /**
     * Send a full conversation plus tools, return the response content array.
     *
     * The returned JsonNode is an ArrayNode of content blocks in Anthropic-style
     * format for uniform handling by the Agent:
     *   - {"type":"text","text":"..."}
     *   - {"type":"tool_use","id":"...","name":"...","input":{...}}
     */
    public JsonNode createMessage(List<JsonNode> messages, List<Tool> tools, int maxTokens)
            throws Exception {

        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
                body.put("max_tokens", 4096);
        body.put("stream", false);
                body.put("tool_choice", "auto");

        // --- messages (OpenAI format: role + content, or role + tool_calls) ---
        // Ensure system prompt is first in the message list
                if (messages.isEmpty() || !"system".equals(messages.get(0).path("role").asText())) {
                    ObjectNode sysMsg = mapper.createObjectNode();
                    sysMsg.put("role", "system");
                    sysMsg.put("content",
                        "You are a helpful assistant with access to functions. " +
                        "When a user request requires calling a function, respond with a JSON object specifying the function name and parameters using the standard tool call format. " +
                        "Do not add conversational text around the tool call when executing a function.");
                    messages.add(0, sysMsg);
                }

                ArrayNode msgs = body.putArray("messages");
                for (JsonNode m : messages) {
                    msgs.add(convertMessageToOpenAIFormat(m));
                }

                // --- tools (OpenAI function-calling format) ---
        if (!tools.isEmpty()) {
            ArrayNode toolArr = body.putArray("tools");
            for (Tool t : tools) {
                ObjectNode toolObj = toolArr.addObject();
                toolObj.put("type", "function");
                ObjectNode fn = toolObj.putObject("function");
                fn.put("name", t.name());
                fn.put("description", t.description());
                fn.set("parameters", t.inputSchema());
            }
        }

        String endpoint = baseUrl.replaceAll("/+$", "") + COMPLETIONS_PATH;

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));

        System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(body));

        HttpResponse<String> resp = http.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Local LLM API error " + resp.statusCode() + ": " + resp.body());
        }

        JsonNode root = mapper.readTree(resp.body());
        JsonNode choice = root.get("choices").get(0).get("message");
        System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        // Convert OpenAI response to Anthropic-style content array
        return convertResponseToContentArray(choice);
    }

    /**
     * Converts an Anthropic-style message node to OpenAI chat format.
     * Handles:
     *   - Simple text messages (role + content string)
     *   - Messages with content arrays (text blocks, tool_use blocks)
     *   - Tool result messages
     */
    private JsonNode convertMessageToOpenAIFormat(JsonNode anthropicMsg) throws JsonProcessingException {
        String role = anthropicMsg.has("role") ? anthropicMsg.get("role").asText() : "user";

        // If it's already in a simple format with string content, pass through
        if (anthropicMsg.has("content") && anthropicMsg.get("content").isTextual()) {
            ObjectNode msg = mapper.createObjectNode();
            msg.put("role", role);
            msg.put("content", anthropicMsg.get("content").asText());
            return msg;
        }

        // If content is an array (Anthropic-style blocks)
        if (anthropicMsg.has("content") && anthropicMsg.get("content").isArray()) {
            ArrayNode contentBlocks = (ArrayNode) anthropicMsg.get("content");

            // Check if this is a tool_result message
            if (contentBlocks.size() > 0) {
                JsonNode firstBlock = contentBlocks.get(0);
                if (firstBlock.has("type") && "tool_result".equals(firstBlock.get("type").asText())) {
                    // Convert to OpenAI tool response format
                    ObjectNode msg = mapper.createObjectNode();
                    msg.put("role", "tool");
                    msg.put("tool_call_id", firstBlock.get("tool_use_id").asText());
                    msg.put("content", firstBlock.has("content") ? firstBlock.get("content").asText() : "");
                    return msg;
                }
            }

            // Check for assistant messages with tool_use blocks
            if ("assistant".equals(role)) {
                ObjectNode msg = mapper.createObjectNode();
                msg.put("role", "assistant");

                StringBuilder textContent = new StringBuilder();
                ArrayNode toolCalls = mapper.createArrayNode();

                for (JsonNode block : contentBlocks) {
                    String type = block.has("type") ? block.get("type").asText() : "";
                    if ("text".equals(type)) {
                        textContent.append(block.get("text").asText());
                    } else if ("tool_use".equals(type)) {
                        ObjectNode tc = toolCalls.addObject();
                        tc.put("id", block.get("id").asText());
                        tc.put("type", "function");
                        ObjectNode fn = tc.putObject("function");
                        fn.put("name", block.get("name").asText());
                        fn.put("arguments", mapper.writeValueAsString(block.get("input")));
                    }
                }

                if (textContent.length() > 0) {
                    msg.put("content", textContent.toString());
                } else {
                    msg.putNull("content");
                }

                if (toolCalls.size() > 0) {
                    msg.set("tool_calls", toolCalls);
                }
                return msg;
            }

            // For user messages with content array, concatenate text
            ObjectNode msg = mapper.createObjectNode();
            msg.put("role", role);
            StringBuilder text = new StringBuilder();
            for (JsonNode block : contentBlocks) {
                if (block.has("type") && "text".equals(block.get("type").asText())) {
                    text.append(block.get("text").asText());
                }
            }
            msg.put("content", text.toString());
            return msg;
        }

        // Fallback: return as-is
        return anthropicMsg;
    }

    /**
     * Converts an OpenAI response message to an Anthropic-style content array.
     *
     * OpenAI format:
     *   {"role":"assistant","content":"text","tool_calls":[...]}
     *
     * Anthropic-style output:
     *   [{"type":"text","text":"..."},{"type":"tool_use","id":"...","name":"...","input":{...}}]
     */
    private JsonNode convertResponseToContentArray(JsonNode message) throws Exception {
        ArrayNode contentArray = mapper.createArrayNode();

        // Add text content if present
        if (message.has("content") && !message.get("content").isNull()) {
            String text = message.get("content").asText();
            if (!text.isEmpty()) {
                ObjectNode textBlock = contentArray.addObject();
                textBlock.put("type", "text");
                textBlock.put("text", text);
            }
        }

        // Add tool calls if present
        if (message.has("tool_calls") && message.get("tool_calls").isArray()) {
            for (JsonNode tc : message.get("tool_calls")) {
                ObjectNode toolBlock = contentArray.addObject();
                toolBlock.put("type", "tool_use");
                toolBlock.put("id", tc.get("id").asText());

                JsonNode function = tc.get("function");
                toolBlock.put("name", function.get("name").asText());

                // Parse arguments string back to JSON object
                String argsStr = function.get("arguments").asText();
                JsonNode argsNode;
                try {
                    argsNode = mapper.readTree(argsStr);
                } catch (Exception e) {
                    // If parsing fails, wrap in a raw object
                    argsNode = mapper.createObjectNode().put("raw", argsStr);
                }
                toolBlock.set("input", argsNode);
            }
        }

        return contentArray;
    }

    private static String envOrDefault(String key, String defaultValue) {
        String val = System.getenv(key);
        return (val != null && !val.isBlank()) ? val : defaultValue;
    }
}