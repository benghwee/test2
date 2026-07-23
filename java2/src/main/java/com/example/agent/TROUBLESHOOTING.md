# Troubleshooting Guide - Java Coding Agent

## Common Errors and Solutions

### Error 1: "tool not found" or empty tool list
**Cause:** Mode set to "chat" but no tools registered (FIXED in commit)

**Solution:** The agent now includes default tools for all modes. If you see this error, restart the agent.

---

### Error 2: "File not found" errors when using read_file or edit_file
**Cause:** LLM provides wrong path format (e.g., absolute Windows paths with backslashes)

**Solution:** 
- Use forward slashes on Windows: `C:/ws/project/file.txt` instead of `C:\ws\project\file.txt`
- Always use relative paths from project root: `src/main/java/Agent.java`

---

### Error 3: "Command not found" in bash tool
**Cause:** LLM tries to run Windows PowerShell commands on Unix bash

**Solution:** Only use standard bash/POSIX compatible commands (ls, cat, grep, etc.)

---

### Error 4: Connection refused to LLM
**Cause:** Local LLM server not running

**Solution:**
```bash
# Check if server is running
curl http://localhost:1234/v1/models

# If not running, start Ollama or LM Studio
# For Ollama: ollama serve
# For LM Studio: open app and enable server
```

---

### Error 5: Tool arguments missing required fields
**Cause:** LLM doesn't send all required parameters

**Solution:** The schema now marks required fields (those without descriptions). Check your LLM's system prompt to ensure it understands which fields are mandatory.

---

## Testing Checklist

Before using the agent:
- [ ] Local LLM is running at `localhost:1234`
- [ ] Build succeeded: `mvn clean package`
- [ ] JAR file exists in `target/coding-agent.jar`

Run with verbose flag for debugging:
```bash
java -jar target/coding-agent.jar edit --verbose
```

---

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| LOCAL_LLM_BASE_URL | http://localhost:1234 | LLM server URL |
| LOCAL_LLM_MODEL | qwen/qwen3.5-9b | Model name to use |
| LOCAL_LLM_API_KEY | (empty) | Optional API key if needed |

---

## Available Modes

Use `--verbose` flag to see detailed logging:

```bash
java -jar target/coding-agent.jar chat --verbose       # All tools enabled
java -jar target/coding-agent.jar read --verbose      # Read only
java -jar target/coding-agent.jar list_files --verbose
java -jar target/coding-agent.jar bash --verbose
java -jar target/coding-agent.jar edit --verbose
java -jar target/coding-agent.jar code_search --verbose
```

---

## Tool Names (Don't Change)

The following tool names are registered by the agent:
- `read_file` - Read file contents
- `list_files` - List directory contents
- `bash` - Execute shell commands
- `edit_file` - Edit text files
- `code_search` - Search for code patterns

These names must match exactly in LLM tool calls.

---

## Sample Session

```
You: ls
tool: bash({"command": "ls"})
result: [".devenv", ".git", "src", "pom.xml", "README.md"]

You: cat pom.xml
tool: read_file({"path": "pom.xml"})
result: <?xml version="1.0" encoding="UTF-8"?>
<project>...

You: edit pom.xml to change version to 2.0.0
tool: edit_file({"path": "pom.xml", "old_str": "<version>1.0.0</version>", "new_str": "<version>2.0.0</version>"})
result: OK

Claude: The version has been updated to 2.0.0 in pom.xml
```

---

## Getting Help

For more information, see:
- `src/main/java/com/example/agent/README.md` - Full documentation
- `src/main/java/com/example/agent/CHANGES.md` - What was fixed
- This file for troubleshooting tips
