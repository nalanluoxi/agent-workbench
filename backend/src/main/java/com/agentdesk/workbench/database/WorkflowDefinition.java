package com.agentdesk.workbench.database;

import com.agentdesk.workbench.core.ApiException;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Built-in contract for the database runner; arbitrary user code is never evaluated. */
@Service
public class WorkflowDefinition {
    private static final List<String> STAGES = List.of("understand", "validate", "execute", "summarize");
    private static final List<String> ENTRIES = List.of("user_authenticated", "target_and_operation_known", "connection_and_operation_valid", "execution_outcome_recorded");
    private static final List<String> EXITS = List.of("target_and_operation_known", "connection_and_operation_valid", "execution_outcome_recorded", "result_visible");
    private final ObjectMapper json;
    private final Definition current;

    public WorkflowDefinition(ObjectMapper json) {
        this.json = json;
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setCodePointLimit(32_768);
        try (InputStream in = new ClassPathResource("workflows/数据库操作流程.yaml").getInputStream()) {
            Object raw = new Yaml(new SafeConstructor(options)).load(in);
            this.current = validate(json.convertValue(raw, Definition.class));
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("数据库流程定义无效，服务无法启动", e);
        }
    }

    public Definition current() { return current; }

    public String snapshot(Definition definition) {
        try { return json.writeValueAsString(validate(definition)); }
        catch (JsonProcessingException e) { throw new IllegalStateException("无法保存流程快照", e); }
    }

    public Definition fromSnapshot(String value) {
        if (value == null || value.isBlank()) throw ApiException.conflict("任务缺少流程快照，请重新发起操作");
        try { return validate(json.readValue(value, Definition.class)); }
        catch (JsonProcessingException | IllegalArgumentException e) { throw ApiException.conflict("任务流程快照无效，请重新发起操作"); }
    }

    public void requireExecutionAllowed(Definition definition, boolean write) {
        validate(definition);
        if (write && !definition.stages().get(2).allowWrite()) throw ApiException.forbidden("流程未开放数据库写入");
    }

    private Definition validate(Definition definition) {
        if (definition == null || !"database-operation".equals(definition.name()) || definition.version() < 1
                || !Map.of("MYSQL", "mysql-operations", "REDIS", "redis-operations").equals(definition.skillByTool())
                || definition.stages() == null || definition.stages().size() != STAGES.size()) {
            throw new IllegalArgumentException("不支持的数据库流程定义");
        }
        for (int i = 0; i < STAGES.size(); i++) {
            Stage stage = definition.stages().get(i);
            if (stage == null || !STAGES.get(i).equals(stage.id()) || stage.name() == null || stage.name().isBlank()
                    || !List.of(ENTRIES.get(i)).equals(stage.entryCriteria()) || !List.of(EXITS.get(i)).equals(stage.exitCriteria())
                    || (i != 2 && stage.allowWrite())) {
                throw new IllegalArgumentException("不支持的流程阶段或条件");
            }
        }
        return definition;
    }

    public record Definition(String name, int version, @JsonProperty("skill_by_tool") Map<String, String> skillByTool, List<Stage> stages) {}
    public record Stage(String id, String name, @JsonProperty("allow_write") boolean allowWrite,
                        @JsonProperty("entry_criteria") List<String> entryCriteria, @JsonProperty("exit_criteria") List<String> exitCriteria) {}
}
