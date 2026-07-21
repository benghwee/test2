package com.example.agent;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.lang.reflect.Field;

/**
 * Replaces the Go {@code invopop/jsonschema} reflector. Builds a JSON Schema
 * "properties" object from a POJO by reflecting over its fields and reading
 * {@link JsonPropertyDescription} for the human description.
 *
 * This produces the same shape the Anthropic API expects:
 * <pre>
 * { "type": "object", "properties": { "path": { "type": "string",
 *     "description": "..." } }, "required": [...] }
 * </pre>
 */
public final class SchemaUtil {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SchemaUtil() {}

    public static ObjectNode generateSchema(Class<?> clazz) {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");
        for (Field f : clazz.getDeclaredFields()) {
            f.setAccessible(true);
            String jsonName = f.getName();
            // honour @JsonProperty name if present
            com.fasterxml.jackson.annotation.JsonProperty jp =
                    f.getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class);
            if (jp != null && !jp.value().isEmpty()) {
                jsonName = jp.value();
            }

            ObjectNode prop = properties.putObject(jsonName);
            prop.put("type", javaType(f.getType()));
            JsonPropertyDescription desc = f.getAnnotation(JsonPropertyDescription.class);
            if (desc != null && !desc.value().isEmpty()) {
                prop.put("description", desc.value());
            }
        }
        return schema;
    }

    private static String javaType(Class<?> t) {
        if (t == int.class || t == Integer.class
                || t == long.class || t == Long.class
                || t == double.class || t == Double.class
                || t == float.class || t == Float.class) {
            return "number";
        }
        if (t == boolean.class || t == Boolean.class) {
            return "boolean";
        }
        return "string";
    }
}
