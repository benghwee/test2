package io.github.pocketflow;

import io.github.cdimascio.dotenv.Dotenv;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Mirrors {@code main.py}: CLI entry point for the tutorial generator.
 *
 * <pre>
 *   java -jar tutorial-codebase-knowledge.jar --repo &lt;url&gt; [options]
 *   java -jar tutorial-codebase-knowledge.jar --dir &lt;path&gt; [options]
 * </pre>
 */
public class Main {

    private static final Set<String> DEFAULT_INCLUDE = Set.of(
            "*.py", "*.js", "*.jsx", "*.ts", "*.tsx", "*.go", "*.java", "*.pyi", "*.pyx",
            "*.c", "*.cc", "*.cpp", "*.h", "*.md", "*.rst", "*Dockerfile",
            "*Makefile", "*.yaml", "*.yml");

    private static final Set<String> DEFAULT_EXCLUDE = Set.of(
            "assets/*", "data/*", "images/*", "public/*", "static/*", "temp/*",
            "*docs/*", "*venv/*", "*.venv/*", "*test*", "*tests/*", "*examples/*",
            "v1/*", "*dist/*", "*build/*", "*experimental/*", "*deprecated/*",
            "*misc/*", "*legacy/*", ".git/*", ".github/*", ".next/*", ".vscode/*",
            "*obj/*", "*bin/*", "*node_modules/*", "*.log");

    public static void main(String[] args) {
        Cli cli = parseArgs(args);

        if (cli.repo == null && cli.dir == null) {
            System.err.println("Error: either --repo or --dir is required.");
            System.exit(2);
        }
        if (cli.repo != null && cli.dir != null) {
            System.err.println("Error: specify only one of --repo or --dir.");
            System.exit(2);
        }

        // Load .env (no-op if not present) to populate environment variables.
        try {
            Dotenv.configure().ignoreIfMissing().load();
        } catch (Exception ignored) {
            // dotenv is best-effort; system env still applies.
        }

        String githubToken = null;
        if (cli.repo != null) {
            githubToken = cli.token != null ? cli.token : System.getenv("GITHUB_TOKEN");
            if (githubToken == null || githubToken.isEmpty()) {
                System.out.println("Warning: No GitHub token provided. You might hit rate limits for public repositories.");
            }
        }

        Set<String> include = cli.include != null ? new HashSet<>(cli.include) : new HashSet<>(DEFAULT_INCLUDE);
        Set<String> exclude = cli.exclude != null ? new HashSet<>(cli.exclude) : new HashSet<>(DEFAULT_EXCLUDE);

        Map<String, Object> shared = new LinkedHashMap<>();
        shared.put("repo_url", cli.repo);
        shared.put("local_dir", cli.dir);
        shared.put("project_name", cli.name);
        shared.put("github_token", githubToken);
        shared.put("output_dir", cli.output);
        shared.put("include_patterns", include);
        shared.put("exclude_patterns", exclude);
        shared.put("max_file_size", cli.maxSize);
        shared.put("language", cli.language);
        shared.put("use_cache", !cli.noCache);
        shared.put("max_abstraction_num", cli.maxAbstractions);
        shared.put("files", new java.util.ArrayList<>());
        shared.put("abstractions", new java.util.ArrayList<>());
        shared.put("relationships", null);
        shared.put("chapter_order", new java.util.ArrayList<>());
        shared.put("chapters", new java.util.ArrayList<>());
        shared.put("final_output_dir", null);

        System.out.println("Starting tutorial generation for: "
                + (cli.repo != null ? cli.repo : cli.dir)
                + " in " + capitalize(cli.language) + " language");
        System.out.println("LLM caching: " + (cli.noCache ? "Disabled" : "Enabled"));

        Flow tutorialFlow = CreateTutorialFlow.createTutorialFlow();
        tutorialFlow.run(shared);
    }

    private static Cli parseArgs(String[] args) {
        Cli cli = new Cli();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--repo":
                    cli.repo = args[++i];
                    break;
                case "--dir":
                    cli.dir = args[++i];
                    break;
                case "-n":
                case "--name":
                    cli.name = args[++i];
                    break;
                case "-t":
                case "--token":
                    cli.token = args[++i];
                    break;
                case "-o":
                case "--output":
                    cli.output = args[++i];
                    break;
                case "-i":
                case "--include":
                    cli.include = readList(args, ++i);
                    i += cli.include.size() - 1;
                    break;
                case "-e":
                case "--exclude":
                    cli.exclude = readList(args, ++i);
                    i += cli.exclude.size() - 1;
                    break;
                case "-s":
                case "--max-size":
                    cli.maxSize = Integer.parseInt(args[++i]);
                    break;
                case "--language":
                    cli.language = args[++i];
                    break;
                case "--no-cache":
                    cli.noCache = true;
                    break;
                case "--max-abstractions":
                    cli.maxAbstractions = Integer.parseInt(args[++i]);
                    break;
                default:
                    System.err.println("Unknown argument: " + a);
            }
        }
        return cli;
    }

    private static java.util.List<String> readList(String[] args, int start) {
        java.util.List<String> list = new java.util.ArrayList<>();
        while (start < args.length && !args[start].startsWith("-")) {
            list.add(args[start]);
            start++;
        }
        return list;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static final class Cli {
        String repo;
        String dir;
        String name;
        String token;
        String output = "output";
        java.util.List<String> include;
        java.util.List<String> exclude;
        int maxSize = 100000;
        String language = "english";
        boolean noCache = false;
        int maxAbstractions = 10;
    }
}
