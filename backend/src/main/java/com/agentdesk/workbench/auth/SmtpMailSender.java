package com.agentdesk.workbench.auth;

import com.agentdesk.workbench.config.WorkbenchProperties;
import com.agentdesk.workbench.core.ApiException;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SmtpMailSender implements MailSender {
    private static final Logger LOGGER = LoggerFactory.getLogger(SmtpMailSender.class);
    private final WorkbenchProperties properties;
    private final SSLSocketFactory socketFactory;
    private final OpenSslSmtpClient openSslClient;

    @Autowired
    public SmtpMailSender(WorkbenchProperties properties) {
        this(properties, (SSLSocketFactory) SSLSocketFactory.getDefault(), new OpenSslSmtpClient());
    }
    SmtpMailSender(WorkbenchProperties properties, SSLSocketFactory socketFactory) {
        this(properties, socketFactory, new OpenSslSmtpClient());
    }
    SmtpMailSender(WorkbenchProperties properties, SSLSocketFactory socketFactory, OpenSslSmtpClient openSslClient) {
        this.properties = properties;
        this.socketFactory = socketFactory;
        this.openSslClient = openSslClient;
    }

    @Override
    public void sendCode(String recipient, String code) {
        String user = properties.smtpUser();
        String password = properties.smtpPassword();
        if (user == null || user.isBlank() || password == null || password.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "尚未配置发件邮箱和 SMTP 授权码");
        }
        if (!safeAddress(user) || !safeAddress(recipient)) throw ApiException.badRequest("邮箱格式无效");
        try {
            sendWithJsse(user, password, recipient, code);
        } catch (SmtpTransportException e) {
            LOGGER.warn("SMTP send failed at stage={} host={} port={} cause={}",
                    e.stage, properties.smtpHost(), properties.smtpPort(), e.getCause().getMessage());
            if (!e.tlsHandshakeFailure()) throw sendFailure();
            try {
                openSslClient.send(properties.smtpHost(), properties.smtpPort(), user, password, recipient, code);
                LOGGER.info("SMTP send completed through the local OpenSSL compatibility transport");
            } catch (IOException fallback) {
                LOGGER.warn("SMTP OpenSSL compatibility transport failed host={} port={} cause={}",
                        properties.smtpHost(), properties.smtpPort(), fallback.getMessage());
                throw sendFailure();
            }
        }
    }

    private void sendWithJsse(String user, String password, String recipient, String code) throws SmtpTransportException {
        String stage = "connect";
        try (Socket transport = new Socket()) {
            transport.connect(new InetSocketAddress(properties.smtpHost(), properties.smtpPort()), 10_000);
            try (SSLSocket socket = (SSLSocket) socketFactory.createSocket(
                    transport, properties.smtpHost(), properties.smtpPort(), true)) {
                socket.setSoTimeout(15_000);
                SSLParameters parameters = socket.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                socket.setSSLParameters(parameters);
                stage = "tls-handshake";
                socket.startHandshake();
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII));
                stage = "banner";
                expect(reader, 220);
                stage = "ehlo";
                command(writer, reader, "EHLO localhost", 250);
                stage = "auth";
                command(writer, reader, "AUTH LOGIN", 334);
                stage = "auth-user";
                command(writer, reader, base64(user), 334);
                stage = "auth-password";
                command(writer, reader, base64(password), 235);
                stage = "mail-from";
                command(writer, reader, "MAIL FROM:<" + user + ">", 250);
                stage = "recipient";
                command(writer, reader, "RCPT TO:<" + recipient + ">", 250, 251);
                stage = "data";
                command(writer, reader, "DATA", 354);
                writer.write("From: <" + user + ">\r\nTo: <" + recipient + ">\r\n");
                writer.write("Subject: Your verification code\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n");
                writer.write("Your verification code is " + code + ". It expires in 5 minutes.\r\n.\r\n");
                writer.flush();
                stage = "message";
                expect(reader, 250);
                writer.write("QUIT\r\n");
                writer.flush();
            }
        } catch (IOException e) {
            throw new SmtpTransportException(stage, e);
        }
    }

    private static ApiException sendFailure() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "邮件发送失败，请检查 SMTP 配置后重试");
    }

    private static final class SmtpTransportException extends IOException {
        private final String stage;
        private SmtpTransportException(String stage, IOException cause) { super(cause); this.stage = stage; }
        private boolean tlsHandshakeFailure() { return "tls-handshake".equals(stage) && getCause() instanceof SSLHandshakeException; }
    }

    private static boolean safeAddress(String value) {
        return value.length() <= 254 && value.matches("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void command(BufferedWriter writer, BufferedReader reader, String value, int... expected) throws IOException {
        writer.write(value);
        writer.write("\r\n");
        writer.flush();
        expect(reader, expected);
    }

    private static void expect(BufferedReader reader, int... expected) throws IOException {
        String line = reader.readLine();
        if (line == null || line.length() < 3 || !line.substring(0, 3).chars().allMatch(Character::isDigit)) throw new IOException("invalid SMTP response");
        int code = Integer.parseInt(line.substring(0, 3));
        while (line.length() > 3 && line.charAt(3) == '-') {
            line = reader.readLine();
            if (line == null) throw new IOException("incomplete SMTP response");
        }
        for (int candidate : expected) if (candidate == code) return;
        throw new IOException("SMTP command rejected");
    }
}
