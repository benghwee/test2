package com.example.agent.tools;

import com.example.agent.Log;
import com.example.agent.SchemaUtil;
import com.example.agent.Tool;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class WriteFileTool implements Tool {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectNode SCHEMA = SchemaUtil.generateSchema(Input.class);

    public record Input(
            @JsonPropertyDescription("The path to the file")
            String path,
            @JsonPropertyDescription("Content to write, overwrites existing file")
            String content) {}

    @Override
    public String name() { return "write_file"; }

    @Override
    public String description() {
        return "Overwrite or create or save a file with given content. If the file exists it will be replaced.";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = SCHEMA.deepCopy();
        schema.putArray("required").add("path").add("content");
        return schema;
    }

    @Override
    public String execute(ObjectNode input) {
        String path = input.path("path").asText("");
        String content = input.path("content").asText();
        if (path.isEmpty()) return "Error: missing path";
        Path filePath = Path.of(path);
        try {
            if (filePath.getParent() != null) Files.createDirectories(filePath.getParent());
            Files.writeString(filePath, content);
            Log.v("Wrote file: %s", path);
            return "OK";
        } catch (IOException e) {
            return "Error writing file: " + e.getMessage();
        }
    }
}
