package io.github.pocketflow.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.copilot.sdk.CopilotClient;
import com.github.copilot.sdk.SystemMessageMode;
import com.github.copilot.sdk.generated.AssistantMessageEvent;
import com.github.copilot.sdk.json.CopilotClientOptions;
import com.github.copilot.sdk.json.MessageOptions;
import com.github.copilot.sdk.json.PermissionHandler;
import com.github.copilot.sdk.json.ProviderConfig;
import com.github.copilot.sdk.json.SessionConfig;
import com.github.copilot.sdk.json.SystemMessageConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public final  class CallGitHubCopilotLlm {
    private static final Logger logger = Logger.getLogger("llm_logger");
    private static final String CACHE_FILE = "llm_cache.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static {
        try {
            String logDir = System.getenv().getOrDefault("LOG_DIR", "logs");
            Files.createDirectories(Paths.get(logDir));
            String logFile = logDir + "/llm_calls_"
                    + LocalDate.now().toString().replace("-", "") + ".log";

            FileHandler fh = new FileHandler(logFile, true);
            fh.setFormatter(new Formatter() {
                @Override
                public String format(LogRecord record) {
                    return record.getLevel() + " " + record.getMessage() + "\n";
                }
            });

            logger.addHandler(fh);
            logger.setLevel(Level.INFO);
            logger.setUseParentHandlers(false);
        } catch (IOException e) {
            System.err.println("Warning: could not set up LLM logger: " + e.getMessage());
        }
    }

    private CallGitHubCopilotLlm() {
    }

    public static String callLlm(String prompt) {
        return callLlm(prompt, true);
    }

    public static String callLlm(String prompt, boolean useCache) {
        logger.info("PROMPT: " + prompt);

        if (useCache) {
            Map<String, String> cache = loadCache();
            if (cache.containsKey(prompt)) {
                String cached = cache.get(prompt);
                logger.info("RESPONSE (cached): " + cached);
                return cached;
            }
        }

        String response = callWithCopilotSdk(prompt);
        logger.info("RESPONSE: " + response);

        if (useCache) {
            Map<String, String> cache = loadCache();
            cache.put(prompt, response);
            saveCache(cache);
        }

        return response;
    }

    private static String callWithCopilotSdk(String prompt) {
        try {
            System.setProperty("COPILOT_USE_CLI_PROXY",
                    System.getenv().getOrDefault("COPILOT_USE_CLI_PROXY", "true"));

            String allowTools = System.getenv("COPILOT_ALLOW_TOOLS");
            if (allowTools != null && !allowTools.isBlank()) {
                System.setProperty("COPILOT_ALLOW_TOOLS", allowTools);
            }

            CopilotClientOptions options = new CopilotClientOptions()
                    .setUseLoggedInUser(Boolean.parseBoolean(
                            System.getenv().getOrDefault("COPILOT_USE_LOGGED_IN_USER", "true")));

            try (CopilotClient client = new CopilotClient(options)) {
                SystemMessageConfig systemMessage = new SystemMessageConfig()
                        .setMode(SystemMessageMode.REPLACE)
                        .setContent(System.getenv().getOrDefault(
                                "COPILOT_SYSTEM_PROMPT",
                                "You are a helpful assistant."));

                SessionConfig sessionConfig = new SessionConfig()
                        .setSystemMessage(systemMessage)
                        .setOnPermissionRequest(PermissionHandler.APPROVE_ALL);

                String model = System.getenv("COPILOT_MODEL");
                model= "nvidia/nemotron-3-ultra-550b-a55b:free";
                if (model != null && !model.isBlank()) {
                    sessionConfig.setModel(model);
                }

                String providerType = System.getenv("COPILOT_PROVIDER_TYPE");
                String providerBaseUrl = System.getenv("COPILOT_PROVIDER_BASE_URL");
                String providerApiKey = System.getenv("COPILOT_PROVIDER_API_KEY");

                if (providerType != null && !providerType.isBlank()
                        && providerBaseUrl != null && !providerBaseUrl.isBlank()) {
                    ProviderConfig provider = new ProviderConfig()
                            .setType(providerType)
                            .setBaseUrl(providerBaseUrl);

                    if (providerApiKey != null && !providerApiKey.isBlank()) {
                        provider.setApiKey(providerApiKey);
                    }

                    sessionConfig.setProvider(provider);
                }

                String tools = System.getenv("COPILOT_AVAILABLE_TOOLS");
                if (tools != null && !tools.isBlank()) {
                    sessionConfig.setAvailableTools(
                            List.of(tools.split("\\s*,\\s*")));
                }

                var session = client.createSession(sessionConfig).get(10, TimeUnit.MINUTES);;

                AtomicReference<String> lastMessage = new AtomicReference<>("");
                StringBuilder fullResponse = new StringBuilder();

//                session.on(AssistantMessageEvent.class, msg -> {
//                    String content = msg.getData().content();
//                    if (content != null) {
//                        lastMessage.set(content);
//                        fullResponse.setLength(0);
//                        fullResponse.append(content);
//                    }
//                });

                var msg = session.sendAndWait(
                        new MessageOptions()
                                .setPrompt(prompt)
                ).get(30, TimeUnit.MINUTES);

                String content = msg.getData().content();
                    if (content != null) {
                        lastMessage.set(content);
                        fullResponse.setLength(0);
                        fullResponse.append(content);
                    }
                String response = fullResponse.toString().trim();
                if (response.isEmpty()) {
                    response = lastMessage.get();
                }
                if (response == null || response.isBlank()) {
                    throw new RuntimeException("Copilot SDK returned an empty response");
                }
                return response;
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to call LLM via GitHub Copilot SDK: " + e.getMessage(), e);
        }
    }

    private static Map<String, String> loadCache() {
        try {
            Path path = Path.of(CACHE_FILE);
            if (!Files.exists(path)) {
                return new HashMap<>();
            }
            return MAPPER.readValue(Files.readString(path), new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private static void saveCache(Map<String, String> cache) {
        try {
            Files.writeString(Path.of(CACHE_FILE), MAPPER.writeValueAsString(cache));
        } catch (IOException e) {
            System.err.println("Warning: could not save LLM cache: " + e.getMessage());
        }
    }
}
