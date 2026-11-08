package com.agentdesk.workbench.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.StringReader;
import org.junit.jupiter.api.Test;

class ModelGatewayTest {
    private final ModelGateway gateway = new ModelGateway(new ObjectMapper());

    @Test void collectsStreamedTextAndFragmentedToolArguments() throws Exception {
        String stream = "data: {\"choices\":[{\"delta\":{\"content\":\"你好\",\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"function\":{\"name\":\"database_\",\"arguments\":\"{\\\"key\\\":\"}}]}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"name\":\"operation\",\"arguments\":\"\\\"x\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n"
                + "data: [DONE]\n\n";
        StringBuilder visible = new StringBuilder();
        var result = gateway.parseStream(new BufferedReader(new StringReader(stream)), visible::append);
        assertThat(visible.toString()).isEqualTo("你好");
        assertThat(result.calls()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo("database_operation");
            assertThat(call.arguments()).isEqualTo("{\"key\":\"x\"}");
        });
    }

    @Test void truncatedModelStreamCannotBeReportedAsCompleted() {
        String stream = "data: {\"choices\":[{\"delta\":{\"content\":\"部分结果\"}}]}\n\n";
        assertThatThrownBy(() -> gateway.parseStream(new BufferedReader(new StringReader(stream)), ignored -> {}))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("中断");
    }
}
