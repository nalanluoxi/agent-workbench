package com.agentdesk.workbench.models;

import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

public final class ModelErrorMessage {
    private ModelErrorMessage() {}

    public static IllegalStateException failure(String action, Throwable error) {
        Throwable cause = root(error);
        return new IllegalStateException(action + "失败：" + describe(cause), cause);
    }

    static String describe(Throwable error) {
        if (error instanceof AuthenticationException) return "鉴权未通过，请检查 API Key 和接口权限";
        if (error instanceof RateLimitException) return "请求过于频繁或额度不足，请稍后重试并检查账户额度";
        if (error instanceof TimeoutException || error instanceof java.net.http.HttpTimeoutException) return "模型服务响应超时，请稍后重试";
        if (error instanceof UnknownHostException) return "无法解析模型服务地址，请检查 Base URL 或网络 DNS";
        if (error instanceof ConnectException) return "无法建立网络连接，请检查模型服务地址、代理和本机网络";
        if (error instanceof HttpException http) return httpMessage(http.statusCode(), safeProviderMessage(http.getMessage()));
        String message = safeProviderMessage(error.getMessage());
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("401") || lower.contains("unauthorized") || lower.contains("invalid api key")) return "鉴权未通过，请检查 API Key";
        if (lower.contains("403") || lower.contains("forbidden")) return "当前 API Key 没有调用该模型的权限";
        if (lower.contains("429") || lower.contains("rate limit")) return "请求过于频繁或额度不足，请稍后重试";
        if (lower.contains("timeout") || lower.contains("timed out")) return "模型服务响应超时，请稍后重试";
        if (lower.equals("closed") || lower.contains("connection closed")) return "模型连接提前关闭，系统已自动重试；请重新发送，若持续发生请检查代理或模型服务";
        if (lower.contains("connection reset") || lower.contains("broken pipe") || lower.contains("unexpected end")) return "模型连接被中断，请稍后重试";
        return message.isBlank() ? "未知错误，请查看服务日志" : message;
    }

    private static String httpMessage(int status, String detail) {
        String prefix = switch (status) {
            case 400 -> "请求参数或模型标识不兼容";
            case 401 -> "鉴权未通过，请检查 API Key";
            case 403 -> "当前 API Key 没有调用该模型的权限";
            case 404 -> "接口地址或模型不存在";
            case 408 -> "模型服务等待请求超时";
            case 429 -> "请求过于频繁或额度不足";
            default -> status >= 500 ? "模型服务暂时异常（HTTP " + status + "）" : "模型请求异常（HTTP " + status + "）";
        };
        return detail.isBlank() ? prefix : prefix + "：" + detail;
    }

    private static Throwable root(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException) && current.getCause() != null) current = current.getCause();
        return current;
    }

    private static String safeProviderMessage(String raw) {
        if (raw == null) return "";
        String value = raw.replaceAll("(?i)(bearer\\s+|api[_-]?key[=:\\s]+)[A-Za-z0-9._-]+", "$1***")
                .replaceAll("[\\r\\n\\t]+", " ").trim();
        return value.length() > 240 ? value.substring(0, 240) + "…" : value;
    }
}
