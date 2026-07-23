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

public final class EditFileTool implements Tool {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectNode SCHEMA = SchemaUtil.generateSchema(Input.class);

    public record Input(
            @JsonPropertyDescription("The path to the file")
            String path,
            @JsonPropertyDescription("Text to search for - must match exactly and must only have one match exactly")
            String old_str,
            @JsonPropertyDescription("Text to replace old_str with")
            String new_str) {}

    @Override
    public String name() {
        return "edit_file";
    }

    @Override
    public String description() {
        return "Make edits to a text file."
                + "Replaces 'old_str' with 'new_str' in the given file. 'old_str' and 'new_str' "
                + "MUST be different from each other."
                + "If the file specified with path doesn't exist, it will be created.";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = SCHEMA.deepCopy();
        schema.putArray("required").add("path").add("old_str").add("new_str");
        return schema;
    }

    @Override
    public String execute(ObjectNode input) {
        String path = input.path("path").asText("");
        String oldStr = input.path("old_str").asText("");
        String newStr = input.path("new_str").asText("");
        if (path.isEmpty() || oldStr.equals(newStr)) {
            return "Error: invalid input parameters";
        }

        // Normalize path for cross-platform compatibility
        Path filePath;
        try {
            filePath = Path.of(path).toRealPath();
        } catch (java.nio.file.InvalidPathException | IOException e) {
            // File doesn't exist yet, use as-is
            filePath = Path.of(path);
        }
        
        boolean exists = Files.exists(filePath);
        if (!exists) {
            if (oldStr.isEmpty()) {
                return createNewFile(filePath, newStr);
            }
            return "Error: file does not exist: " + path;
        }

        try {
            String content = Files.readString(filePath);
            if (oldStr.isEmpty()) {
                String newContent = content + newStr;
                Files.writeString(filePath, newContent);
                Log.v("Appended to file: %s", path);
                return "OK";
            }
            int count = countOccurrences(content, oldStr);
            if (count == 0) {
                return "Error: old_str not found in file";
            }
            if (count > 1) {
                return String.format("Error: old_str found %d times in file, must be unique", count);
            }
            String newContent = content.replaceFirst(escape(oldStr), escapeReplacement(newStr));
            Files.writeString(filePath, newContent);
            Log.v("Successfully edited file: %s", path);
            return "OK";
        } catch (IOException e) {
            Log.v("Failed to edit file %s: %s", path, e.getMessage());
            return "Error editing file: " + e.getMessage();
        }
    }

    private String createNewFile(Path filePath, String content) {
        try {
            Path parent = filePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(filePath, content);
            Log.v("Successfully created file: %s", filePath);
            return "Successfully created file " + filePath;
        } catch (IOException e) {
            return "Error creating file: " + e.getMessage();
        }
    }

    private int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace(".", "\\.")
                .replace("+", "\\+")
                .replace("*", "\\*")
                .replace("?", "\\?")
                .replace("^", "\\^")
                .replace("$", "\\$")
                .replace("(", "\\(")
                .replace(")", "\\)")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("{", "\\{")
                .replace("}", "\\}")
                .replace("|", "\\|");
    }

    private String escapeReplacement(String s) {
        return s.replace("\\", "\\\\").replace("$", "\\$");
    }
}
