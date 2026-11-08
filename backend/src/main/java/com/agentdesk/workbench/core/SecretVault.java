package com.agentdesk.workbench.core;

import com.agentdesk.workbench.config.WorkbenchProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class SecretVault {
    private final SecureRandom random = new SecureRandom();
    private final byte[] key;

    public SecretVault(WorkbenchProperties properties) throws IOException {
        Path path = Path.of(properties.keyFile()).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            Path db = Path.of(properties.dataDir()).toAbsolutePath().normalize().resolve("workbench.db");
            if (Files.exists(db) && Files.size(db) > 0) throw new IOException("主密钥缺失，无法安全读取已有数据");
            Files.createDirectories(path.getParent());
            byte[] generated = new byte[32];
            random.nextBytes(generated);
            Files.writeString(path, Base64.getEncoder().encodeToString(generated), StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW);
            try { Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)); }
            catch (UnsupportedOperationException ignored) { /* File permissions are platform dependent. */ }
        }
        try { key = Base64.getDecoder().decode(Files.readString(path, StandardCharsets.US_ASCII).trim()); }
        catch (IllegalArgumentException e) { throw new IOException("主密钥格式无效", e); }
        if (key.length != 32) throw new IOException("主密钥长度无效");
    }

    public String encrypt(String text) {
        if (text == null || text.isEmpty()) return null;
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(requireKey(), "AES"), new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
            byte[] result = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, result, 0, nonce.length);
            System.arraycopy(encrypted, 0, result, nonce.length, encrypted.length);
            return Base64.getEncoder().encodeToString(result);
        } catch (GeneralSecurityException e) { throw new IllegalStateException("凭据加密失败", e); }
    }

    public String decrypt(String encoded) {
        if (encoded == null || encoded.isEmpty()) return null;
        try {
            byte[] data = Base64.getDecoder().decode(encoded);
            if (data.length < 29) throw new GeneralSecurityException("invalid ciphertext");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(requireKey(), "AES"), new GCMParameterSpec(128, data, 0, 12));
            return new String(cipher.doFinal(data, 12, data.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) { throw new IllegalStateException("无法解密凭据，请检查主密钥", e); }
    }

    public String digest(String purpose, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(requireKey(), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal((purpose + "\0" + value).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) { throw new IllegalStateException("无法生成校验摘要", e); }
    }

    public boolean matches(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] requireKey() {
        return key;
    }
}
