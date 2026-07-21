package io.github.pocketflow.nodes;

import io.github.pocketflow.Node;
import io.github.pocketflow.utils.CallGitHubCopilotLlm;
import io.github.pocketflow.utils.CallLlm;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Mirrors {@code AnalyzeRelationships} from nodes.py. */
public class AnalyzeRelationships extends Node {

    public AnalyzeRelationships() {
        super(5, 20);
    }

    private static final class Prep {
        String context;
        String abstractionListing;
        int numAbstractions;
        String projectName;
        String language;
        boolean useCache;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object prep(Map<String, Object> shared) {
        List<Models.Abstraction> abstractions = (List<Models.Abstraction>) shared.get("abstractions");
        List<Object> filesData = (List<Object>) shared.get("files");
        String projectName = (String) shared.get("project_name");
        String language = (String) shared.getOrDefault("language", "english");
        boolean useCache = Boolean.parseBoolean(String.valueOf(
                shared.getOrDefault("use_cache", true)));

        StringBuilder context = new StringBuilder("Identified Abstractions:\n");
        Set<Integer> allRelevantIndices = new TreeSet<>();
        List<String> abstractionInfo = new ArrayList<>();
        for (int i = 0; i < abstractions.size(); i++) {
            Models.Abstraction abstr = abstractions.get(i);
            String fileIndicesStr = String.join(", ",
                    abstr.files.stream().map(String::valueOf).toList());
            context.append("- Index ").append(i).append(": ").append(abstr.name)
                    .append(" (Relevant file indices: [").append(fileIndicesStr).append("])\n")
                    .append("  Description: ").append(abstr.description).append("\n");
            abstractionInfo.add(i + " # " + abstr.name);
            allRelevantIndices.addAll(abstr.files);
        }

        context.append("\nRelevant File Snippets (Referenced by Index and Path):\n");
        Map<String, String> relevantMap = getContentForIndices(filesData, new ArrayList<>(allRelevantIndices));
        StringBuilder fileContext = new StringBuilder();
        for (Map.Entry<String, String> e : relevantMap.entrySet()) {
            fileContext.append("--- File: ").append(e.getKey()).append(" ---\n").append(e.getValue()).append("\n\n");
        }
        context.append(fileContext);

        Prep prep = new Prep();
        prep.context = context.toString();
        prep.abstractionListing = String.join("\n", abstractionInfo);
        prep.numAbstractions = abstractions.size();
        prep.projectName = projectName;
        prep.language = language;
        prep.useCache = useCache;
        return prep;
    }

    @Override
    public Object exec(Object prepResult) {
        Prep p = (Prep) prepResult;
        System.out.println("Analyzing relationships using LLM...");

        String langInstruction = "";
        String langHint = "";
        String listLangNote = "";
        if (!p.language.equalsIgnoreCase("english")) {
            String cap = capitalize(p.language);
            langInstruction = "IMPORTANT: Generate the `summary` and relationship `label` fields in **"
                    + cap + "** language. Do NOT use English for these fields.\n\n";
            langHint = " (in " + cap + ")";
            listLangNote = " (Names might be in " + cap + ")";
        }

        String prompt = """
                Based on the following abstractions and relevant code snippets from the project `%s`:

                List of Abstraction Indices and Names%s:
                %s

                Context (Abstractions, Descriptions, Code):
                %s

                %sPlease provide:
                1. A high-level `summary` of the project's main purpose and functionality in a few beginner-friendly sentences%s. Use markdown formatting with **bold** and *italic* text to highlight important concepts.
                2. A list (`relationships`) describing the key interactions between these abstractions. For each relationship, specify:
                    - `from_abstraction`: Index of the source abstraction (e.g., `0 # AbstractionName1`)
                    - `to_abstraction`: Index of the target abstraction (e.g., `1 # AbstractionName2`)
                    - `label`: A brief label for the interaction **in just a few words**%s (e.g., "Manages", "Inherits", "Uses").
                    Ideally the relationship should be backed by one abstraction calling or passing parameters to another.
                    Simplify the relationship and exclude those non-important ones.

                IMPORTANT: Make sure EVERY abstraction is involved in at least ONE relationship (either as source or target). Each abstraction index must appear at least once across all relationships.

                Format the output as YAML:

                ```yaml
                summary: |
                  A brief, simple explanation of the project%s.
                  Can span multiple lines with **bold** and *italic* for emphasis.
                relationships:
                  - from_abstraction: 0 # AbstractionName1
                    to_abstraction: 1 # AbstractionName2
                    label: "Manages"%s
                  - from_abstraction: 2 # AbstractionName3
                    to_abstraction: 0 # AbstractionName1
                    label: "Provides config"%s
                  # ... other relationships
                ```

                Now, provide the YAML output:
                """
                .formatted(p.projectName, listLangNote, p.abstractionListing, p.context,
                        langInstruction, langHint, langHint, langHint, langHint, langHint);

        String response = CallLlm.callLlm(prompt, p.useCache && this.curRetry == 0);

        String yamlStr = YamlUtil.extractYaml(response);
        Object parsed = YamlUtil.load(yamlStr);
        if (!(parsed instanceof Map)) {
            throw new RuntimeException("LLM output is not a dict");
        }
        Map<String, Object> data = YamlUtil.asMap(parsed);
        for (String key : new String[]{"summary", "relationships"}) {
            if (!data.containsKey(key)) {
                throw new RuntimeException("Missing keys in relationship data: " + data);
            }
        }
        if (!(data.get("summary") instanceof String)) {
            throw new RuntimeException("summary is not a string");
        }
        if (!(data.get("relationships") instanceof List)) {
            throw new RuntimeException("relationships is not a list");
        }

        List<Models.Relationship> validated = new ArrayList<>();
        for (Object obj : (List<?>) data.get("relationships")) {
            Map<String, Object> rel = YamlUtil.asMap(obj);
            for (String key : new String[]{"from_abstraction", "to_abstraction", "label"}) {
                if (!rel.containsKey(key)) {
                    throw new RuntimeException("Missing keys in relationship item: " + rel);
                }
            }
            if (!(rel.get("label") instanceof String)) {
                throw new RuntimeException("Relationship label is not a string: " + rel);
            }
            int from = parseIndex(rel.get("from_abstraction"));
            int to = parseIndex(rel.get("to_abstraction"));
            if (!(0 <= from && from < p.numAbstractions && 0 <= to && to < p.numAbstractions)) {
                throw new RuntimeException("Invalid index in relationship: from=" + from + ", to=" + to);
            }
            validated.add(new Models.Relationship(from, to, (String) rel.get("label")));
        }

        System.out.println("Generated project summary and relationship details.");
        return new Models.Relationships((String) data.get("summary"), validated);
    }

    @Override
    public void post(Map<String, Object> shared, Object prepResult, Object execResult) {
        shared.put("relationships", (Models.Relationships) execResult);
    }

    private static int parseIndex(Object o) {
        String s = o.toString();
        if (s.contains("#")) {
            s = s.split("#")[0].trim();
        }
        return Integer.parseInt(s.trim());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> getContentForIndices(List<Object> filesData, List<Integer> indices) {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        for (int i : indices) {
            if (i >= 0 && i < filesData.size()) {
                List<String> entry = (List<String>) filesData.get(i);
                map.put(i + " # " + entry.get(0), entry.get(1));
            }
        }
        return map;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
