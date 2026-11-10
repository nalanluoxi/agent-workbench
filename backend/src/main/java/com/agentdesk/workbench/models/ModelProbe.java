package com.agentdesk.workbench.models;

import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class ModelProbe {
    private final OpenAiCompatibleClients clients;

    public ModelProbe(OpenAiCompatibleClients clients) { this.clients = clients; }

    public ProbeResult test(String kind, String baseUrl, String model, String apiKey) {
        long start = System.nanoTime();
        try {
            if (kind.equals("EMBEDDING")) clients.embedding(baseUrl, model, apiKey).embed("ping");
            else clients.chat(baseUrl, model, apiKey).chat("只回复 ok");
            long latency = Duration.ofNanos(System.nanoTime() - start).toMillis();
            return new ProbeResult(latency, "连接成功");
        } catch (Exception e) { throw ModelErrorMessage.failure("模型测试", e); }
    }

    public record ProbeResult(long latencyMs, String message) {}
}
