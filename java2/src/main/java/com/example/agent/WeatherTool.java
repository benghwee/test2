package com.example.agent;

import com.fasterxml.jackson.databind.node.ObjectNode;

public class WeatherTool implements Tool{
    private final String name;
    private final String description;
    private final ObjectNode inputSchema;

    public WeatherTool(String name, String description, ObjectNode inputSchema) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
    }

    @Override
    public String name() { return name; }

    @Override
    public String description() { return description; }

    @Override
    public ObjectNode inputSchema() { return inputSchema; }

    @Override
    public String execute(ObjectNode input) {
        String location = input.has("location")
                ? input.get("location").asText()
                : "unknown location";

        // Simulated weather response — replace with a real API call if needed
        return "The weather in " + location + " is currently 18°C and partly cloudy.";
    }
}
