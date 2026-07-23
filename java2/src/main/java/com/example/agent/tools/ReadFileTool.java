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
        ObjectNode schema = SCHEMA.deepCopy();
        schema.putArray("required").add("path");
        return schema;
    }

    @Override
    public String execute(ObjectNode input) {
        String path = input.path("path").asText();
        if (path.isEmpty()) {
            return "Error: missing or empty 'path' value";
        }
        Log.v("Reading file: %s", path);
        try {
            Path filePath;
            // Handle Windows paths with drive letters (e.g., C:\\something)
            if (System.getProperty("os.name").toLowerCase().contains("win") && 
                path.length() >= 2 && path.charAt(1) == ':') {
                // Absolute Windows path - use as-is
                filePath = Path.of(path);
            } else {
                // Relative or Unix-style path
                filePath = Path.of(path);
            }
            byte[] bytes = Files.readAllBytes(filePath);
            Log.v("Successfully read file %s (%d bytes)", path, bytes.length);
            return new String(bytes);
        } catch (Exception e) {
            // Provide more helpful error messages
            String errorMsg = e.getMessage();
            if (errorMsg != null && errorMsg.contains("No such file or directory")) {
                errorMsg = "Error: File not found: " + path + ". Please check the file exists and the path is correct.";
            } else if (errorMsg != null && errorMsg.contains("permission denied")) {
                errorMsg = "Error: Permission denied reading file: " + path;
            }
            Log.v("Failed to read file %s: %s", path, e.getMessage());
            return "Error reading file: " + errorMsg;
        }
    }
}
