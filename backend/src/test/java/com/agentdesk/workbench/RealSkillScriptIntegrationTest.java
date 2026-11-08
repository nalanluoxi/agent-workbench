package com.agentdesk.workbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentdesk.workbench.auth.Principal;
import com.agentdesk.workbench.agent.AgentService;
import com.agentdesk.workbench.agent.ModelGateway;
import com.agentdesk.workbench.core.ApiException;
import com.agentdesk.workbench.memory.MemoryGateway;
import com.agentdesk.workbench.models.ModelService;
import com.agentdesk.workbench.sessions.SessionService;
import com.agentdesk.workbench.skills.SkillScriptService;
import com.agentdesk.workbench.skills.SkillService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "WORKBENCH_RUN_SKILL_SCRIPT_TEST", matches = "1")
class RealSkillScriptIntegrationTest {
    private static final Path TEMP = temp();
    @Autowired JdbcTemplate db;
    @Autowired SkillService skills;
    @Autowired SkillScriptService scripts;
    @Autowired ObjectMapper json;
    @Autowired AgentService agent;
    @Autowired ModelService models;
    @Autowired SessionService sessions;
    @MockBean ModelGateway gateway;
    @MockBean MemoryGateway memoryGateway;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("workbench.data-dir", () -> TEMP.resolve("data").toString());
        registry.add("workbench.key-file", () -> TEMP.resolve("key/master.key").toString());
    }

    @Test void agentRequestsRealScriptAndResumesOnlyAfterUserApproval() throws Exception {
        Path source = Files.createDirectories(TEMP.resolve("agent-source"));
        Files.writeString(source.resolve("SKILL.md"), "---\nname: agent-script\ndescription: test\n---\n说明\n");
        Files.writeString(source.resolve("hello.py"), "print('real script result')\n");
        long now = System.currentTimeMillis();
        db.update("INSERT INTO users(id,email,password_hash,created_at) VALUES('agent-script','agent-script@example.com','unused',?)", now);
        db.update("INSERT INTO user_settings(user_id) VALUES('agent-script')");
        var skill = skills.installLocal("agent-script", source.toString());
        var model = models.create("agent-script", new ModelService.ModelInput("CHAT", "model", "test", "http://127.0.0.1:9999/v1", "model", "", true));
        var session = sessions.create("agent-script", "真实脚本");
        String arguments = json.writeValueAsString(java.util.Map.of("skillId", skill.view().id(), "script", "hello.py", "args", List.of()));
        AtomicInteger turns = new AtomicInteger();
        org.mockito.Mockito.when(gateway.complete(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anySet(), org.mockito.ArgumentMatchers.anySet(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> turns.getAndIncrement() == 0
                        ? new ModelGateway.ModelReply("等待确认", List.of(new ModelGateway.ToolCall("script-call", "skill_script", arguments)))
                        : new ModelGateway.ModelReply("脚本已返回", List.of()));
        org.mockito.Mockito.when(memoryGateway.extract(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new MemoryGateway.Extracted(List.of(), List.of(), List.of()));
        String run = agent.start("agent-script", session.id(), new AgentService.StartRequest("运行脚本", model.id(), "BUILD")).id();
        awaitRun(run, "waiting_confirmation");
        String action = db.queryForObject("SELECT a.id FROM skill_script_actions a JOIN workflow_jobs j ON j.id=a.job_id WHERE j.run_id=?", String.class, run);
        var result = scripts.approve(new Principal("agent-script", "USER", "test"), action);
        assertThat(result.status()).as(String.valueOf(result.error())).isEqualTo("COMPLETED");
        assertThat(result.result().path("stdout").asText()).contains("real script result");
        awaitRun(run, "completed");
        assertThat(turns.get()).isEqualTo(2);
    }
    private void awaitRun(String id, String expected) throws Exception {
        for (int i = 0; i < 200; i++) {
            String status = db.queryForObject("SELECT status FROM runs WHERE id=?", String.class, id);
            if (expected.equals(status)) return;
            if (List.of("failed", "interrupted").contains(status)) throw new AssertionError("运行失败：" + db.queryForObject("SELECT error FROM runs WHERE id=?", String.class, id));
            Thread.sleep(50);
        }
        throw new AssertionError("运行没有进入 " + expected);
    }

    @Test void pythonAndNodeExecuteOnlyAfterApprovalInsideIsolation() throws Exception {
        Path source = Files.createDirectories(TEMP.resolve("source"));
        Files.writeString(source.resolve("SKILL.md"), "---\nname: sandbox-test\ndescription: test\n---\n测试技能\n");
        Files.writeString(source.resolve("probe.py"), """
                import sys, json, socket, subprocess
                result = {'input':sys.stdin.read(), 'args':sys.argv[1:]}
                try:
                    open(sys.argv[1]).read()
                    result['readBlocked'] = False
                except Exception: result['readBlocked'] = True
                try:
                    open(sys.argv[2], 'w').write('bad')
                    result['writeBlocked'] = False
                except Exception: result['writeBlocked'] = True
                try:
                    socket.create_connection(('127.0.0.1', int(sys.argv[3])), timeout=1)
                    result['networkBlocked'] = False
                except Exception: result['networkBlocked'] = True
                try:
                    subprocess.run(['/usr/bin/id'], check=True, capture_output=True)
                    result['processBlocked'] = False
                except Exception: result['processBlocked'] = True
                print(json.dumps(result))
                """);
        Files.writeString(source.resolve("hello.mjs"), "console.log('node:' + process.argv[2]);\n");
        Files.writeString(source.resolve("fail.py"), "import sys\nprint('expected failure', file=sys.stderr)\nsys.exit(2)\n");
        long now = System.currentTimeMillis();
        db.update("INSERT INTO users(id,email,password_hash,created_at) VALUES('script-user','script@example.com','unused',?)", now);
        db.update("INSERT INTO user_settings(user_id) VALUES('script-user')");
        var installed = skills.installLocal("script-user", source.toString()); String id = installed.view().id();
        assertThat(skills.scripts("script-user", id)).containsExactly("fail.py", "hello.mjs", "probe.py");
        AtomicInteger requests = new AtomicInteger(); HttpServer target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/", exchange -> { requests.incrementAndGet(); exchange.sendResponseHeaders(204, -1); exchange.close(); }); target.start();
        try {
            Principal user = new Principal("script-user", "USER", "test");
            Path forbiddenWrite = TEMP.resolve("not-written");
            var pending = scripts.request(user, new SkillScriptService.Input(id, "probe.py", List.of(TEMP.resolve("key/master.key").toString(), forbiddenWrite.toString(), Integer.toString(target.getAddress().getPort())), "hello", null, null));
            assertThat(pending.status()).isEqualTo("WAITING_CONFIRMATION");
            assertThat(pending.action().kind()).isEqualTo("SKILL_SCRIPT");
            assertThatThrownBy(() -> scripts.approve(new Principal("script-user", "AGENT", "test", "run"), pending.action().id())).isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> scripts.approve(new Principal("other", "USER", "test"), pending.action().id())).isInstanceOf(ApiException.class);
            var completed = scripts.approve(user, pending.action().id());
            assertThat(completed.status()).as(String.valueOf(completed.error())).isEqualTo("COMPLETED");
            var result = json.readTree(completed.result().path("stdout").asText());
            assertThat(result.path("input").asText()).isEqualTo("hello");
            assertThat(result.path("readBlocked").asBoolean()).isTrue();
            assertThat(result.path("writeBlocked").asBoolean()).isTrue();
            assertThat(result.path("networkBlocked").asBoolean()).isTrue();
            assertThat(result.path("processBlocked").asBoolean()).isTrue();
            assertThat(forbiddenWrite).doesNotExist(); assertThat(requests.get()).isZero();
            assertThat(scripts.approve(user, pending.action().id()).result()).isEqualTo(completed.result());
            var node = scripts.request(user, new SkillScriptService.Input(id, "hello.mjs", List.of("ok"), "", null, null));
            assertThat(scripts.approve(user, node.action().id()).result().path("stdout").asText()).contains("node:ok");
            var failing = scripts.request(user, new SkillScriptService.Input(id, "fail.py", List.of(), "", null, null));
            var failed = scripts.approve(user, failing.action().id());
            assertThat(failed.status()).isEqualTo("FAILED");
            assertThat(failed.result().path("exitCode").asInt()).isEqualTo(2);
            assertThat(failed.result().path("stderr").asText()).contains("expected failure");
            var rejected = scripts.request(user, new SkillScriptService.Input(id, "hello.mjs", List.of("no"), "", null, null));
            assertThat(scripts.reject(user, rejected.action().id()).status()).isEqualTo("REJECTED");
            var changed = scripts.request(user, new SkillScriptService.Input(id, "hello.mjs", List.of("no"), "", null, null));
            Files.writeString(skills.executablePackage("script-user", id).resolve("hello.mjs"), "// changed\n", java.nio.file.StandardOpenOption.APPEND);
            assertThatThrownBy(() -> scripts.approve(user, changed.action().id())).isInstanceOf(ApiException.class);
            assertThat(scripts.getAction("script-user", changed.action().id()).status()).isEqualTo("EXPIRED");
            var versioned = scripts.request(user, new SkillScriptService.Input(id, "hello.mjs", List.of("version"), "", null, null));
            skills.update("script-user", id, new SkillService.UpdateInput("sandbox-test", "updated", "---\nname: sandbox-test\ndescription: updated\n---\n新版说明\n"));
            assertThatThrownBy(() -> scripts.approve(user, versioned.action().id())).isInstanceOf(ApiException.class);
            assertThat(scripts.getAction("script-user", versioned.action().id()).status()).isEqualTo("EXPIRED");
            assertThatThrownBy(() -> scripts.request(user, new SkillScriptService.Input(id, "../master.key", List.of(), "", null, null))).isInstanceOf(ApiException.class);
        } finally { target.stop(0); }
    }

    @Test void longRunningScriptIsStoppedAtTimeout() throws Exception {
        Path source = Files.createDirectories(TEMP.resolve("timeout-source"));
        Files.writeString(source.resolve("SKILL.md"), "---\nname: timeout-test\ndescription: test\n---\n说明\n");
        Files.writeString(source.resolve("slow.py"), "import time\ntime.sleep(60)\n");
        long now = System.currentTimeMillis();
        db.update("INSERT INTO users(id,email,password_hash,created_at) VALUES('script-timeout','script-timeout@example.com','unused',?)", now);
        db.update("INSERT INTO user_settings(user_id) VALUES('script-timeout')");
        var skill = skills.installLocal("script-timeout", source.toString());
        Principal user = new Principal("script-timeout", "USER", "test");
        var pending = scripts.request(user, new SkillScriptService.Input(skill.view().id(), "slow.py", List.of(), "", null, null));
        var result = scripts.approve(user, pending.action().id());
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.error()).contains("超时");
    }
    private static Path temp() { try { return Files.createTempDirectory("workbench-real-script-"); } catch (Exception e) { throw new IllegalStateException(e); } }
}
