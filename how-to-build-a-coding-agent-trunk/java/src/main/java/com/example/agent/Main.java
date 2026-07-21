package com.example.agent;

import com.example.agent.tools.BashTool;
import com.example.agent.tools.CodeSearchTool;
import com.example.agent.tools.EditFileTool;
import com.example.agent.tools.ListFilesTool;
import com.example.agent.tools.ReadFileTool;

import java.util.ArrayList;
import java.util.List;

/**
 * Entry point. Replaces the six separate Go binaries with a single app that
 * selects a tool set by subcommand:
 *   chat | read | list_files | bash | edit | code_search
 * plus a global {@code --verbose} flag.
 */
public final class Main {
    public static void main(String[] args) {
        boolean verbose = false;
        String mode = "edit";//"chat";
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--verbose".equals(a)) {
                verbose = true;
            } else if (a.startsWith("--")) {
                System.err.println("Unknown flag: " + a);
                System.exit(2);
            } else {
                mode = a;
            }
        }
        Log.setVerbose(verbose);

        List<Tool> tools = new ArrayList<>();
        switch (mode) {
            case "chat":
                break;
            case "read":
                tools.add(new ReadFileTool());
                break;
            case "list_files":
                tools.add(new ReadFileTool());
                tools.add(new ListFilesTool());
                break;
            case "bash":
                tools.add(new ReadFileTool());
                tools.add(new ListFilesTool());
                tools.add(new BashTool());
                break;
            case "edit":
                tools.add(new ReadFileTool());
                tools.add(new ListFilesTool());
                tools.add(new BashTool());
                tools.add(new EditFileTool());
                break;
            case "code_search":
                tools.add(new ReadFileTool());
                tools.add(new ListFilesTool());
                tools.add(new BashTool());
                tools.add(new CodeSearchTool());
                break;
            default:
                System.err.println("Unknown mode: " + mode
                        + " (expected chat|read|list_files|bash|edit|code_search)");
                System.exit(2);
        }

        try {
            OpenAILocalClient client = new OpenAILocalClient();
            new Agent(client, tools).run();
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            if (verbose) {
                e.printStackTrace();
            }
            System.exit(1);
        }
    }
}
