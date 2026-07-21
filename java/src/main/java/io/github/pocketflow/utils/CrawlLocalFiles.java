package io.github.pocketflow.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Reimplementation of {@code utils/crawl_local_files.py}.
 *
 * <p>Walks a local directory, honoring {@code .gitignore} (basic gitwildmatch
 * via a small matcher), include/exclude patterns and a max file size. Returns
 * {@code {"files": Map<relativePath, content>}}.
 */
public final class CrawlLocalFiles {

    private CrawlLocalFiles() {
    }

    public static Map<String, Object> crawlLocalFiles(
            String directory,
            Set<String> includePatterns,
            Set<String> excludePatterns,
            int maxFileSize,
            boolean useRelativePaths) {

        Path dir = Paths.get(directory);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Directory does not exist: " + directory);
        }

        Map<String, String> files = new LinkedHashMap<>();
        GitignoreSpec gitignore = GitignoreSpec.load(dir);

        List<Path> allFiles = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile).forEach(allFiles::add);
        } catch (IOException e) {
            throw new RuntimeException("Failed to walk directory: " + e.getMessage(), e);
        }

        int total = allFiles.size();
        int processed = 0;
        for (Path filePath : allFiles) {
            processed++;
            String relPath = dir.relativize(filePath).toString().replace('\\', '/');
            String status = "processed";

            boolean excluded = false;
            if (gitignore.matches(relPath)) {
                excluded = true;
            }
            if (!excluded && excludePatterns != null) {
                for (String p : excludePatterns) {
                    if (CrawlGithubFiles.fnMatchStatic(relPath, p)) {
                        excluded = true;
                        break;
                    }
                }
            }

            boolean included;
            if (includePatterns == null || includePatterns.isEmpty()) {
                included = true;
            } else {
                included = false;
                for (String p : includePatterns) {
                    if (CrawlGithubFiles.fnMatchStatic(relPath, p)) {
                        included = true;
                        break;
                    }
                }
            }

            if (!included || excluded) {
                status = "skipped (excluded)";
                printProgress(processed, total, relPath, status);
                continue;
            }

            try {
                long size = Files.size(filePath);
                if (maxFileSize > 0 && size > maxFileSize) {
                    status = "skipped (size limit)";
                    printProgress(processed, total, relPath, status);
                    continue;
                }
                files.put(relPath, Files.readString(filePath));
            } catch (IOException e) {
                status = "skipped (read error)";
            }
            printProgress(processed, total, relPath, status);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("files", files);
        return result;
    }

    private static void printProgress(int processed, int total, String relPath, String status) {
        int pct = total > 0 ? (processed * 100) / total : 100;
        System.out.println("Progress: " + processed + "/" + total + " (" + pct + "%) "
                + relPath + " [" + status + "]");
    }

    /** Very small gitwildmatch matcher supporting {@code *}, {@code ?}, {@code /}. */
    private static final class GitignoreSpec {
        private final List<String> patterns = new ArrayList<>();

        static GitignoreSpec load(Path dir) {
            GitignoreSpec spec = new GitignoreSpec();
            Path gi = dir.resolve(".gitignore");
            if (Files.exists(gi)) {
                try {
                    for (String line : Files.readAllLines(gi)) {
                        line = line.trim();
                        if (!line.isEmpty() && !line.startsWith("#")) {
                            spec.patterns.add(line);
                        }
                    }
                } catch (IOException e) {
                    System.err.println("Warning: could not read .gitignore: " + e.getMessage());
                }
            }
            return spec;
        }

        boolean matches(String relPath) {
            for (String p : patterns) {
                String pat = p;
                if (pat.startsWith("/")) {
                    pat = pat.substring(1);
                }
                if (CrawlGithubFiles.fnMatchStatic(relPath, pat)
                        || CrawlGithubFiles.fnMatchStatic(relPath, "**/" + pat)) {
                    return true;
                }
            }
            return false;
        }
    }
}
