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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class CodeSearchTool implements Tool {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectNode SCHEMA = SchemaUtil.generateSchema(Input.class);

    public record Input(
            @JsonPropertyDescription("The search pattern or regex to look for")
            String pattern,
            @JsonPropertyDescription("Optional path to search in (file or directory)")
            String path,
            @JsonPropertyDescription("Optional file extension to limit search to (e.g., 'go', 'js', 'py')")
            String file_type,
            @JsonPropertyDescription("Whether the search should be case sensitive (default: false)")
            boolean case_sensitive) {}

    @Override
    public String name() {
        return "code_search";
    }

    @Override
    public String description() {
        return "Search for code patterns using a regex over the codebase.\n\n"
                + "Use this to find code patterns, function definitions, variable usage, or any "
                + "text in the codebase. You can search by pattern, file type, or directory.";
    }

    @Override
    public ObjectNode inputSchema() {
        return SCHEMA;
    }

    @Override
    public String execute(ObjectNode input) {
        String pattern = input.path("pattern").asText("");
        String path = input.path("path").asText("");
        String fileType = input.path("file_type").asText("");
        boolean caseSensitive = input.path("case_sensitive").asBoolean(false);

        if (pattern.isEmpty()) {
            return "Error: pattern is required";
        }

        Path base = Path.of(path == null || path.isEmpty() ? "." : path);
        int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
        Pattern compiled;
        try {
            compiled = Pattern.compile(pattern, flags);
        } catch (Exception e) {
            return "Error: invalid pattern: " + e.getMessage();
        }

        final String extFilter = (fileType == null || fileType.isEmpty()) ? null : fileType;
        List<String> matches = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(base)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                if (extFilter != null && !p.toString().endsWith("." + extFilter)) {
                    continue;
                }
                try {
                    List<String> lines = Files.readAllLines(p);
                    for (int i = 0; i < lines.size(); i++) {
                        if (compiled.matcher(lines.get(i)).find()) {
                            matches.add(p + ":" + (i + 1) + ":" + lines.get(i));
                        }
                    }
                } catch (IOException ignored) {
                    // skip unreadable files
                }
            }
        } catch (IOException e) {
            return "Error searching: " + e.getMessage();
        }

        if (matches.isEmpty()) {
            Log.v("No matches found for pattern: %s", pattern);
            return "No matches found";
        }

        Log.v("Found %d matches for pattern: %s", matches.size(), pattern);
        if (matches.size() > 50) {
            List<String> limited = new ArrayList<>(matches.subList(0, 50));
            limited.add(String.format("... (showing first 50 of %d matches)", matches.size()));
            return String.join("\n", limited);
        }
        return String.join("\n", matches);
    }
}
