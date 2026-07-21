package com.example.agent.tools;

import com.example.agent.Log;
import com.example.agent.SchemaUtil;
import com.example.agent.Tool;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public final class BashTool implements Tool {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectNode SCHEMA = SchemaUtil.generateSchema(Input.class);
    private static final boolean IS_WINDOWS =
            System.getProperty("os.name").toLowerCase().contains("win");

    public record Input(
            @JsonPropertyDescription("The bash command to execute.")
            String command) {}

    @Override
    public String name() {
        return "bash";
    }

    @Override
    public String description() {
        return "Execute a shell command and return its output. Use this to run shell commands.";
    }

    @Override
    public ObjectNode inputSchema() {
        return SCHEMA;
    }

    @Override
    public String execute(ObjectNode input) {
        String command = input.path("command").asText();
        Log.v("Executing command: %s", command);
        try {
            ProcessBuilder pb;
            if (IS_WINDOWS) {
                pb = new ProcessBuilder("cmd.exe", "/c", command);
            } else {
                pb = new ProcessBuilder("bash", "-c", command);
            }
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (java.io.InputStream is = proc.getInputStream()) {
                is.transferTo(out);
            }
            int code = proc.waitFor();
            String output = out.toString(StandardCharsets.UTF_8);
            if (code != 0) {
                Log.v("Command failed with exit code %d", code);
                return String.format("Command failed with error code: %d%nOutput: %s", code, output);
            }
            Log.v("Command succeeded (output: %d bytes)", output.length());
            return output.strip();
        } catch (Exception e) {
            Log.v("Command execution error: %s", e.getMessage());
            return "Error executing command: " + e.getMessage();
        }
    }
}
