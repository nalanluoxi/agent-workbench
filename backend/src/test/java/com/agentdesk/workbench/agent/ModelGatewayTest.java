package com.agentdesk.workbench.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentdesk.workbench.models.OpenAiCompatibleClients;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModelGatewayTest {
    @Test void gatewayUsesManagedOpenAiCompatibleClients() {
        assertThat(new ModelGateway(new OpenAiCompatibleClients())).isNotNull();
    }

    @Test void enabledDatabaseToolIsVisibleEvenWithoutRouteCandidate() {
        ModelGateway gateway = new ModelGateway(new OpenAiCompatibleClients());

        assertThat(gateway.toolSpecifications("CHAT", Set.of("MYSQL"), Set.of()))
                .extracting(specification -> specification.name())
                .containsExactly("database_operation");
    }
}
