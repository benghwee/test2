package io.github.pocketflow.nodes;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.List;
import java.util.Map;

/** Helpers for parsing LLM YAML responses (mirrors Python yaml.safe_load). */
public final class YamlUtil {

    private YamlUtil() {
    }

    /** Extract the fenced ```yaml block from an LLM response. */
    public static String extractYaml(String response) {
        String trimmed = response.trim();
        if (!trimmed.contains("```yaml")) {
            // Some providers omit the fence; try a plain yaml block.
            if (trimmed.contains("```")) {
                return trimmed.split("```")[1];
            }
            return trimmed;
        }
        String inner = trimmed.split("```yaml")[1];
        if (inner.contains("```")) {
            inner = inner.split("```")[0];
        }
        return inner.trim();
    }

    @SuppressWarnings("unchecked")
    public static Object load(String yamlText) {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        return yaml.load(yamlText);
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object o) {
        return (List<Object>) o;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }
}
