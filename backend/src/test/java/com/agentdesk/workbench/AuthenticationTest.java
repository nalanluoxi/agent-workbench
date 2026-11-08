package com.agentdesk.workbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentdesk.workbench.auth.MailSender;
import com.agentdesk.workbench.core.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AuthenticationTest {
    private static final Path TEMP = temporaryDirectory();
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate db;
    @MockBean MailSender mail;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("workbench.data-dir", () -> TEMP.resolve("data").toString());
        registry.add("workbench.key-file", () -> TEMP.resolve("key").resolve("master.key").toString());
    }

    @Test
    void emailVerificationLoginAndLogout() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        String email = "person@example.com";
        send("/api/auth/email-codes", Map.of("email", email), 200);
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(mail).sendCode(org.mockito.ArgumentMatchers.eq(email), code.capture());
        String wrong = code.getValue().equals("000000") ? "000001" : "000000";
        send("/api/auth/register", Map.of("email", email, "code", wrong, "password", "very-secret-password"), 400);
        assertThat(db.queryForObject("SELECT attempts FROM email_codes WHERE email=?", Integer.class, email)).isEqualTo(1);
        send("/api/auth/register", Map.of("email", email, "code", code.getValue(), "password", "very-secret-password"), 200);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM email_codes WHERE email=?", Integer.class, email)).isZero();
        String hash = db.queryForObject("SELECT password_hash FROM users WHERE email=?", String.class, email);
        assertThat(hash).doesNotContain("very-secret-password");
        String result = send("/api/auth/login", Map.of("email", email, "password", "very-secret-password"), 200);
        JsonNode login = mapper.readTree(result);
        String token = login.get("token").asText();
        assertThat(token).isNotBlank();
        assertThat(login.get("role").asText()).isEqualTo("USER");
        assertThat(db.queryForObject("SELECT token_hash FROM auth_tokens LIMIT 1", String.class)).isNotEqualTo(token);
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("USER"));
        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    @Test
    void defaultAdminCanLoginAndIsMarkedAsAdmin() throws Exception {
        String result = send("/api/auth/login", Map.of("email", "admin@admin.com", "password", "admin"), 200);
        JsonNode login = mapper.readTree(result);
        assertThat(login.get("role").asText()).isEqualTo("ADMIN");
        String token = login.get("token").asText();
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("USER"))
                .andExpect(jsonPath("$.role").value("ADMIN"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM users WHERE email='admin@admin.com'", Integer.class)).isEqualTo(1);
    }

    @Test
    void failedMailDoesNotLeaveUsableCode() throws Exception {
        String email = "mail-failure@example.com";
        doThrow(new ApiException(HttpStatus.BAD_GATEWAY, "邮件发送失败")).when(mail).sendCode(org.mockito.ArgumentMatchers.eq(email), anyString());
        send("/api/auth/email-codes", Map.of("email", email), 502);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM email_codes WHERE email=?", Integer.class, email)).isZero();
    }

    private String send(String path, Object body, int expected) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body)))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }

    private static Path temporaryDirectory() {
        try { return Files.createTempDirectory("workbench-auth-test-"); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
