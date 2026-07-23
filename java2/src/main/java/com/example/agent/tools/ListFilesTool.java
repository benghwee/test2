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
import java.util.ArrayList;
import java.util.List;

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
        ObjectNode schema = SCHEMA.deepCopy();
        // path is optional, so no required fields
        return schema;
    }

    @Override
    public String execute(ObjectNode input) {
        String dir = input.path("path").asText("");
        if (dir == null || dir.isEmpty()) {
            dir = ".";
        }
        Log.v("Listing files in directory: %s", dir);
        
        Path base;
        try {
            // Try to get real path if exists, otherwise use as-is
            base = Path.of(dir).toRealPath();
        } catch (java.nio.file.InvalidPathException | IOException e) {
            // File/directory doesn't exist yet, use as-is
            base = Path.of(dir);
        }
        
        List<Path> paths;
        try {
            paths = Files.list(base).toList();
        } catch (IOException e) {
            return "Error: Cannot list directory: " + e.getMessage();
        }
        
        List<String> files = new ArrayList<>();
        for (Path p : paths) {
            try {
                String relStr = base.relativize(p).toString();
                
                // Skip current directory marker and hidden devenv dirs
                if (!relStr.equals(".") && !relStr.equals(".devenv") && 
                    !relStr.startsWith(".devenv" + java.io.File.separator)) {
                    if (Files.isDirectory(p)) {
                        files.add(relStr + "/");
                    } else {
                        files.add(relStr);
                    }
                }
            } catch (Exception ignored) {}
        }
        
        Log.v("Successfully listed %d items in %s", files.size(), dir);
        try {
            ArrayNode result = MAPPER.createArrayNode();
            for (String f : files) {
                result.add(f);
            }
            return MAPPER.writeValueAsString(result);
        } catch (Exception e) {
            return files.toString();
        }
    }
}
