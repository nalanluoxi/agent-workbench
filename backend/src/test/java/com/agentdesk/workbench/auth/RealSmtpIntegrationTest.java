package com.agentdesk.workbench.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentdesk.workbench.config.WorkbenchProperties;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "WORKBENCH_RUN_SMTP_TEST", matches = "1")
class RealSmtpIntegrationTest {
    @Test void smtpSenderUsesTlsAndRealSmtpDialogueWithLocalTestServer() throws Exception {
        Path temp = Files.createTempDirectory("workbench-smtp-test-"); Path keyStore = temp.resolve("server.p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        Process key = new ProcessBuilder(keytool, "-genkeypair", "-alias", "local", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12", "-keystore", keyStore.toString(),
                "-storepass", "changeit", "-keypass", "changeit", "-noprompt").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assertThat(key.waitFor(30, TimeUnit.SECONDS)).isTrue(); assertThat(key.exitValue()).isZero();
        KeyStore store = KeyStore.getInstance("PKCS12"); try (var in = Files.newInputStream(keyStore)) { store.load(in, "changeit".toCharArray()); }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); keys.init(store, "changeit".toCharArray());
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); trust.init(store);
        SSLContext serverContext = SSLContext.getInstance("TLS"); serverContext.init(keys.getKeyManagers(), null, null);
        SSLContext clientContext = SSLContext.getInstance("TLS"); clientContext.init(null, trust.getTrustManagers(), null);
        try (SSLServerSocket server = (SSLServerSocket) serverContext.getServerSocketFactory().createServerSocket(0, 1, InetAddress.getByName("localhost"))) {
            server.setSoTimeout(10_000);
            CompletableFuture<List<String>> transcript = CompletableFuture.supplyAsync(() -> serve(server));
            var config = new WorkbenchProperties(temp.toString(), temp.resolve("master.key").toString(), "", "localhost", server.getLocalPort(), "sender@qq.com", "local-test-authorization", "",
                    "127.0.0.1", 6379, "", 15, false, "admin@admin.com", "admin");
            new SmtpMailSender(config, clientContext.getSocketFactory()).sendCode("recipient@example.com", "123456");
            List<String> lines = transcript.get(10, TimeUnit.SECONDS);
            assertThat(lines).contains("EHLO localhost", "AUTH LOGIN", Base64.getEncoder().encodeToString("sender@qq.com".getBytes(StandardCharsets.UTF_8)),
                    Base64.getEncoder().encodeToString("local-test-authorization".getBytes(StandardCharsets.UTF_8)), "MAIL FROM:<sender@qq.com>", "RCPT TO:<recipient@example.com>", "DATA", "QUIT");
            assertThat(lines).anyMatch(line -> line.contains("123456"));
        }
    }
    private List<String> serve(SSLServerSocket server) {
        try (var socket = server.accept(); var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
             var writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII))) {
            socket.setSoTimeout(10_000); List<String> lines = new ArrayList<>();
            send(writer, "220 localhost test SMTP");
            lines.add(reader.readLine()); send(writer, "250-localhost\r\n250 AUTH LOGIN");
            lines.add(reader.readLine()); send(writer, "334 VXNlcm5hbWU6");
            lines.add(reader.readLine()); send(writer, "334 UGFzc3dvcmQ6");
            lines.add(reader.readLine()); send(writer, "235 authenticated");
            lines.add(reader.readLine()); send(writer, "250 sender ok");
            lines.add(reader.readLine()); send(writer, "250 recipient ok");
            lines.add(reader.readLine()); send(writer, "354 send data");
            String line; while ((line = reader.readLine()) != null && !line.equals(".")) lines.add(line);
            send(writer, "250 queued"); lines.add(reader.readLine());
            return lines;
        } catch (Exception e) { throw new IllegalStateException("本机 SMTP 测试服务失败", e); }
    }
    private void send(BufferedWriter writer, String line) throws java.io.IOException { writer.write(line + "\r\n"); writer.flush(); }
}
