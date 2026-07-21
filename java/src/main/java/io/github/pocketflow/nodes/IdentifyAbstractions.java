package io.github.pocketflow.nodes;

import io.github.pocketflow.Node;
import io.github.pocketflow.utils.CallGitHubCopilotLlm;
import io.github.pocketflow.utils.CallLlm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mirrors {@code IdentifyAbstractions} from nodes.py. */
public class IdentifyAbstractions extends Node {

    public IdentifyAbstractions() {
        super(5, 20);
    }

    private static final class Prep {
        String context;
        String fileListing;
        int fileCount;
        String projectName;
        String language;
        boolean useCache;
        int maxAbstractionNum;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object prep(Map<String, Object> shared) {
        List<Object> filesData = (List<Object>) shared.get("files");
        String projectName = (String) shared.get("project_name");
        String language = (String) shared.getOrDefault("language", "english");
        boolean useCache = Boolean.parseBoolean(String.valueOf(
                shared.getOrDefault("use_cache", true)));
        int maxAbstractionNum = (int) shared.getOrDefault("max_abstraction_num", 10);

        StringBuilder context = new StringBuilder();
        List<String> fileInfo = new ArrayList<>();
        for (int i = 0; i < filesData.size(); i++) {
            List<String> entry = (List<String>) filesData.get(i);
            String path = entry.get(0);
            String content = entry.get(1);
            context.append("--- File Index ").append(i).append(": ").append(path)
                    .append(" ---\n").append(content).append("\n\n");
            fileInfo.add("- " + i + " # " + path);
        }

        Prep prep = new Prep();
        prep.context = context.toString();
        prep.fileListing = String.join("\n", fileInfo);
        prep.fileCount = filesData.size();
        prep.projectName = projectName;
        prep.language = language;
        prep.useCache = useCache;
        prep.maxAbstractionNum = maxAbstractionNum;
        return prep;
    }

    @Override
    public Object exec(Object prepResult) {
        Prep p = (Prep) prepResult;
        System.out.println("Identifying abstractions using LLM...");

        String langInstruction = "";
        String nameLangHint = "";
        String descLangHint = "";
        if (!p.language.equalsIgnoreCase("english")) {
            String cap = capitalize(p.language);
            langInstruction = "IMPORTANT: Generate the `name` and `description` for each abstraction in **"
                    + cap + "** language. Do NOT use English for these fields.\n\n";
            nameLangHint = " (value in " + cap + ")";
            descLangHint = " (value in " + cap + ")";
        }

        String prompt = """
                For the project `%s`:

                Codebase Context:
                %s

                %sAnalyze the codebase context.
                Identify the top 5-%d core most important abstractions to help those new to the codebase.

                For each abstraction, provide:
                1. A concise `name`%s.
                2. A beginner-friendly `description` explaining what it is with a simple analogy, in around 100 words%s.
                3. A list of relevant `file_indices` (integers) using the format `idx # path/comment`.

                List of file indices and paths present in the context:
                %s

                Format the output as a YAML list of dictionaries:

                ```yaml
                - name: |
                    Query Processing%s
                  description: |
                    Explains what the abstraction does.
                    It's like a central dispatcher routing requests.%s
                  file_indices:
                    - 0 # path/to/file1.py
                    - 3 # path/to/related.py
                - name: |
                    Query Optimization%s
                  description: |
                    Another core concept, similar to a blueprint for objects.%s
                  file_indices:
                    - 5 # path/to/another.js
                # ... up to %d abstractions
                ```"""
                .formatted(p.projectName, p.context, langInstruction, p.maxAbstractionNum,
                        nameLangHint, descLangHint, p.fileListing, nameLangHint, descLangHint,
                        nameLangHint, descLangHint, p.maxAbstractionNum);

        String response = CallLlm.callLlm(prompt, p.useCache && this.curRetry == 0);

        String yamlStr = YamlUtil.extractYaml(response);
        Object parsed = YamlUtil.load(yamlStr);
        if (!(parsed instanceof List)) {
            throw new RuntimeException("LLM Output is not a list");
        }

        List<Object> rawList = YamlUtil.asList(parsed);
        List<Models.Abstraction> validated = new ArrayList<>();
        for (Object obj : rawList) {
            Map<String, Object> item = YamlUtil.asMap(obj);
            for (String key : new String[]{"name", "description", "file_indices"}) {
                if (!item.containsKey(key)) {
                    throw new RuntimeException("Missing keys in abstraction item: " + item);
                }
            }
            if (!(item.get("name") instanceof String)) {
                throw new RuntimeException("Name is not a string in item: " + item);
            }
            if (!(item.get("description") instanceof String)) {
                throw new RuntimeException("Description is not a string in item: " + item);
            }
            if (!(item.get("file_indices") instanceof List)) {
                throw new RuntimeException("file_indices is not a list in item: " + item);
            }

            List<Integer> validatedIndices = new ArrayList<>();
            for (Object idxEntry : (List<?>) item.get("file_indices")) {
                int idx;
                if (idxEntry instanceof Integer) {
                    idx = (Integer) idxEntry;
                } else {
                    String s = idxEntry.toString();
                    if (s.contains("#")) {
                        s = s.split("#")[0].trim();
                    }
                    idx = Integer.parseInt(s.trim());
                }
                if (!(0 <= idx && idx < p.fileCount)) {
                    throw new RuntimeException("Invalid file index " + idx
                            + " found in item " + item.get("name"));
                }
                validatedIndices.add(idx);
            }

            // store files as deduplicated, sorted unique list
            List<Integer> unique = new ArrayList<>(new LinkedHashMap<Integer, Boolean>() {{
                for (int v : validatedIndices) put(v, Boolean.TRUE);
            }}.keySet());
            unique.sort(Integer::compareTo);

            validated.add(new Models.Abstraction(
                    (String) item.get("name"),
                    (String) item.get("description"),
                    unique));
        }

        System.out.println("Identified " + validated.size() + " abstractions.");
        return validated;
    }

    @Override
    public void post(Map<String, Object> shared, Object prepResult, Object execResult) {
        @SuppressWarnings("unchecked")
        List<Models.Abstraction> abstractions = (List<Models.Abstraction>) execResult;
        shared.put("abstractions", abstractions);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
