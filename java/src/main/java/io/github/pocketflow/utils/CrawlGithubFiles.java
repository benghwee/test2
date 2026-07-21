package io.github.pocketflow.utils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Reimplementation of {@code utils/crawl_github_files.py}.
 *
 * <p>Supports GitHub HTTPS URLs (parsed via the API) and SSH URLs
 * ({@code git@...}) which are cloned with JGit into a temp directory.
 * Returns {@code {"files": Map<path, content>, "stats": Map}}. Mirrors the
 * include/exclude pattern matching and size limits of the Python version.
 */
public final class CrawlGithubFiles {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private CrawlGithubFiles() {
    }

    public static Map<String, Object> crawlGithubFiles(
            String repoUrl,
            String token,
            Set<String> includePatterns,
            Set<String> excludePatterns,
            int maxFileSize,
            boolean useRelativePaths) {

        if (repoUrl.startsWith("git@") || repoUrl.endsWith(".git")) {
            return crawlSsh(repoUrl, includePatterns, excludePatterns, maxFileSize, useRelativePaths);
        }
        return crawlHttps(repoUrl, token, includePatterns, excludePatterns, maxFileSize, useRelativePaths);
    }

    private static boolean shouldInclude(String filePath, String fileName,
                                          Set<String> includePatterns, Set<String> excludePatterns) {
        boolean includeFile;
        if (includePatterns == null || includePatterns.isEmpty()) {
            includeFile = true;
        } else {
            includeFile = false;
            for (String p : includePatterns) {
                if (fnMatch(fileName, p)) {
                    includeFile = true;
                    break;
                }
            }
        }
        if (excludePatterns != null && !excludePatterns.isEmpty() && includeFile) {
            for (String p : excludePatterns) {
                if (fnMatch(filePath, p) || fnMatch(fileName, p)) {
                    return false;
                }
            }
        }
        return includeFile;
    }

    /** Public wrapper used by other crawlers. */
    public static boolean fnMatchStatic(String name, String pattern) {
        return fnMatch(name, pattern);
    }

    /** Minimal fnmatch-style glob: supports {@code *} and {@code ?}. */
    private static boolean fnMatch(String name, String pattern) {
        StringBuilder sb = new StringBuilder();
        for (char c : pattern.toCharArray()) {
            if (c == '*') {
                sb.append(".*");
            } else if (c == '?') {
                sb.append('.');
            } else if ("+()[]{}$^.\\|".indexOf(c) >= 0) {
                sb.append('\\').append(c);
            } else {
                sb.append(c);
            }
        }
        return Pattern.compile(sb.toString()).matcher(name).matches();
    }

    private static Map<String, Object> crawlSsh(String repoUrl,
                                                Set<String> includePatterns,
                                                Set<String> excludePatterns,
                                                int maxFileSize,
                                                boolean useRelativePaths) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, String> files = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();
        try {
            Path tmp = Files.createTempDirectory("gh_clone");
            org.eclipse.jgit.api.Git.cloneRepository()
                    .setURI(repoUrl)
                    .setDirectory(tmp.toFile())
                    .call()
                    .close();
            Files.walk(tmp).forEach(p -> {
                if (Files.isRegularFile(p)) {
                    try {
                        String rel = tmp.relativize(p).toString().replace('\\', '/');
                        long size = Files.size(p);
                        if (size > maxFileSize) {
                            skipped.add(rel + " (size)");
                            return;
                        }
                        if (!shouldInclude(rel, p.getFileName().toString(),
                                includePatterns, excludePatterns)) {
                            return;
                        }
                        files.put(rel, Files.readString(p));
                        System.out.println("Added " + rel);
                    } catch (IOException e) {
                        System.err.println("Failed to read " + p + ": " + e.getMessage());
                    }
                }
            });
            deleteRecursively(tmp);
        } catch (Exception e) {
            System.err.println("Error cloning repo: " + e.getMessage());
            result.put("files", files);
            result.put("stats", Map.of("error", e.getMessage()));
            return result;
        }
        result.put("files", files);
        result.put("stats", Map.of("downloaded_count", files.size(),
                "skipped_count", skipped.size(), "source", "ssh_clone"));
        return result;
    }

    private static Map<String, Object> crawlHttps(String repoUrl,
                                                  String token,
                                                  Set<String> includePatterns,
                                                  Set<String> excludePatterns,
                                                  int maxFileSize,
                                                  boolean useRelativePaths) {
        Map<String, String> files = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();

        String[] parts = URI.create(repoUrl).getPath().replaceAll("^/|/$", "").split("/");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Invalid GitHub URL: " + repoUrl);
        }
        String owner = parts[0];
        String repo = parts[1];

        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/vnd.github.v3+json");
        if (token != null && !token.isEmpty()) {
            headers.put("Authorization", "token " + token);
        }

        String ref = null;
        String specificPath = "";
        if (parts.length > 2 && "tree".equals(parts[2])) {
            // Re-fetch branches to resolve ref (simplified: try common branches).
            ref = resolveRef(owner, repo, token, headers, parts);
            int idx = 5;
            if (ref != null && ref.contains("/")) {
                idx = 4;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = idx; i < parts.length; i++) {
                sb.append(parts[i]);
                if (i < parts.length - 1) {
                    sb.append('/');
                }
            }
            specificPath = sb.toString();
        }

        fetchContents(owner, repo, ref, specificPath, headers,
                includePatterns, excludePatterns, maxFileSize, useRelativePaths, files, skipped);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("files", files);
        result.put("stats", Map.of(
                "downloaded_count", files.size(),
                "skipped_count", skipped.size(),
                "base_path", useRelativePaths ? specificPath : "",
                "include_patterns", includePatterns,
                "exclude_patterns", excludePatterns));
        return result;
    }

    private static String resolveRef(String owner, String repo, String token,
                                     Map<String, String> headers, String[] parts) {
        // Try the path segment right after "tree" as a branch/commit.
        String candidate = parts[3];
        boolean ok = checkTree(owner, repo, candidate, token, headers);
        if (ok) {
            return candidate;
        }
        return null;
    }

    private static boolean checkTree(String owner, String repo, String tree,
                                     String token, Map<String, String> headers) {
        String url = "https://api.github.com/repos/" + owner + "/" + repo + "/git/trees/" + tree;
        try {
            HttpResponse<String> r = HTTP.send(
                    request(url, headers, Map.of()), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private static HttpRequest request(String url, Map<String, String> headers, Map<String, String> params) {
        StringBuilder u = new StringBuilder(url);
        if (!params.isEmpty()) {
            u.append('?');
            for (Map.Entry<String, String> e : params.entrySet()) {
                u.append(e.getKey()).append('=').append(e.getValue()).append('&');
            }
            u.deleteCharAt(u.length() - 1);
        }
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(u.toString()))
                .timeout(Duration.ofSeconds(30));
        headers.forEach(b::header);
        return b.GET().build();
    }

    @SuppressWarnings("unchecked")
    private static void fetchContents(String owner, String repo, String ref, String path,
                                      Map<String, String> headers,
                                      Set<String> includePatterns, Set<String> excludePatterns,
                                      int maxFileSize, boolean useRelativePaths,
                                      Map<String, String> files, List<String> skipped) {
        String url = "https://api.github.com/repos/" + owner + "/" + repo + "/contents/" + path;
        Map<String, String> params = new HashMap<>();
        if (ref != null) {
            params.put("ref", ref);
        }
        try {
            HttpResponse<String> resp = HTTP.send(request(url, headers, params),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                System.err.println("Error fetching " + path + ": " + resp.statusCode());
                return;
            }
            com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
            JsonNode contents = m.readTree(resp.body());
            if (!contents.isArray()) {
                contents = m.createArrayNode().add(contents);
            }
            for (JsonNode item : contents) {
                String itemPath = item.path("path").asText();
                String relPath;
                if (useRelativePaths && !specificPathEmpty(path) && itemPath.startsWith(path)) {
                    relPath = itemPath.substring(path.length()).replaceAll("^/", "");
                } else {
                    relPath = itemPath;
                }
                String type = item.path("type").asText();
                if ("file".equals(type)) {
                    if (!shouldInclude(relPath, item.path("name").asText(),
                            includePatterns, excludePatterns)) {
                        continue;
                    }
                    long size = item.path("size").asLong(0);
                    if (size > maxFileSize) {
                        skipped.add(itemPath);
                        continue;
                    }
                    String content = downloadFile(item, headers);
                    if (content != null) {
                        files.put(relPath, content);
                        System.out.println("Downloaded: " + relPath + " (" + size + " bytes)");
                    }
                } else if ("dir".equals(type)) {
                    if (excludePatterns != null) {
                        boolean excluded = false;
                        for (String p : excludePatterns) {
                            if (fnMatch(itemPath, p) || fnMatch(relPath, p)) {
                                excluded = true;
                                break;
                            }
                        }
                        if (excluded) {
                            continue;
                        }
                    }
                    fetchContents(owner, repo, ref, itemPath, headers,
                            includePatterns, excludePatterns, maxFileSize, useRelativePaths, files, skipped);
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to fetch contents of " + path + ": " + e.getMessage());
        }
    }

    private static boolean specificPathEmpty(String path) {
        return path == null || path.isEmpty();
    }

    private static String downloadFile(JsonNode item, Map<String, String> headers) throws Exception {
        String downloadUrl = item.path("download_url").asText("");
        if (downloadUrl != null && !downloadUrl.isEmpty()) {
            HttpResponse<String> r = HTTP.send(request(downloadUrl, headers, Map.of()),
                    HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 ? r.body() : null;
        }
        String apiUrl = item.path("url").asText();
        HttpResponse<String> r = HTTP.send(request(apiUrl, headers, Map.of()),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            return null;
        }
        JsonNode data = new com.fasterxml.jackson.databind.ObjectMapper().readTree(r.body());
        if ("base64".equals(data.path("encoding").asText()) && data.has("content")) {
            return new String(Base64.getDecoder().decode(data.path("content").asText()));
        }
        return null;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (Files.exists(dir)) {
            Files.walk(dir)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }
}
