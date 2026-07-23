package com.example.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

public class AgentExample {

    public static void main(String[] args) throws Exception {
        System.setProperty("local.llm.base.url", "https://openrouter.ai/api/v1");
        System.setProperty("local.llm.model", "poolside/laguna-xs-2.1:free");
        System.setProperty("local.llm.api.key", "my-secret-key");
        ObjectMapper mapper = new ObjectMapper();
        OpenAILocalClient client = new OpenAILocalClient();

        // Build a simple user message
        List<JsonNode> messages = new ArrayList<>();
        ObjectNode userMsg = mapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", "What is the weather in San Francisco?");
        messages.add(userMsg);

        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

// Properties
        ObjectNode props = schema.putObject("properties");
        ObjectNode locProp = props.putObject("location");
        locProp.put("type", "string");
        locProp.put("description", "The city name");

// Required — build array node explicitly
        var requiredArray = mapper.createArrayNode();
        requiredArray.add("location");
        schema.set("required", requiredArray);  // use set() instead of putArray()

        List<Tool> tools = List.of(
                new WeatherTool("get_weather", "Get current weather for a location", schema)
        );

        // Call the local LLM
        JsonNode content = client.createMessage(messages, tools, 4096);

        // Iterate over response blocks (same pattern as AnthropicClient)
        for (JsonNode block : content) {
            String type = block.get("type").asText();
            switch (type) {
                case "text" -> System.out.println("Text: " + block.get("text").asText());
                case "tool_use" -> {
                    System.out.println("Tool call: " + block.get("name").asText());
                    System.out.println("  ID: " + block.get("id").asText());
                    System.out.println("  Input: " + block.get("input"));
                }
            }
        }
    }

    public static ObjectNode buildTool(ObjectMapper mapper, String name, String description, ObjectNode parameters) {
        ObjectNode functionBody = mapper.createObjectNode();
        functionBody.put("name", name);
        functionBody.put("description", description);
        functionBody.set("parameters", parameters);

        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");
        tool.set("function", functionBody);

        return tool;
    }
}
