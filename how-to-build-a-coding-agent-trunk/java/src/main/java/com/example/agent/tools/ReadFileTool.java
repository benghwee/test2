package com.example.agent.tools;

import com.example.agent.Log;
import com.example.agent.SchemaUtil;
import com.example.agent.Tool;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ReadFileTool implements Tool {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectNode SCHEMA = SchemaUtil.generateSchema(Input.class);

    public record Input(
            @JsonPropertyDescription("The relative path of a file in the working directory.")
            String path) {}

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "Read the contents of a given relative file path. Use this when you want to see "
                + "what's inside a file. Do not use this with directory names.";
    }

    @Override
    public ObjectNode inputSchema() {
        return SCHEMA;
    }

    @Override
    public String execute(ObjectNode input) {
        String path = input.path("path").asText();
        Log.v("Reading file: %s", path);
        try {
            byte[] bytes = Files.readAllBytes(Path.of(path));
            Log.v("Successfully read file %s (%d bytes)", path, bytes.length);
            return new String(bytes);
        } catch (Exception e) {
            Log.v("Failed to read file %s: %s", path, e.getMessage());
            return "Error reading file: " + e.getMessage();
        }
    }
}
