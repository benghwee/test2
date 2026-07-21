package com.example.agent.tools;

import com.example.agent.Log;
import com.example.agent.SchemaUtil;
import com.example.agent.Tool;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

public final class ListFilesTool implements Tool {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectNode SCHEMA = SchemaUtil.generateSchema(Input.class);

    public record Input(
            @JsonPropertyDescription("Optional relative path to list files from. Defaults to current directory if not provided.")
            String path) {}

    @Override
    public String name() {
        return "list_files";
    }

    @Override
    public String description() {
        return "List files and directories at a given path. If no path is provided, lists files "
                + "in the current directory.";
    }

    @Override
    public ObjectNode inputSchema() {
        return SCHEMA;
    }

    @Override
    public String execute(ObjectNode input) {
        String dir = input.path("path").asText("");
        if (dir == null || dir.isEmpty()) {
            dir = ".";
        }
        Log.v("Listing files in directory: %s", dir);
        Path base = Path.of(dir);
        ArrayNode files = MAPPER.createArrayNode();
        try (Stream<Path> stream = Files.walk(base)) {
            stream
                    .sorted(Comparator.comparing(p -> p.toString()))
                    .forEach(p -> {
                        try {
                            Path rel = base.relativize(p);
                            String relStr = rel.toString();
                            if (relStr.equals(".")) {
                                return;
                            }
                            if (relStr.equals(".devenv") || relStr.startsWith(".devenv" + java.io.File.separator)) {
                                return;
                            }
                            if (Files.isDirectory(p)) {
                                files.add(relStr + "/");
                            } else {
                                files.add(relStr);
                            }
                        } catch (Exception ignored) {
                        }
                    });
        } catch (IOException e) {
            Log.v("Failed to list files in %s: %s", dir, e.getMessage());
            return "Error listing files: " + e.getMessage();
        }
        Log.v("Successfully listed %d items in %s", files.size(), dir);
        try {
            return MAPPER.writeValueAsString(files);
        } catch (Exception e) {
            return files.toString();
        }
    }
}
