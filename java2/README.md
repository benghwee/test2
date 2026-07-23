# Java Coding Agent - Fixed & Documented

## Overview
This is a unified Java coding agent that consolidates tool-based AI interactions (previously in Go). It supports multiple modes for different use cases.

## Available Modes

| Mode | Tools | Description |
|------|-------|-------------|
| `chat` | read_file, list_files, bash | Default mode - all tools available |
| `read` | read_file | Read file contents only |
| `list_files` | read_file, list_files | List directory contents |
| `bash` | read_file, list_files, bash | Execute shell commands |
| `edit` | read_file, list_files, bash, edit_file | Full file editing capabilities |
| `code_search` | read_file, list_files, bash, code_search | Code pattern searching |

## How to Run

### Prerequisites
- Java 17+ 
- Local LLM server running at `localhost:1234` (default) or set `LOCAL_LLM_BASE_URL`

### Environment Variables
```bash
export LOCAL_LLM_BASE_URL="http://localhost:1234"
export LOCAL_LLM_MODEL="qwen/qwen3.5-9b"
# Optional: export LOCAL_LLM_API_KEY=""
```

### Build and Run
```bash
mvn clean package
java -jar target/coding-agent.jar edit --verbose
```

## Fixed Issues

### 1. ✅ Empty Tools in "chat" Mode
**Problem:** The "chat" mode had no tools registered, causing failures when the LLM tried to use any tool.
**Fix:** Now includes `read_file`, `list_files`, and `bash` tools by default.

### 2. ✅ Path Handling on Windows
**Problem:** Tools failed with paths like `./src/file.txt` or absolute paths.
**Fix:** 
- All tools now handle cross-platform path normalization
- Added fallback for non-existent paths (creates directory structure)
- Improved error messages for common issues (file not found, permission denied)

### 3. ✅ JSON Schema Required Fields
**Problem:** Schemas didn't mark which fields were required, causing LLM confusion.
**Fix:** `SchemaUtil.generateSchema()` now automatically adds "required" array with fields that have no description (required) vs those with descriptions (optional).

### 4. ✅ Error Message Improvements
**Problem:** Generic error messages made debugging difficult.
**Fix:** All tools now provide specific error messages:
- File not found: Clear file path in error
- Command failures: Truncated output to avoid overwhelming the LLM
- Permission issues: Explicit permission denied messages

### 5. ✅ Process Timeout on Windows
**Problem:** Long-running bash commands could hang indefinitely.
**Fix:** Added command execution timeout handling and cleaner output truncation.

## Troubleshooting

### Error: "File not found" or similar path errors
1. Use relative paths from project root (e.g., `src/main/java/Agent.java`)
2. For absolute Windows paths, use forward slashes: `C:/ws/test/project/file.txt`
3. Ensure the file exists before editing (use read_file first to verify)

### Error: LLM calls wrong tool name
- Tool names are fixed: `read_file`, `list_files`, `bash`, `edit_file`, `code_search`
- Don't change these in custom implementations

### Error: BashTool fails on Windows
- Commands must be bash-compatible (no PowerShell-specific syntax)
- Use `cmd.exe /c` for basic commands, or use `bash -c` if bash is installed

### Error: Connection refused to LLM
```bash
# Check if local server is running
curl http://localhost:1234/v1/models

# If not running, start Ollama or LM Studio
```

## Tool Usage Examples

### Read a file
```json
{"path": "src/main/java/Agent.java"}
```

### Edit a file (must be unique match)
```json
{
  "path": "pom.xml",
  "old_str": "<version>1.0.0</version>",
  "new_str": "<version>2.0.0</version>"
}
```

### List files
```json
{"path": ""}  // lists current directory
{"path": "src"}  // lists src directory
```

### Bash command
```json
{"command": "ls -la /tmp"}
```

## Architecture

- **Agent.java**: Main event loop, handles tool execution and conversation
- **OpenAILocalClient.java**: Converts between Anthropic and OpenAI message formats
- **Tool interface**: All tools implement this with `name()`, `description()`, `inputSchema()`, `execute()`
- **SchemaUtil.java**: Generates JSON schemas from Java record classes

## License
MIT
