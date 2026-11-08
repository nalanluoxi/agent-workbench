package com.agentdesk.workbench.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentdesk.workbench.core.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class WorkflowDefinitionTest {
    private final WorkflowDefinition definition = new WorkflowDefinition(new ObjectMapper());

    @Test void builtInStagesAreLoadedAndSnapshotPreservesRules() {
        var current = definition.current();
        assertThat(current.stages()).extracting(WorkflowDefinition.Stage::id).containsExactly("understand", "validate", "execute", "summarize");
        assertThat(definition.fromSnapshot(definition.snapshot(current))).isEqualTo(current);
        definition.requireExecutionAllowed(current, true);
    }

    @Test void unknownRulesAndDisabledWritesFailClosed() {
        String snapshot = definition.snapshot(definition.current());
        assertThatThrownBy(() -> definition.fromSnapshot(snapshot.replace("connection_and_operation_valid", "untrusted_rule")))
                .isInstanceOf(ApiException.class);
        var readOnly = definition.fromSnapshot(snapshot.replace("\"allow_write\":true", "\"allow_write\":false"));
        definition.requireExecutionAllowed(readOnly, false);
        assertThatThrownBy(() -> definition.requireExecutionAllowed(readOnly, true)).isInstanceOf(ApiException.class);
    }
}
