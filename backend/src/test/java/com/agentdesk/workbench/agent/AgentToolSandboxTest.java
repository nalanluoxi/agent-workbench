package com.agentdesk.workbench.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentdesk.workbench.config.WorkbenchProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "WORKBENCH_RUN_SANDBOX_TEST", matches = "1")
class AgentToolSandboxTest {
    @Test void childCanOnlyReachAllowedServiceAndCannotReadOrWriteProtectedFiles() throws Exception {
        Path temp = Files.createTempDirectory("workbench-sandbox-test-");
        Path data = Files.createDirectories(temp.resolve("data"));
        Path key = Files.writeString(temp.resolve("master.key"), "test-only");
        Path secret = Files.writeString(data.resolve("secret"), "test-only");
        Path script = Files.createDirectories(temp.resolve("cli/bin")).resolve("probe.mjs");
        HttpServer allowed = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpServer denied = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        allowed.createContext("/", exchange -> { byte[] body = "ok".getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close(); });
        denied.createContext("/", exchange -> { exchange.sendResponseHeaders(200, -1); exchange.close(); });
        allowed.start(); denied.start();
        try {
            ObjectMapper mapper = new ObjectMapper();
            String js = """
                import { readFileSync, writeFileSync } from 'node:fs';
                import { spawnSync } from 'node:child_process';
                const result = {};
                try { readFileSync(SECRET); result.readBlocked = false; } catch { result.readBlocked = true; }
                try { readFileSync(MASTER_KEY); result.keyBlocked = false; } catch { result.keyBlocked = true; }
                try { writeFileSync(OUTPUT, 'no'); result.writeBlocked = false; } catch { result.writeBlocked = true; }
                try { result.allowed = await (await fetch(ALLOWED)).text(); } catch { result.allowed = 'failed'; }
                try { await fetch(DENIED, { signal: AbortSignal.timeout(2000) }); result.networkBlocked = false; } catch { result.networkBlocked = true; }
                const child = spawnSync('/usr/bin/id');
                result.processBlocked = !!child.error;
                console.log(JSON.stringify(result));
                """.replace("SECRET", mapper.writeValueAsString(secret.toString()))
                    .replace("MASTER_KEY", mapper.writeValueAsString(key.toString()))
                    .replace("OUTPUT", mapper.writeValueAsString(temp.resolve("should-not-exist").toString()))
                    .replace("ALLOWED", mapper.writeValueAsString("http://127.0.0.1:" + allowed.getAddress().getPort()))
                    .replace("DENIED", mapper.writeValueAsString("http://127.0.0.1:" + denied.getAddress().getPort()));
            Files.writeString(script, js);
            var settings = new WorkbenchProperties(data.toString(), key.toString(), "", "", 465, "", "", temp.resolve("cli").toString(),
                    "127.0.0.1", 6379, "", 15, false, "admin@admin.com", "admin");
            ProcessBuilder builder = new AgentToolSandbox(settings).process(script, allowed.getAddress().getPort());
            builder.environment().clear();
            builder.redirectErrorStream(true);
            Process process = builder.start();
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.exitValue()).as(output).isZero();
            var result = mapper.readTree(output);
            assertThat(result.path("readBlocked").asBoolean()).isTrue();
            assertThat(result.path("keyBlocked").asBoolean()).isTrue();
            assertThat(result.path("writeBlocked").asBoolean()).isTrue();
            assertThat(result.path("networkBlocked").asBoolean()).isTrue();
            assertThat(result.path("processBlocked").asBoolean()).isTrue();
            assertThat(result.path("allowed").asText()).isEqualTo("ok");
        } finally { allowed.stop(0); denied.stop(0); }
    }
}
