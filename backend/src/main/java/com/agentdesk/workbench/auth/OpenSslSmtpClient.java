package com.agentdesk.workbench.auth;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

final class OpenSslSmtpClient {
    private static final int MAX_LINE = 8 * 1024;
    private final Path executable;

    OpenSslSmtpClient() { this(Path.of("/usr/bin/openssl")); }
    OpenSslSmtpClient(Path executable) { this.executable = executable; }

    void send(String host, int port, String user, String password, String recipient, String code) throws IOException {
        validateTarget(host, port);
        if (!Files.isExecutable(executable)) throw new IOException("OpenSSL executable is unavailable");
        ProcessBuilder builder = new ProcessBuilder(executable.toString(), "s_client", "-quiet",
                "-connect", host + ":" + port, "-servername", host);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().keySet().removeIf(OpenSslSmtpClient::isSecretEnvironmentKey);
        Process process = builder.start();
        Thread watchdog = new Thread(() -> terminateAfterTimeout(process), "smtp-openssl-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.US_ASCII));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.US_ASCII))) {
            expect(reader, 220);
            command(writer, reader, "EHLO localhost", 250);
            command(writer, reader, "AUTH LOGIN", 334);
            command(writer, reader, base64(user), 334);
            command(writer, reader, base64(password), 235);
            command(writer, reader, "MAIL FROM:<" + user + ">", 250);
            command(writer, reader, "RCPT TO:<" + recipient + ">", 250, 251);
            command(writer, reader, "DATA", 354);
            writeLine(writer, "From: <" + user + ">");
            writeLine(writer, "To: <" + recipient + ">");
            writeLine(writer, "Subject: Your verification code");
            writeLine(writer, "MIME-Version: 1.0");
            writeLine(writer, "Content-Type: text/plain; charset=UTF-8");
            writeLine(writer, "");
            writeLine(writer, "Your verification code is " + code + ". It expires in 5 minutes.");
            writeLine(writer, ".");
            writer.flush();
            expect(reader, 250);
            command(writer, reader, "QUIT", 221);
            if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IOException("OpenSSL SMTP process did not exit");
            if (process.exitValue() != 0) throw new IOException("OpenSSL SMTP process failed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("OpenSSL SMTP process interrupted", e);
        } finally {
            watchdog.interrupt();
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static void validateTarget(String host, int port) throws IOException {
        if (host == null || !host.matches("[A-Za-z0-9.-]{1,253}") || host.startsWith("-") || port < 1 || port > 65535) {
            throw new IOException("invalid SMTP target");
        }
    }

    private static boolean isSecretEnvironmentKey(String key) {
        String normalized = key.toUpperCase(Locale.ROOT);
        return normalized.contains("PASSWORD") || normalized.contains("SECRET")
                || normalized.contains("TOKEN") || normalized.endsWith("_KEY") || normalized.contains("API_KEY");
    }

    private static void terminateAfterTimeout(Process process) {
        try {
            Thread.sleep(25_000);
            if (process.isAlive()) process.destroyForcibly();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void command(BufferedWriter writer, BufferedReader reader, String value, int... expected) throws IOException {
        writeLine(writer, value);
        writer.flush();
        expect(reader, expected);
    }

    private static void writeLine(BufferedWriter writer, String value) throws IOException {
        writer.write(value);
        writer.write("\r\n");
    }

    private static void expect(BufferedReader reader, int... expected) throws IOException {
        String line = readLine(reader);
        if (line == null || line.length() < 3 || !line.substring(0, 3).chars().allMatch(Character::isDigit)) {
            throw new IOException("invalid SMTP response");
        }
        int code = Integer.parseInt(line.substring(0, 3));
        while (line.length() > 3 && line.charAt(3) == '-') {
            line = readLine(reader);
            if (line == null) throw new IOException("incomplete SMTP response");
        }
        for (int candidate : expected) if (candidate == code) return;
        throw new IOException("SMTP command rejected with status " + code);
    }

    private static String readLine(BufferedReader reader) throws IOException {
        StringBuilder value = new StringBuilder();
        int next;
        while ((next = reader.read()) != -1) {
            if (next == '\n') break;
            if (next != '\r') value.append((char) next);
            if (value.length() > MAX_LINE) throw new IOException("SMTP response line is too long");
        }
        if (next == -1 && value.isEmpty()) return null;
        return value.toString();
    }
}
