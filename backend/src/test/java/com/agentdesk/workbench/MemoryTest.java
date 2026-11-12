package com.agentdesk.workbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.agentdesk.workbench.core.ApiException;
import com.agentdesk.workbench.core.SecretVault;
import com.agentdesk.workbench.memory.MemoryGateway;
import com.agentdesk.workbench.memory.HybridMemoryService;
import com.agentdesk.workbench.memory.MemoryService;
import com.agentdesk.workbench.models.ModelService;
import com.agentdesk.workbench.sessions.SessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class MemoryTest {
    private static final Path TEMP = temp();
    @Autowired JdbcTemplate db;
    @Autowired SecretVault vault;
    @Autowired MemoryService memories;
    @Autowired HybridMemoryService hybrid;
    @Autowired ObjectMapper json;
    @Autowired ModelService models;
    @Autowired SessionService sessions;
    @MockBean MemoryGateway gateway;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("workbench.data-dir", () -> TEMP.resolve("data").toString());
        registry.add("workbench.key-file", () -> TEMP.resolve("key").resolve("master.key").toString());
    }
    @BeforeEach void setup() {
        db.update("INSERT OR IGNORE INTO users(id,email,password_hash,created_at) VALUES('one','one@memory.test','unused',0)");
        db.update("INSERT OR IGNORE INTO users(id,email,password_hash,created_at) VALUES('two','two@memory.test','unused',0)");
        db.update("INSERT OR IGNORE INTO user_settings(user_id) VALUES('one')");
        db.update("INSERT OR IGNORE INTO user_settings(user_id) VALUES('two')");
    }

    @Test void extractionCreatesLayersAndEditingOrDeletingSourceInvalidatesDependents() throws Exception {
        var session = sessions.create("one", "原始会话");
        String message = sessions.append("one", session.id(), "user", "我偏好使用深色界面");
        String run = "memory-run"; long now = System.currentTimeMillis();
        db.update("INSERT INTO runs(id,user_id,session_id,status,created_at,updated_at) VALUES(?,'one',?,'completed',?,?)", run, session.id(), now, now);
        db.update("INSERT INTO run_contexts(run_id,base_url,model_name,api_key_cipher,messages_json,source_message_id) VALUES(?,?,?,?,?,?)", run, "http://localhost/v1", "model", vault.encrypt("secret"), "[]", message);
        when(gateway.extract(anyString(), anyString(), any(), anyString())).thenReturn(new MemoryGateway.Extracted(List.of("用户偏好深色界面"), List.of("界面偏好：深色"), List.of("偏好深色工作环境")));
        memories.queue(run);
        waitFor(run, "COMPLETED");
        assertThat(memories.list("one", "L1", "")).hasSize(1);
        assertThat(memories.list("one", "L2", "")).hasSize(1);
        assertThat(memories.list("one", "L3", "")).as("L3 需累计 50 条有效 L1 变化后更新").isEmpty();
        var fact = memories.list("one", "L1", "").get(0);
        assertThatThrownBy(() -> memories.get("two", fact.id())).isInstanceOf(ApiException.class);
        memories.update("one", fact.id(), new MemoryService.MemoryInput("L1", "用户偏好浅色界面"));
        assertThat(memories.list("one", "L2", "").get(0).status()).isEqualTo("STALE");
        assertThat(memories.recall("one", "界面偏好").memories()).extracting(MemoryService.MemoryView::content).contains("用户偏好浅色界面").doesNotContain("界面偏好：深色");
        db.update("UPDATE memory_jobs SET status='FAILED' WHERE run_id=?", run);
        memories.retry("one", run);
        waitFor(run, "SKIPPED");
        memories.delete("one", fact.id());
        assertThat(memories.list("one", "L1", "")).extracting(MemoryService.MemoryView::status).containsExactly("DELETED");
        assertThat(memories.list("one", "L2", "")).extracting(MemoryService.MemoryView::status).containsExactly("STALE");
        assertThat(memories.list("one", "L3", "")).isEmpty();
        assertThat(memories.restore("one", fact.id()).status()).isEqualTo("ACTIVE");
    }

    @Test void embeddingsUseConfiguredModelAndCacheOnlyMatchingVersions() {
        var first = memories.create("two", new MemoryService.MemoryInput("L1", "喜欢 MySQL"));
        memories.create("two", new MemoryService.MemoryInput("L1", "喜欢 Redis"));
        var model = models.create("two", new ModelService.ModelInput("EMBEDDING", "向量模型", "compatible", "http://localhost/v1", "embed", "key", true));
        models.setDefault("two", model.id());
        when(gateway.embeddings(any(), anyList())).thenAnswer(invocation -> {
            List<String> inputs = invocation.getArgument(1); List<double[]> values = new ArrayList<>();
            for (String input : inputs) values.add(input.contains("MySQL") ? new double[]{1, 0} : new double[]{0, 1});
            return values;
        });
        var result = memories.recall("two", "MySQL");
        assertThat(result.mode()).isEqualTo("embedding");
        assertThat(result.memories()).extracting(MemoryService.MemoryView::id).contains(first.id());
        memories.recall("two", "MySQL");
        verify(gateway, times(1)).embeddings(any(), anyList());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM memory_embeddings WHERE model_id=?", Integer.class, model.id())).isEqualTo(2);
        memories.update("two", first.id(), new MemoryService.MemoryInput("L1", "喜欢数据库"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM memory_embeddings WHERE memory_id=?", Integer.class, first.id())).isZero();
    }

    @Test void hybridWindowKeepsCompleteToolEvidenceAndSummarizesFiveEvictedTurns() throws Exception {
        db.update("INSERT OR IGNORE INTO users(id,email,password_hash,created_at) VALUES('hybrid','hybrid@memory.test','unused',0)");
        db.update("INSERT OR IGNORE INTO user_settings(user_id) VALUES('hybrid')");
        var summaryModel = models.create("hybrid", new ModelService.ModelInput("CHAT", "摘要模型", "compatible", "http://localhost/v1", "chat", "key", true));
        models.setDefault("hybrid", summaryModel.id());
        when(gateway.summarize(anyString(), anyString(), any(), any(), anyString())).thenReturn("{\"currentTopic\":\"第5轮\",\"userGoal\":\"查询\",\"verifiedResults\":[],\"completed\":[],\"failed\":[],\"pending\":[],\"decisions\":[]}");
        var session = sessions.create("hybrid", "TurnBlock 会话");
        long now = System.currentTimeMillis();
        for (int i = 1; i <= 25; i++) {
            String messageId = sessions.append("hybrid", session.id(), "user", "第 " + i + " 轮查询", "BUILD");
            String runId = "hybrid-run-" + i;
            String messagesJson = json.writeValueAsString(List.of(
                    java.util.Map.of("role", "user", "content", "第 " + i + " 轮查询"),
                    java.util.Map.of("role", "tool", "content", "真实结果 " + i),
                    java.util.Map.of("role", "assistant", "content", "已返回第 " + i + " 轮结果")));
            db.update("INSERT INTO runs(id,user_id,session_id,status,mode,created_at,updated_at) VALUES(?,'hybrid',?,'completed','BUILD',?,?)", runId, session.id(), now + i, now + i);
            db.update("INSERT INTO run_contexts(run_id,base_url,model_name,messages_json,source_message_id) VALUES(?,'http://localhost/v1','model',?,?)", runId, messagesJson, messageId);
            db.update("INSERT INTO run_events(run_id,seq,type,payload,created_at) VALUES(?,1,'tool_result',?,?)", runId,
                    "{\"status\":\"COMPLETED\",\"result\":\"真实结果 " + i + "\"}", now + i);
        }
        hybrid.onRunTerminal("hybrid-run-25");
        var context = hybrid.context("hybrid", session.id());
        assertThat(context.turns()).hasSize(20);
        assertThat(context.turns().get(0).userMessage()).isEqualTo("第 6 轮查询");
        assertThat(context.turns().get(19).events()).extracting(HybridMemoryService.EventEvidence::type).contains("tool_result");
        assertThat(context.summary()).isNotNull();
        assertThat(context.summary().coveredUntilTurn()).isEqualTo(5);
    }

    @Test void hotContextUsesRealSummaryAndReportsPagedJobCounts() {
        db.update("INSERT OR IGNORE INTO users(id,email,password_hash,created_at) VALUES('context-user','context@memory.test','unused',0)");
        db.update("INSERT OR IGNORE INTO user_settings(user_id) VALUES('context-user')");
        var session = sessions.create("context-user", "语义统计会话");
        long now = System.currentTimeMillis();
        db.update("INSERT INTO session_summaries(id,user_id,session_id,content_json,covered_from_turn,covered_until_turn,status,created_at,updated_at) VALUES('context-summary','context-user',?,?,1,5,'ACTIVE',?,?)",
                session.id(), "{\"currentTopic\":\"当前会话摘要\"}", now, now);
        db.update("INSERT INTO memories(id,user_id,layer,content,fingerprint,status,created_at,updated_at) VALUES('context-l2','context-user','L2','场景记忆','context-l2','ACTIVE',?,?)", now, now);
        db.update("INSERT INTO memories(id,user_id,layer,content,fingerprint,status,created_at,updated_at) VALUES('context-l3','context-user','L3','用户画像','context-l3','ACTIVE',?,?)", now, now);
        db.update("INSERT INTO memory_jobs(run_id,user_id,session_id,source_message_id,status,created_at,updated_at) VALUES('context-job','context-user',?,'source','FAILED',?,?)", session.id(), now, now);

        MemoryService.HotContext context = memories.context("context-user", session.id());
        assertThat(context.sessionSummary()).contains("当前会话摘要");
        assertThat(context.loadedCounts().l2()).isEqualTo(1);
        assertThat(context.activeTotals().l3()).isEqualTo(1);
        assertThat(context.jobStats().failed()).isEqualTo(1);
        MemoryService.MemoryJobPage page = memories.jobs("context-user", "FAILED", 1, 20);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).extracting(MemoryService.MemoryJob::runId).containsExactly("context-job");
    }
    private void waitFor(String runId, String expected) throws Exception {
        for (int i = 0; i < 100; i++) {
            String state = db.queryForObject("SELECT status FROM memory_jobs WHERE run_id=?", String.class, runId);
            if (expected.equals(state)) return;
            if ("FAILED".equals(state) && !expected.equals("FAILED")) throw new AssertionError(db.queryForObject("SELECT error FROM memory_jobs WHERE run_id=?", String.class, runId));
            Thread.sleep(30);
        }
        throw new AssertionError("记忆任务未进入 " + expected);
    }
    private static Path temp() { try { return Files.createTempDirectory("workbench-memory-test-"); } catch (Exception e) { throw new IllegalStateException(e); } }
}
