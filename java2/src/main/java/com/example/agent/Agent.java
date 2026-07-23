package com.example.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The shared agent event loop, consolidating the (heavily duplicated) Run /
 * runInference logic from the six Go binaries into a single class. Selects a
 * tool set via the constructor, then drives the conversation until Claude stops
 * requesting tools.
 */
public final class Agent {
    private static final int MAX_TOKENS = 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OpenAILocalClient client;
    private final List<Tool> tools;
    private final BufferedReader stdin =
            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

    public Agent(OpenAILocalClient client, List<Tool> tools) {
        this.client = client;
        this.tools = tools;
    }

    public void run() throws Exception {
        List<JsonNode> conversation = new ArrayList<>();
        Log.v("Starting chat session with %d tools enabled", tools.size());
        System.out.println("Chat with Claude (use 'ctrl-c' to quit)");

        while (true) {
            System.out.print("\u001b[94mYou\u001b[0m: ");
            String userInput = stdin.readLine();
            if (userInput == null) {
                Log.v("User input ended, breaking from chat loop");
                break;
            }
            if (userInput.isEmpty()) {
                continue;
            }
            Log.v("User input received: %s", userInput);

            ObjectNode userMsg = MAPPER.createObjectNode();
            userMsg.put("role", "user");
            ArrayNode ub = userMsg.putArray("content");
            ub.addObject().put("type", "text").put("text", userInput);
            conversation.add(userMsg);

            JsonNode content = client.createMessage(conversation, tools, MAX_TOKENS);
            conversation.add(wrapAssistant(content));

            // Keep processing tool-use rounds until Claude stops using tools.
            while (true) {
                boolean hasToolUse = false;
                ArrayNode toolResults = MAPPER.createArrayNode();

                for (JsonNode block : content) {
                    String type = block.path("type").asText();
                    if ("text".equals(type)) {
                        System.out.printf("\u001b[93mClaude\u001b[0m: %s%n", block.path("text").asText());
                    } else if ("tool_use".equals(type)) {
                        hasToolUse = true;
                        String toolName = block.path("name").asText();
                        String rawInput = block.path("input").toString();
                        Log.v("Tool use detected: %s with input: %s", toolName, rawInput);
                        System.out.printf("\u001b[96mtool\u001b[0m: %s(%s)%n", toolName, rawInput);

                        String result = null;
                        String error = null;
                        Tool tool = findTool(toolName);
                        if (tool != null) {
                            try {
                                result = tool.execute((ObjectNode) MAPPER.readTree(rawInput));
                                System.out.printf("\u001b[92mresult\u001b[0m: %s%n", result);
                            } catch (Exception e) {
                                error = e.getMessage();
                                System.out.printf("\u001b[91merror\u001b[0m: %s%n", error);
                            }
                        } else {
                            error = "tool '" + toolName + "' not found";
                            System.out.printf("\u001b[91merror\u001b[0m: %s%n", error);
                        }

                        ObjectNode tr = MAPPER.createObjectNode();
                        tr.put("type", "tool_result");
                        tr.put("tool_use_id", block.path("id").asText());
                        if (error != null) {
                            tr.put("is_error", true);
                            tr.put("content", error);
                        } else {
                            tr.put("content", result);
                        }
                        toolResults.add(tr);
                    }
                }

                if (!hasToolUse) {
                    break;
                }

                ObjectNode toolMsg = MAPPER.createObjectNode();
                toolMsg.put("role", "user");
                toolMsg.set("content", toolResults);
                conversation.add(toolMsg);

                content = client.createMessage(conversation, tools, MAX_TOKENS);
                conversation.add(wrapAssistant(content));
            }
        }
        Log.v("Chat session ended");
    }

    private Tool findTool(String name) {
        for (Tool t : tools) {
            if (t.name().equals(name)) {
                return t;
            }
        }
        return null;
    }

    private JsonNode wrapAssistant(JsonNode content) {
        ObjectNode a = MAPPER.createObjectNode();
        a.put("role", "assistant");
        a.set("content", content);
        return a;
    }
}
