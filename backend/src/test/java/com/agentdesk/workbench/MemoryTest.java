package com.agentdesk.workbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.agentdesk.workbench.core.ApiException;
import com.agentdesk.workbench.core.SecretVault;
import com.agentdesk.workbench.memory.MemoryGateway;
import com.agentdesk.workbench.memory.MemoryService;
import com.agentdesk.workbench.models.ModelService;
import com.agentdesk.workbench.sessions.SessionService;
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
        assertThat(memories.list("one", "L3", "")).hasSize(1);
        var fact = memories.list("one", "L1", "").get(0);
        assertThatThrownBy(() -> memories.get("two", fact.id())).isInstanceOf(ApiException.class);
        memories.update("one", fact.id(), new MemoryService.MemoryInput("L1", "用户偏好浅色界面"));
        assertThat(memories.list("one", "L2", "").get(0).status()).isEqualTo("STALE");
        assertThat(memories.recall("one", "界面偏好").memories()).extracting(MemoryService.MemoryView::content).contains("用户偏好浅色界面").doesNotContain("界面偏好：深色");
        db.update("UPDATE memory_jobs SET status='FAILED' WHERE run_id=?", run);
        memories.retry("one", run);
        waitFor(run, "SKIPPED");
        memories.delete("one", fact.id());
        assertThat(memories.list("one", "L1", "")).isEmpty();
        assertThat(memories.list("one", "L2", "")).isEmpty();
        assertThat(memories.list("one", "L3", "")).isEmpty();
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
