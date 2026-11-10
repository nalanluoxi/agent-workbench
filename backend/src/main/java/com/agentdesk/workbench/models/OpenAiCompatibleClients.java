package com.agentdesk.workbench.models;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class OpenAiCompatibleClients {
    private static final int MAX_CLIENTS = 24;
    private final Map<ClientKey, OpenAiStreamingChatModel> streaming = lru();

    public synchronized OpenAiStreamingChatModel streaming(String baseUrl, String model, String apiKey) {
        ClientKey key = key(baseUrl, model, apiKey);
        return streaming.computeIfAbsent(key, ignored -> OpenAiStreamingChatModel.builder()
                .baseUrl(normalize(baseUrl)).apiKey(value(apiKey)).modelName(model)
                .timeout(Duration.ofMinutes(3)).build());
    }

    public OpenAiChatModel chat(String baseUrl, String model, String apiKey) {
        return OpenAiChatModel.builder().baseUrl(normalize(baseUrl)).apiKey(value(apiKey)).modelName(model)
                .timeout(Duration.ofSeconds(60)).maxRetries(2).build();
    }

    public OpenAiEmbeddingModel embedding(String baseUrl, String model, String apiKey) {
        return OpenAiEmbeddingModel.builder().baseUrl(normalize(baseUrl)).apiKey(value(apiKey)).modelName(model)
                .timeout(Duration.ofSeconds(60)).maxRetries(2).build();
    }

    private static <K, V> Map<K, V> lru() {
        return new LinkedHashMap<>(16, .75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<K, V> eldest) { return size() > MAX_CLIENTS; }
        };
    }

    private static ClientKey key(String baseUrl, String model, String apiKey) {
        return new ClientKey(normalize(baseUrl), model, digest(value(apiKey)));
    }

    private static String normalize(String baseUrl) {
        String value = baseUrl == null ? "" : baseUrl.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private static String value(String apiKey) { return apiKey == null ? "" : apiKey; }

    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("无法创建模型配置指纹", e); }
    }

    private record ClientKey(String baseUrl, String model, String keyDigest) {}
}
