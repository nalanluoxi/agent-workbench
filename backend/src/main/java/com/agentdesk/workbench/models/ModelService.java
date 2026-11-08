package com.agentdesk.workbench.models;

import com.agentdesk.workbench.core.ApiException;
import com.agentdesk.workbench.core.SecretVault;
import java.net.URI;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ModelService {
    private final JdbcTemplate db;
    private final SecretVault vault;
    private final ModelProbe probe;
    private final SecureRandom random = new SecureRandom();

    public ModelService(JdbcTemplate db, SecretVault vault, ModelProbe probe) {
        this.db = db;
        this.vault = vault;
        this.probe = probe;
    }

    public List<ModelView> list(String userId) {
        return db.query("SELECT m.id,m.kind,m.name,m.provider,m.base_url,m.model_name,m.api_key_cipher IS NOT NULL,m.enabled,m.version,"
                        + "CASE WHEN m.kind='CHAT' THEN s.default_chat_model=m.id ELSE s.default_embedding_model=m.id END "
                        + "FROM model_configs m LEFT JOIN user_settings s ON s.user_id=m.user_id WHERE m.user_id=? ORDER BY m.created_at DESC",
                (rs, row) -> new ModelView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getBoolean(7), rs.getBoolean(8), rs.getInt(9), rs.getBoolean(10)), userId);
    }

    public ModelView get(String userId, String id) {
        return list(userId).stream().filter(model -> model.id().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("模型配置不存在"));
    }

    @Transactional
    public ModelView create(String userId, ModelInput input) {
        return createInternal(userId, input);
    }

    @Transactional
    public ModelView createVerified(String userId, ModelInput input) {
        requireTest(userId, null, input);
        return createInternal(userId, input);
    }

    private ModelView createInternal(String userId, ModelInput input) {
        PreparedModel model = prepare(input);
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        db.update("INSERT INTO model_configs(id,user_id,kind,name,provider,base_url,model_name,api_key_cipher,enabled,created_at,updated_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?)", id, userId, model.kind(), model.name(), model.provider(), model.baseUrl(),
                model.modelName(), vault.encrypt(model.apiKey()), model.enabled() ? 1 : 0, now, now);
        return get(userId, id);
    }

    @Transactional
    public ModelView update(String userId, String id, ModelInput input) {
        return updateInternal(userId, id, input);
    }

    @Transactional
    public ModelView updateVerified(String userId, String id, ModelInput input) {
        requireTest(userId, id, input);
        return updateInternal(userId, id, input);
    }

    private ModelView updateInternal(String userId, String id, ModelInput input) {
        ModelView existing = get(userId, id);
        PreparedModel model = prepare(input);
        if (!existing.kind().equals(model.kind())) throw ApiException.badRequest("不能更改已有模型配置的类型");
        String cipher = model.apiKey() == null || model.apiKey().isBlank() ? null : vault.encrypt(model.apiKey());
        db.update("UPDATE model_configs SET name=?,provider=?,base_url=?,model_name=?,api_key_cipher=COALESCE(?,api_key_cipher),"
                        + "enabled=?,version=version+1,updated_at=? WHERE id=? AND user_id=?",
                model.name(), model.provider(), model.baseUrl(), model.modelName(), cipher, model.enabled() ? 1 : 0,
                System.currentTimeMillis(), id, userId);
        if (existing.kind().equals("EMBEDDING")) db.update("DELETE FROM memory_embeddings WHERE model_id=?", id);
        if (!model.enabled()) clearDefault(userId, id);
        return get(userId, id);
    }

    @Transactional
    public ModelTestResult test(String userId, ModelTestRequest request) {
        if (request == null || request.input() == null) throw ApiException.badRequest("缺少模型测试参数");
        PreparedModel model = prepare(request.input());
        String apiKey = resolvedApiKey(userId, request.modelId(), model.apiKey());
        ModelProbe.ProbeResult result;
        try { result = probe.test(model.kind(), model.baseUrl(), model.modelName(), apiKey); }
        catch (IllegalStateException e) { throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, e.getMessage()); }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        long now = System.currentTimeMillis();
        db.update("DELETE FROM model_test_tokens WHERE user_id=? OR expires_at<=?", userId, now);
        db.update("INSERT INTO model_test_tokens(token_hash,user_id,config_digest,expires_at,created_at) VALUES(?,?,?,?,?)",
                vault.digest("model-test-token", token), userId, configDigest(request.modelId(), model, apiKey), now + 10 * 60_000L, now);
        return new ModelTestResult(token, result.latencyMs(), result.message(), now + 10 * 60_000L);
    }

    @Transactional
    public void delete(String userId, String id) {
        get(userId, id);
        clearDefault(userId, id);
        db.update("DELETE FROM memory_embeddings WHERE model_id=?", id);
        db.update("DELETE FROM model_configs WHERE id=? AND user_id=?", id, userId);
    }

    @Transactional
    public ModelView setDefault(String userId, String id) {
        ModelView model = get(userId, id);
        if (!model.enabled()) throw ApiException.badRequest("停用的模型不能设为默认模型");
        String field = model.kind().equals("CHAT") ? "default_chat_model" : "default_embedding_model";
        db.update("UPDATE user_settings SET " + field + "=? WHERE user_id=?", id, userId);
        return get(userId, id);
    }

    public ModelConnection resolveChat(String userId, String requestedId) {
        String id = requestedId;
        if (id == null || id.isBlank()) {
            id = db.query("SELECT default_chat_model FROM user_settings WHERE user_id=?", rs -> rs.next() ? rs.getString(1) : null, userId);
        }
        if (id == null || id.isBlank()) throw ApiException.badRequest("请先配置并选择对话模型");
        List<ModelConnection> models = db.query("SELECT id,kind,base_url,model_name,api_key_cipher,version,enabled FROM model_configs WHERE id=? AND user_id=?",
                (rs, row) -> new ModelConnection(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        vault.decrypt(rs.getString(5)), rs.getInt(6), rs.getBoolean(7)), id, userId);
        if (models.isEmpty() || !models.get(0).kind().equals("CHAT") || !models.get(0).enabled()) {
            throw ApiException.badRequest("所选对话模型不可用，请重新选择");
        }
        return models.get(0);
    }

    public ModelConnection resolveEmbedding(String userId) {
        String id = db.query("SELECT default_embedding_model FROM user_settings WHERE user_id=?", rs -> rs.next() ? rs.getString(1) : null, userId);
        if (id == null || id.isBlank()) return null;
        List<ModelConnection> models = db.query("SELECT id,kind,base_url,model_name,api_key_cipher,version,enabled FROM model_configs WHERE id=? AND user_id=?",
                (rs, row) -> new ModelConnection(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        vault.decrypt(rs.getString(5)), rs.getInt(6), rs.getBoolean(7)), id, userId);
        if (models.isEmpty() || !models.get(0).kind().equals("EMBEDDING") || !models.get(0).enabled()) throw ApiException.badRequest("默认嵌入模型不可用");
        return models.get(0);
    }

    private void clearDefault(String userId, String id) {
        db.update("UPDATE user_settings SET default_chat_model=CASE WHEN default_chat_model=? THEN NULL ELSE default_chat_model END,"
                + "default_embedding_model=CASE WHEN default_embedding_model=? THEN NULL ELSE default_embedding_model END WHERE user_id=?", id, id, userId);
    }

    private void requireTest(String userId, String modelId, ModelInput input) {
        if (input == null || input.testToken() == null || input.testToken().isBlank()) throw ApiException.conflict("请先测试当前模型配置，测试成功后才能保存");
        PreparedModel model = prepare(input);
        String apiKey = resolvedApiKey(userId, modelId, model.apiKey());
        String hash = vault.digest("model-test-token", input.testToken());
        String expected = configDigest(modelId, model, apiKey);
        Integer count = db.queryForObject("SELECT COUNT(*) FROM model_test_tokens WHERE token_hash=? AND user_id=? AND config_digest=? AND expires_at>?",
                Integer.class, hash, userId, expected, System.currentTimeMillis());
        if (count == null || count == 0) throw ApiException.conflict("模型配置已变化或测试已过期，请重新测试");
        db.update("DELETE FROM model_test_tokens WHERE token_hash=?", hash);
    }

    private String resolvedApiKey(String userId, String modelId, String candidate) {
        if (candidate != null && !candidate.isBlank()) return candidate;
        if (modelId == null || modelId.isBlank() || "new".equals(modelId)) return "";
        List<String> keys = db.query("SELECT api_key_cipher FROM model_configs WHERE id=? AND user_id=?",
                (rs, n) -> vault.decrypt(rs.getString(1)), modelId, userId);
        if (keys.isEmpty()) throw ApiException.notFound("模型配置不存在");
        return keys.get(0) == null ? "" : keys.get(0);
    }

    private String configDigest(String modelId, PreparedModel model, String apiKey) {
        return vault.digest("model-test-config", String.join("\0", modelId == null ? "" : modelId, model.kind(), model.name(), model.provider(),
                model.baseUrl(), model.modelName(), apiKey == null ? "" : apiKey, Boolean.toString(model.enabled())));
    }

    private PreparedModel prepare(ModelInput input) {
        if (input == null || input.kind() == null || !(input.kind().equals("CHAT") || input.kind().equals("EMBEDDING"))) {
            throw ApiException.badRequest("模型类型必须是 CHAT 或 EMBEDDING");
        }
        String name = requireText(input.name(), "模型名称");
        String provider = requireText(input.provider(), "服务商");
        String modelName = requireText(input.modelName(), "模型标识");
        String baseUrl = requireText(input.baseUrl(), "接口地址");
        try {
            URI uri = URI.create(baseUrl);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) { throw ApiException.badRequest("接口地址必须是有效的 HTTP 或 HTTPS 地址，且不能包含凭据或查询参数"); }
        return new PreparedModel(input.kind(), name, provider, baseUrl.replaceAll("/+$", ""), modelName, input.apiKey(), input.enabled() == null || input.enabled());
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank() || value.length() > 2048) throw ApiException.badRequest(label + "不能为空或过长");
        return value.trim();
    }

    public record ModelInput(String kind, String name, String provider, String baseUrl, String modelName, String apiKey, Boolean enabled, String testToken) {
        public ModelInput(String kind, String name, String provider, String baseUrl, String modelName, String apiKey, Boolean enabled) {
            this(kind, name, provider, baseUrl, modelName, apiKey, enabled, null);
        }
    }
    public record ModelTestRequest(String modelId, ModelInput input) {}
    public record ModelTestResult(String testToken, long latencyMs, String message, long expiresAt) {}
    private record PreparedModel(String kind, String name, String provider, String baseUrl, String modelName, String apiKey, boolean enabled) {}
    public record ModelView(String id, String kind, String name, String provider, String baseUrl, String modelName,
                            boolean hasApiKey, boolean enabled, int version, boolean isDefault) {}
    public record ModelConnection(String id, String kind, String baseUrl, String modelName, String apiKey, int version, boolean enabled) {}
}
