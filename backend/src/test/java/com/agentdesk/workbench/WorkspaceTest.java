package com.agentdesk.workbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentdesk.workbench.core.SecretVault;
import com.agentdesk.workbench.models.ModelService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class WorkspaceTest {
    private static final Path TEMP = temporaryDirectory();
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired SecretVault vault;
    @Autowired ModelService models;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("workbench.data-dir", () -> TEMP.resolve("data").toString());
        registry.add("workbench.key-file", () -> TEMP.resolve("key").resolve("master.key").toString());
    }

    @BeforeEach void setup() {
        addUser("first");
        addUser("second");
    }

    @Test void modelCredentialsAreEncryptedAndConfigurationsAreIsolated() throws Exception {
        String created = json.writeValueAsString(models.create("first", new ModelService.ModelInput("CHAT", "模型一", "compatible", "http://localhost:9999/v1", "demo", "my-private-key", true)));
        JsonNode node = json.readTree(created);
        String id = node.get("id").asText();
        assertThat(created).doesNotContain("my-private-key");
        assertThat(node.get("hasApiKey").asBoolean()).isTrue();
        String cipher = db.queryForObject("SELECT api_key_cipher FROM model_configs WHERE id=?", String.class, id);
        assertThat(cipher).doesNotContain("my-private-key");
        assertThat(vault.decrypt(cipher)).isEqualTo("my-private-key");
        mvc.perform(get("/api/models/" + id).header("Authorization", bearer("second"))).andExpect(status().isNotFound());
        mvc.perform(post("/api/models/" + id + "/default").header("Authorization", bearer("first"))).andExpect(status().isOk());
        models.update("first", id, new ModelService.ModelInput("CHAT", "已更新", "compatible", "http://localhost:9999/v1", "demo2", "", true));
        assertThat(db.queryForObject("SELECT api_key_cipher FROM model_configs WHERE id=?", String.class, id)).isEqualTo(cipher);
        assertThat(db.queryForObject("SELECT version FROM model_configs WHERE id=?", Integer.class, id)).isEqualTo(2);
    }

    @Test void sessionsCannotBeViewedOrChangedAcrossUsers() throws Exception {
        String created = mvc.perform(post("/api/sessions").header("Authorization", bearer("first")).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"我的会话\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(created).get("id").asText();
        mvc.perform(get("/api/sessions/" + id).header("Authorization", bearer("second"))).andExpect(status().isNotFound());
        mvc.perform(delete("/api/sessions/" + id).header("Authorization", bearer("second"))).andExpect(status().isNotFound());
        mvc.perform(patch("/api/sessions/" + id).header("Authorization", bearer("first")).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"已重命名\"}"))
                .andExpect(status().isOk());
        assertThat(db.queryForObject("SELECT title FROM sessions WHERE id=?", String.class, id)).isEqualTo("已重命名");
    }

    private void addUser(String id) {
        long now = System.currentTimeMillis();
        db.update("INSERT OR IGNORE INTO users(id,email,password_hash,created_at) VALUES(?,?,?,?)", id, id + "@example.com", "unused", now);
        db.update("INSERT OR IGNORE INTO user_settings(user_id) VALUES(?)", id);
        db.update("INSERT OR IGNORE INTO auth_tokens(token_hash,user_id,kind,expires_at,created_at) VALUES(?,?,?,?,?)", vault.digest("auth-token", id + "-test-token"), id, "USER", now + 60_000, now);
    }
    private static String bearer(String id) { return "Bearer " + id + "-test-token"; }
    private static Path temporaryDirectory() { try { return Files.createTempDirectory("workbench-workspace-test-"); } catch (Exception e) { throw new IllegalStateException(e); } }
}
