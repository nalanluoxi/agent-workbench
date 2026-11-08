package com.agentdesk.workbench.auth;

import com.agentdesk.workbench.core.ApiException;
import com.agentdesk.workbench.core.SecretVault;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private static final long CODE_TTL = 5 * 60_000L;
    private static final long CODE_INTERVAL = 60_000L;
    private static final long TOKEN_TTL = 7 * 24 * 60 * 60_000L;
    private static final int MAX_ATTEMPTS = 5;
    private final JdbcTemplate db;
    private final SecretVault vault;
    private final MailSender mail;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
    private final String dummyHash = passwords.encode("workbench-unused-password");

    @Autowired
    public AuthService(JdbcTemplate db, SecretVault vault, MailSender mail) {
        this(db, vault, mail, Clock.systemUTC());
    }

    AuthService(JdbcTemplate db, SecretVault vault, MailSender mail, Clock clock) {
        this.db = db; this.vault = vault; this.mail = mail; this.clock = clock;
    }

    @Transactional
    public void sendCode(String rawEmail) {
        String email = normalizeEmail(rawEmail);
        if (db.queryForObject("SELECT COUNT(*) FROM users WHERE email=?", Integer.class, email) > 0) {
            throw ApiException.conflict("该邮箱已注册");
        }
        long now = clock.millis();
        Long lastSent = db.query("SELECT sent_at FROM email_codes WHERE email=?", rs -> rs.next() ? rs.getLong(1) : null, email);
        if (lastSent != null && now - lastSent < CODE_INTERVAL) throw ApiException.conflict("验证码发送过于频繁，请稍后重试");
        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        String hash = vault.digest("email-code", email + "\0" + code);
        db.update("INSERT INTO email_codes(email,code_hash,expires_at,sent_at,attempts) VALUES(?,?,?,?,0) "
                        + "ON CONFLICT(email) DO UPDATE SET code_hash=excluded.code_hash,expires_at=excluded.expires_at,sent_at=excluded.sent_at,attempts=0",
                email, hash, now + CODE_TTL, now);
        mail.sendCode(email, code);
    }

    @Transactional
    public RegistrationResult register(String rawEmail, String code, String password) {
        String email = normalizeEmail(rawEmail);
        validatePassword(password);
        if (code == null || !code.matches("[0-9]{6}")) return new RegistrationResult(false, "验证码格式无效");
        CodeRecord record = db.query("SELECT code_hash,expires_at,attempts FROM email_codes WHERE email=?",
                rs -> rs.next() ? new CodeRecord(rs.getString(1), rs.getLong(2), rs.getInt(3)) : null, email);
        if (record == null || record.expiresAt() <= clock.millis()) {
            db.update("DELETE FROM email_codes WHERE email=?", email);
            return new RegistrationResult(false, "验证码无效或已过期");
        }
        if (record.attempts() >= MAX_ATTEMPTS) return new RegistrationResult(false, "验证码尝试次数过多，请重新获取");
        String submitted = vault.digest("email-code", email + "\0" + code);
        if (!vault.matches(record.hash(), submitted)) {
            db.update("UPDATE email_codes SET attempts=attempts+1 WHERE email=?", email);
            return new RegistrationResult(false, "验证码错误");
        }
        String id = UUID.randomUUID().toString();
        try {
            db.update("INSERT INTO users(id,email,password_hash,role,created_at) VALUES(?,?,?,'USER',?)", id, email, passwords.encode(password), clock.millis());
            db.update("INSERT INTO user_settings(user_id) VALUES(?)", id);
            db.update("DELETE FROM email_codes WHERE email=?", email);
        } catch (DuplicateKeyException e) { throw ApiException.conflict("该邮箱已注册"); }
        return new RegistrationResult(true, "注册成功，请登录");
    }

    public LoginResult login(String rawEmail, String password) {
        String email = normalizeEmail(rawEmail);
        UserRecord user = db.query("SELECT id,password_hash,role FROM users WHERE email=?",
                rs -> rs.next() ? new UserRecord(rs.getString(1), rs.getString(2), rs.getString(3)) : null, email);
        boolean matches = passwords.matches(password == null ? "" : password, user == null ? dummyHash : user.hash());
        if (user == null || !matches) throw ApiException.unauthorized("邮箱或密码错误");
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        long now = clock.millis();
        db.update("INSERT INTO auth_tokens(token_hash,user_id,kind,expires_at,created_at) VALUES(?,?,?,?,?)",
                vault.digest("auth-token", token), user.id(), "USER", now + TOKEN_TTL, now);
        return new LoginResult(token, now + TOKEN_TTL, email, user.role());
    }

    public Principal authenticate(String token) {
        if (token == null || token.isBlank() || token.length() > 256) return null;
        String hash = vault.digest("auth-token", token);
        return db.query("SELECT t.user_id,t.kind,d.run_id,u.role FROM auth_tokens t JOIN users u ON u.id=t.user_id LEFT JOIN agent_delegations d ON d.token_hash=t.token_hash WHERE t.token_hash=? AND t.expires_at>?",
                rs -> {
                    if (!rs.next()) return null;
                    String kind = rs.getString(2), runId = rs.getString(3);
                    return "AGENT".equals(kind) && runId == null ? null : new Principal(rs.getString(1), kind, hash, runId, rs.getString(4));
                }, hash, clock.millis());
    }

    public void logout(Principal principal) {
        if (principal.tokenHash() != null) db.update("DELETE FROM auth_tokens WHERE token_hash=?", principal.tokenHash());
    }

    public java.util.Map<String,Object> profile(Principal principal) {
        String email = db.query("SELECT email FROM users WHERE id=?", rs -> rs.next() ? rs.getString(1) : "", principal.userId());
        return java.util.Map.of("userId", principal.userId(), "kind", principal.kind(), "role", principal.role(), "email", email);
    }

    private static String normalizeEmail(String value) {
        String email = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (email.length() > 254 || !email.matches("[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9.-]+\\.[a-z]{2,}")) throw ApiException.badRequest("邮箱格式无效");
        return email;
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < 8 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw ApiException.badRequest("密码至少 8 个字符，UTF-8 长度不超过 72 字节");
        }
    }

    record CodeRecord(String hash, long expiresAt, int attempts) {}
    record UserRecord(String id, String hash, String role) {}
    public record RegistrationResult(boolean success, String message) {}
    public record LoginResult(String token, long expiresAt, String email, String role) {}
}
