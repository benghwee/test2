package io.github.pocketflow.nodes;

import io.github.pocketflow.Node;
import io.github.pocketflow.utils.CallGitHubCopilotLlm;
import io.github.pocketflow.utils.CallLlm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Mirrors {@code OrderChapters} from nodes.py. */
public class OrderChapters extends Node {

    public OrderChapters() {
        super(5, 20);
    }

    private static final class Prep {
        String abstractionListing;
        String context;
        int numAbstractions;
        String projectName;
        String listLangNote;
        boolean useCache;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object prep(Map<String, Object> shared) {
        List<Models.Abstraction> abstractions = (List<Models.Abstraction>) shared.get("abstractions");
        Models.Relationships relationships = (Models.Relationships) shared.get("relationships");
        String projectName = (String) shared.get("project_name");
        String language = (String) shared.getOrDefault("language", "english");
        boolean useCache = Boolean.parseBoolean(String.valueOf(
                shared.getOrDefault("use_cache", true)));

        List<String> abstractionInfo = new ArrayList<>();
        for (int i = 0; i < abstractions.size(); i++) {
            abstractionInfo.add("- " + i + " # " + abstractions.get(i).name);
        }

        String summaryNote = "";
        String listLangNote = "";
        if (!language.equalsIgnoreCase("english")) {
            String cap = capitalize(language);
            summaryNote = " (Note: Project Summary might be in " + cap + ")";
            listLangNote = " (Names might be in " + cap + ")";
        }

        StringBuilder context = new StringBuilder("Project Summary").append(summaryNote).append(":\n")
                .append(relationships.summary).append("\n\n");
        context.append("Relationships (Indices refer to abstractions above):\n");
        for (Models.Relationship rel : relationships.details) {
            String fromName = abstractions.get(rel.from).name;
            String toName = abstractions.get(rel.to).name;
            context.append("- From ").append(rel.from).append(" (").append(fromName)
                    .append(") to ").append(rel.to).append(" (").append(toName)
                    .append("): ").append(rel.label).append("\n");
        }

        Prep prep = new Prep();
        prep.abstractionListing = String.join("\n", abstractionInfo);
        prep.context = context.toString();
        prep.numAbstractions = abstractions.size();
        prep.projectName = projectName;
        prep.listLangNote = listLangNote;
        prep.useCache = useCache;
        return prep;
    }

    @Override
    public Object exec(Object prepResult) {
        Prep p = (Prep) prepResult;
        System.out.println("Determining chapter order using LLM...");

        String prompt = """
                Given the following project abstractions and their relationships for the project `%s`:

                Abstractions (Index # Name)%s:
                %s

                Context about relationships and project summary:
                %s

                If you are going to make a tutorial for `%s`, what is the best order to explain these abstractions, from first to last?
                Ideally, first explain those that are the most important or foundational, perhaps user-facing concepts or entry points. Then move to more detailed, lower-level implementation details or supporting concepts.

                Output the ordered list of abstraction indices, including the name in a comment for clarity. Use the format `idx # AbstractionName`.

                ```yaml
                - 2 # FoundationalConcept
                - 0 # CoreClassA
                - 1 # CoreClassB (uses CoreClassA)
                - ...
                ```

                Now, provide the YAML output:
                """
                .formatted(p.projectName, p.listLangNote, p.abstractionListing, p.context, p.projectName);

        String response = CallLlm.callLlm(prompt, p.useCache && this.curRetry == 0);

        String yamlStr = YamlUtil.extractYaml(response);
        Object parsed = YamlUtil.load(yamlStr);
        if (!(parsed instanceof List)) {
            throw new RuntimeException("LLM output is not a list");
        }

        List<Integer> ordered = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (Object obj : YamlUtil.asList(parsed)) {
            int idx = parseIndex(obj);
            if (!(0 <= idx && idx < p.numAbstractions)) {
                throw new RuntimeException("Invalid index " + idx + " in ordered list");
            }
            if (seen.contains(idx)) {
                throw new RuntimeException("Duplicate index " + idx + " in ordered list");
            }
            ordered.add(idx);
            seen.add(idx);
        }
        if (ordered.size() != p.numAbstractions) {
            Set<Integer> missing = new HashSet<>();
            for (int i = 0; i < p.numAbstractions; i++) {
                missing.add(i);
            }
            missing.removeAll(seen);
            throw new RuntimeException("Ordered list length (" + ordered.size()
                    + ") does not match number of abstractions (" + p.numAbstractions
                    + "). Missing indices: " + missing);
        }

        System.out.println("Determined chapter order (indices): " + ordered);
        return ordered;
    }

    @Override
    public void post(Map<String, Object> shared, Object prepResult, Object execResult) {
        @SuppressWarnings("unchecked")
        List<Integer> order = (List<Integer>) execResult;
        shared.put("chapter_order", order);
    }

    private static int parseIndex(Object o) {
        String s = o.toString();
        if (s.contains("#")) {
            s = s.split("#")[0].trim();
        }
        return Integer.parseInt(s.trim());
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
