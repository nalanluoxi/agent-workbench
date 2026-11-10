package com.agentdesk.workbench.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class IntentRouterServiceTest {
    private final IntentRouterService router = new IntentRouterService();

    @Test void recognizesConcreteTableQueryWithoutDatabaseKeyword() {
        IntentRouterService.RouteDecision decision = router.route(
                "重新查询 crawler.crawl_site_config 前十行数据", "BUILD", List.of(), List.of(), true, true);

        assertThat(decision.intent()).isEqualTo("MYSQL_READ");
        assertThat(decision.candidates()).containsExactly("builtin-mysql");
    }

    @Test void recognizesNaturalLanguageTableDiscovery() {
        IntentRouterService.RouteDecision decision = router.route(
                "看一下 crawler 有什么表", "BUILD", List.of(), List.of(), true, true);

        assertThat(decision.intent()).isEqualTo("MYSQL_READ");
    }

    @Test void carriesDatabaseIntentForShortFollowUp() {
        IntentRouterService.RouteDecision decision = router.route(
                "再查前 10 行", "BUILD", List.of(), List.of(), true, true, "MYSQL_READ");

        assertThat(decision.intent()).isEqualTo("MYSQL_READ");
    }

    @Test void recognizesCreateTableAsMutation() {
        IntentRouterService.RouteDecision decision = router.route(
                "在 crawler 写一个表 test_crawler", "BUILD", List.of(), List.of(), true, true);

        assertThat(decision.intent()).isEqualTo("MYSQL_MUTATE");
        assertThat(decision.candidates()).containsExactly("builtin-mysql");
    }

    @Test void recognizesNaturalLanguageMultiRowInsertAsMutation() {
        IntentRouterService.RouteDecision decision = router.route(
                "在nalantest表加10条测试数据", "BUILD", List.of(), List.of(), true, true);

        assertThat(decision.intent()).isEqualTo("MYSQL_MUTATE");
        assertThat(decision.candidates()).containsExactly("builtin-mysql");
    }

    @Test void carriesCreateTableIntentForFieldListReply() {
        IntentRouterService.RouteDecision decision = router.route(
                "id,name,created", "BUILD", List.of(), List.of(), true, true, "MYSQL_MUTATE");

        assertThat(decision.intent()).isEqualTo("MYSQL_MUTATE");
        assertThat(decision.candidates()).containsExactly("builtin-mysql");
    }

    @Test void ordinaryConversationRemainsChatOnly() {
        IntentRouterService.RouteDecision decision = router.route(
                "你好，帮我介绍一下这个项目", "CHAT", List.of(), List.of(), true, true);

        assertThat(decision.intent()).isEqualTo("CHAT_ONLY");
    }
}
