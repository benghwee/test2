package io.github.pocketflow.nodes;

import java.util.List;
import java.util.Map;

/** Shared immutable data holders mirroring the Python node outputs. */
public final class Models {

    private Models() {
    }

    /** Abstraction: name, description and the file indices it references. */
    public static final class Abstraction {
        public final String name;
        public final String description;
        public final List<Integer> files;

        public Abstraction(String name, String description, List<Integer> files) {
            this.name = name;
            this.description = description;
            this.files = files;
        }
    }

    /** A relationship between two abstractions. */
    public static final class Relationship {
        public final int from;
        public final int to;
        public final String label;

        public Relationship(int from, int to, String label) {
            this.from = from;
            this.to = to;
            this.label = label;
        }
    }

    /** Project-level relationship result. */
    public static final class Relationships {
        public final String summary;
        public final List<Relationship> details;

        public Relationships(String summary, List<Relationship> details) {
            this.summary = summary;
            this.details = details;
        }
    }

    /** A single chapter to write (BatchNode item). */
    public static final class ChapterItem {
        public int chapterNum;
        public int abstractionIndex;
        public Abstraction abstractionDetails;
        public Map<String, String> relatedFilesContentMap;
        public String projectName;
        public String fullChapterListing;
        public Map<Integer, ChapterMeta> chapterFilenames;
        public ChapterMeta prevChapter;
        public ChapterMeta nextChapter;
        public String language;
        public boolean useCache;

        public static final class ChapterMeta {
            public int num;
            public String name;
            public String filename;

            public ChapterMeta(int num, String name, String filename) {
                this.num = num;
                this.name = name;
                this.filename = filename;
            }
        }
    }
}
