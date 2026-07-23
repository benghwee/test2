package com.example.agent;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

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

        // Collect field info first
        List<FieldInfo> fields = new ArrayList<>();
        for (Field f : clazz.getDeclaredFields()) {
            f.setAccessible(true);
            String jsonName = f.getName();
            com.fasterxml.jackson.annotation.JsonProperty jp =
                    f.getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class);
            if (jp != null && !jp.value().isEmpty()) {
                jsonName = jp.value();
            }

            JsonPropertyDescription desc = f.getAnnotation(JsonPropertyDescription.class);
            String description = "";
            boolean hasDesc = (desc != null && !desc.value().isEmpty());
            if (hasDesc) {
                description = desc.value();
            }
            
            fields.add(new FieldInfo(jsonName, javaType(f.getType()), hasDesc, description));
        }

        // Build properties object
        ObjectNode properties = schema.putObject("properties");
        for (FieldInfo fi : fields) {
            ObjectNode prop = properties.putObject(fi.name);
            prop.put("type", fi.type);
            if (fi.hasDesc) {
                prop.put("description", fi.description);
            }
        }

        // Add required fields (those without descriptions)
        List<String> required = new ArrayList<>();
        for (FieldInfo fi : fields) {
            if (!fi.hasDesc) {
                required.add(fi.name);
            }
        }
        
        if (!required.isEmpty()) {
            ArrayNode reqArray = schema.putArray("required");
            for (String name : required) {
                reqArray.add(name);
            }
        }

        return schema;
    }

    public record FieldInfo(String name, String type, boolean hasDesc, String description) {}

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
