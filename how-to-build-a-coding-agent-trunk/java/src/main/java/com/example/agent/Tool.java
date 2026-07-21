package com.example.agent;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Mirrors the Go {@code ToolDefinition} struct: a named tool with a JSON input
 * schema and an execute function. Input arrives as a Jackson ObjectNode parsed
 * from the raw JSON the model sends.
 */
public interface Tool {
    String name();

    String description();

    /** JSON Schema object describing the tool's input (properties + type). */
    ObjectNode inputSchema();

    /**
     * Execute the tool. Returns the textual result to send back to the model.
     * Implementations should not throw for expected failures (e.g. file not
     * found); instead return an error string so the model can recover.
     */
    String execute(ObjectNode input);
}
