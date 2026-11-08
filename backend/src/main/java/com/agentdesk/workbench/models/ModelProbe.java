package com.agentdesk.workbench.models;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ModelProbe {
    private final ObjectMapper json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public ModelProbe(ObjectMapper json) {
        this.json = json;
    }

    public ProbeResult test(String kind, String baseUrl, String model, String apiKey) {
        long start = System.nanoTime();
        try {
            String path = kind.equals("EMBEDDING") ? "/embeddings" : "/chat/completions";
            Map<String, Object> body = kind.equals("EMBEDDING")
                    ? Map.of("model", model, "input", List.of("ping"))
                    : Map.of("model", model, "messages", List.of(Map.of("role", "user", "content", "只回复 ok")), "stream", false);
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            if (apiKey != null && !apiKey.isBlank()) request.header("Authorization", "Bearer " + apiKey);
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            long latency = Duration.ofNanos(System.nanoTime() - start).toMillis();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("模型测试失败（HTTP " + response.statusCode() + "），请检查地址、密钥和模型标识");
            }
            JsonNode root = json.readTree(response.body());
            boolean valid = kind.equals("EMBEDDING") ? root.path("data").isArray() : root.path("choices").isArray();
            if (!valid) throw new IllegalStateException("模型测试响应格式不兼容 OpenAI API");
            return new ProbeResult(latency, "连接成功");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("模型测试已中断", e);
        } catch (java.io.IOException | IllegalArgumentException e) {
            throw new IllegalStateException("无法连接模型服务，请检查接口地址和本机网络", e);
        }
    }

    public record ProbeResult(long latencyMs, String message) {}
}
