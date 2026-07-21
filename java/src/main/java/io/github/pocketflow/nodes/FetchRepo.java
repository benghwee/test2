package io.github.pocketflow.nodes;

import io.github.pocketflow.Node;
import io.github.pocketflow.utils.CrawlGithubFiles;
import io.github.pocketflow.utils.CrawlLocalFiles;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Mirrors {@code FetchRepo} from nodes.py. */
public class FetchRepo extends Node {

    private static final class Prep {
        String repoUrl;
        String localDir;
        String token;
        Set<String> includePatterns;
        Set<String> excludePatterns;
        int maxFileSize;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object prep(Map<String, Object> shared) {
        String repoUrl = (String) shared.get("repo_url");
        String localDir = (String) shared.get("local_dir");
        String projectName = (String) shared.get("project_name");

        if (projectName == null || projectName.isEmpty()) {
            if (repoUrl != null && !repoUrl.isEmpty()) {
                projectName = repoUrl.substring(repoUrl.lastIndexOf('/') + 1).replace(".git", "");
            } else {
                projectName = new File(localDir).getAbsoluteFile().getName();
            }
            shared.put("project_name", projectName);
        }

        Prep prep = new Prep();
        prep.repoUrl = repoUrl;
        prep.localDir = localDir;
        prep.token = (String) shared.get("github_token");
        prep.includePatterns = (Set<String>) shared.get("include_patterns");
        prep.excludePatterns = (Set<String>) shared.get("exclude_patterns");
        prep.maxFileSize = (int) shared.get("max_file_size");
        return prep;
    }

    @Override
    public Object exec(Object prepResult) {
        Prep p = (Prep) prepResult;
        Map<String, Object> result;
        if (p.repoUrl != null && !p.repoUrl.isEmpty()) {
            System.out.println("Crawling repository: " + p.repoUrl + "...");
            result = CrawlGithubFiles.crawlGithubFiles(p.repoUrl, p.token,
                    p.includePatterns, p.excludePatterns, p.maxFileSize, true);
        } else {
            System.out.println("Crawling directory: " + p.localDir + "...");
            result = CrawlLocalFiles.crawlLocalFiles(p.localDir,
                    p.includePatterns, p.excludePatterns, p.maxFileSize, true);
        }

        @SuppressWarnings("unchecked")
        Map<String, String> filesMap = (Map<String, String>) result.get("files");
        List<Object> filesList = new ArrayList<>();
        for (Map.Entry<String, String> e : filesMap.entrySet()) {
            filesList.add(List.of(e.getKey(), e.getValue()));
        }
        if (filesList.isEmpty()) {
            throw new RuntimeException("Failed to fetch files");
        }
        System.out.println("Fetched " + filesList.size() + " files.");
        return filesList;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void post(Map<String, Object> shared, Object prepResult, Object execResult) {
        shared.put("files", (List<Object>) execResult);
    }
}
