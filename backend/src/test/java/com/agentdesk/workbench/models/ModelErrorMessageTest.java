package com.agentdesk.workbench.models;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;
import org.junit.jupiter.api.Test;

class ModelErrorMessageTest {
    @Test void classifiesCommonProviderFailures() {
        assertThat(ModelErrorMessage.failure("模型测试", new AuthenticationException("bad key")).getMessage()).contains("鉴权未通过");
        assertThat(ModelErrorMessage.failure("模型测试", new RateLimitException("quota")).getMessage()).contains("额度不足");
        assertThat(ModelErrorMessage.failure("模型测试", new TimeoutException("timeout")).getMessage()).contains("响应超时");
        assertThat(ModelErrorMessage.failure("模型测试", new HttpException(503, "busy")).getMessage()).contains("HTTP 503").contains("busy");
    }

    @Test void removesCredentialsFromProviderMessage() {
        String message = ModelErrorMessage.failure("模型测试", new IllegalStateException("Authorization: Bearer secret-token")).getMessage();
        assertThat(message).doesNotContain("secret-token").contains("***");
    }

    @Test void explainsClosedStreamingConnection() {
        assertThat(ModelErrorMessage.failure("模型请求", new IllegalStateException("closed")).getMessage())
                .contains("连接提前关闭").contains("自动重试");
    }
}
