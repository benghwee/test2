package io.github.pocketflow.nodes;

import io.github.pocketflow.BatchNode;
import io.github.pocketflow.utils.CallGitHubCopilotLlm;
import io.github.pocketflow.utils.CallLlm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Mirrors {@code WriteChapters} (BatchNode) from nodes.py. */
public class WriteChapters extends BatchNode {

    /** Temporary storage across exec calls for progressive chapter summaries. */
    private List<String> chaptersWrittenSoFar;

    public WriteChapters() {
        super(5, 20);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Object> prep(Map<String, Object> shared) {
        List<Integer> chapterOrder = (List<Integer>) shared.get("chapter_order");
        List<Models.Abstraction> abstractions = (List<Models.Abstraction>) shared.get("abstractions");
        List<Object> filesData = (List<Object>) shared.get("files");
        String projectName = (String) shared.get("project_name");
        String language = (String) shared.getOrDefault("language", "english");
        boolean useCache = Boolean.parseBoolean(String.valueOf(
                shared.getOrDefault("use_cache", true)));

        this.chaptersWrittenSoFar = new ArrayList<>();

        Map<Integer, Models.ChapterItem.ChapterMeta> chapterFilenames = new LinkedHashMap<>();
        List<String> allChapters = new ArrayList<>();
        for (int i = 0; i < chapterOrder.size(); i++) {
            int abstractionIndex = chapterOrder.get(i);
            if (abstractionIndex >= 0 && abstractionIndex < abstractions.size()) {
                int chapterNum = i + 1;
                String chapterName = abstractions.get(abstractionIndex).name;
                String safeName = sanitize(chapterName);
                String filename = String.format("%02d_%s.md", chapterNum, safeName);
                allChapters.add(chapterNum + ". [" + chapterName + "](" + filename + ")");
                chapterFilenames.put(abstractionIndex,
                        new Models.ChapterItem.ChapterMeta(chapterNum, chapterName, filename));
            }
        }
        String fullChapterListing = String.join("\n", allChapters);

        List<Object> items = new ArrayList<>();
        for (int i = 0; i < chapterOrder.size(); i++) {
            int abstractionIndex = chapterOrder.get(i);
            if (abstractionIndex < 0 || abstractionIndex >= abstractions.size()) {
                System.out.println("Warning: Invalid abstraction index " + abstractionIndex
                        + " in chapter_order. Skipping.");
                continue;
            }
            Models.Abstraction details = abstractions.get(abstractionIndex);
            Map<String, String> relatedFiles = getContentForIndices(filesData, details.files);

            Models.ChapterItem.ChapterMeta prev = null;
            if (i > 0) {
                prev = chapterFilenames.get(chapterOrder.get(i - 1));
            }
            Models.ChapterItem.ChapterMeta next = null;
            if (i < chapterOrder.size() - 1) {
                next = chapterFilenames.get(chapterOrder.get(i + 1));
            }

            Models.ChapterItem item = new Models.ChapterItem();
            item.chapterNum = i + 1;
            item.abstractionIndex = abstractionIndex;
            item.abstractionDetails = details;
            item.relatedFilesContentMap = relatedFiles;
            item.projectName = projectName;
            item.fullChapterListing = fullChapterListing;
            item.chapterFilenames = chapterFilenames;
            item.prevChapter = prev;
            item.nextChapter = next;
            item.language = language;
            item.useCache = useCache;
            items.add(item);
        }

        System.out.println("Preparing to write " + items.size() + " chapters...");
        return items;
    }

    @Override
    public Object exec(Object itemObj) {
        Models.ChapterItem item = (Models.ChapterItem) itemObj;
        String abstractionName = item.abstractionDetails.name;
        String abstractionDescription = item.abstractionDetails.description;
        int chapterNum = item.chapterNum;
        String projectName = item.projectName;
        String language = item.language;
        System.out.println("Writing chapter " + chapterNum + " for: " + abstractionName + " using LLM...");

        String fileContext = item.relatedFilesContentMap.entrySet().stream()
                .map(e -> "--- File: " + e.getKey().split("# ")[1] + " ---\n" + e.getValue())
                .collect(Collectors.joining("\n\n"));

        String previousSummary = String.join("\n---\n", chaptersWrittenSoFar);

        String langInstruction = "";
        String conceptDetailsNote = "";
        String structureNote = "";
        String prevSummaryNote = "";
        String instructionLangNote = "";
        String mermaidLangNote = "";
        String codeCommentNote = "";
        String linkLangNote = "";
        String toneNote = "";
        if (!language.equalsIgnoreCase("english")) {
            String cap = capitalize(language);
            langInstruction = "IMPORTANT: Write this ENTIRE tutorial chapter in **" + cap
                    + "**. Some input context (like concept name, description, chapter list, previous summary) might already be in "
                    + cap + ", but you MUST translate ALL other generated content including explanations, examples, technical terms, and potentially code comments into "
                    + cap + ". DO NOT use English anywhere except in code syntax, required proper nouns, or when specified. The entire output MUST be in " + cap + ".\n\n";
            conceptDetailsNote = " (Note: Provided in " + cap + ")";
            structureNote = " (Note: Chapter names might be in " + cap + ")";
            prevSummaryNote = " (Note: This summary might be in " + cap + ")";
            instructionLangNote = " (in " + cap + ")";
            mermaidLangNote = " (Use " + cap + " for labels/text if appropriate)";
            codeCommentNote = " (Translate to " + cap + " if possible, otherwise keep minimal English for clarity)";
            linkLangNote = " (Use the " + cap + " chapter title from the structure above)";
            toneNote = " (appropriate for " + cap + " readers)";
        }

        String prompt = """
                %sWrite a very beginner-friendly tutorial chapter (in Markdown format) for the project `%s` about the concept: "%s". This is Chapter %d.

                Concept Details%s:
                - Name: %s
                - Description:
                %s

                Complete Tutorial Structure%s:
                %s

                Context from previous chapters%s:
                %s

                Relevant Code Snippets (Code itself remains unchanged):
                %s

                Instructions for the chapter (Generate content in %s unless specified otherwise):
                - Start with a clear heading (e.g., `# Chapter %d: %s`). Use the provided concept name.

                - If this is not the first chapter, begin with a brief transition from the previous chapter%s, referencing it with a proper Markdown link using its name%s.

                - Begin with a high-level motivation explaining what problem this abstraction solves%s. Start with a central use case as a concrete example. The whole chapter should guide the reader to understand how to solve this use case. Make it very minimal and friendly to beginners.

                - If the abstraction is complex, break it down into key concepts. Explain each concept one-by-one in a very beginner-friendly way%s.

                - Explain how to use this abstraction to solve the use case%s. Give example inputs and outputs for code snippets (if the output isn't values, describe at a high level what will happen%s).

                - Each code block should be BELOW 10 lines! If longer code blocks are needed, break them down into smaller pieces and walk through them one-by-one. Aggressively simplify the code to make it minimal. Use comments%s to skip non-important implementation details. Each code block should have a beginner friendly explanation right after it%s.

                - Describe the internal implementation to help understand what's under the hood%s. First provide a non-code or code-light walkthrough on what happens step-by-step when the abstraction is called%s. It's recommended to use a simple sequenceDiagram with a dummy example - keep it minimal with at most 5 participants to ensure clarity. If participant name has space, use: `participant QP as Query Processing`. %s.

                - Then dive deeper into code for the internal implementation with references to files. Provide example code blocks, but make them similarly simple and beginner-friendly. Explain%s.

                - IMPORTANT: When you need to refer to other core abstractions covered in other chapters, ALWAYS use proper Markdown links like this: [Chapter Title](filename.md). Use the Complete Tutorial Structure above to find the correct filename and the chapter title%s. Translate the surrounding text.

                - Use mermaid diagrams to illustrate complex concepts (```mermaid``` format). %s.

                - Heavily use analogies and examples throughout%s to help beginners understand.

                - End the chapter with a brief conclusion that summarizes what was learned%s and provides a transition to the next chapter%s. If there is a next chapter, use a proper Markdown link: [Next Chapter Title](next_chapter_filename)%s.

                - Ensure the tone is welcoming and easy for a newcomer to understand%s.

                - Output *only* the Markdown content for this chapter.

                Now, directly provide a super beginner-friendly Markdown output (DON'T need ```markdown``` tags):
                """
                .formatted(langInstruction, projectName, abstractionName, chapterNum,
                        conceptDetailsNote, abstractionName, abstractionDescription,
                        structureNote, item.fullChapterListing,
                        prevSummaryNote, (previousSummary.isEmpty() ? "This is the first chapter." : previousSummary),
                        (fileContext.isEmpty() ? "No specific code snippets provided for this abstraction." : fileContext),
                        capitalize(language), chapterNum, abstractionName,
                        instructionLangNote, linkLangNote,
                        instructionLangNote, instructionLangNote, instructionLangNote,
                        codeCommentNote, instructionLangNote,
                        instructionLangNote, instructionLangNote, mermaidLangNote,
                        instructionLangNote, linkLangNote, mermaidLangNote,
                        instructionLangNote, instructionLangNote, instructionLangNote,
                        linkLangNote, instructionLangNote, toneNote);

        String chapterContent = CallLlm.callLlm(prompt, item.useCache && this.curRetry == 0);

        String actualHeading = "# Chapter " + chapterNum + ": " + abstractionName;
        if (!chapterContent.trim().startsWith("# Chapter " + chapterNum)) {
            List<String> lines = new ArrayList<>(List.of(chapterContent.trim().split("\n")));
            if (!lines.isEmpty() && lines.get(0).trim().startsWith("#")) {
                lines.set(0, actualHeading);
                chapterContent = String.join("\n", lines);
            } else {
                chapterContent = actualHeading + "\n\n" + chapterContent;
            }
        }

        chaptersWrittenSoFar.add(chapterContent);
        return chapterContent;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void post(Map<String, Object> shared, Object prepResult, Object execResult) {
        shared.put("chapters", (List<Object>) execResult);
        this.chaptersWrittenSoFar = null;
        System.out.println("Finished writing " + ((List<?>) execResult).size() + " chapters.");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> getContentForIndices(List<Object> filesData, List<Integer> indices) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i : indices) {
            if (i >= 0 && i < filesData.size()) {
                List<String> entry = (List<String>) filesData.get(i);
                map.put(i + " # " + entry.get(0), entry.get(1));
            }
        }
        return map;
    }

    private static String sanitize(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) {
            sb.append(Character.isLetterOrDigit(c) ? c : '_');
        }
        return sb.toString().toLowerCase();
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
