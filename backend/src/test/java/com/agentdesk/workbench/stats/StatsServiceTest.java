package com.agentdesk.workbench.stats;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class StatsServiceTest {
    @Test void sevenDayTrendContainsEveryDayAndBothModes() {
        List<StatsService.TrendPoint> result = StatsService.completeTrends(
                List.of(new StatsService.TrendPoint("2026-11-08", "BUILD", 3, 2)),
                "7d", LocalDate.of(2026, 11, 10));

        assertThat(result).hasSize(14);
        assertThat(result.stream().map(StatsService.TrendPoint::day).distinct())
                .containsExactly("2026-11-04", "2026-11-05", "2026-11-06", "2026-11-07", "2026-11-08", "2026-11-09", "2026-11-10");
        assertThat(result).anySatisfy(point -> {
            assertThat(point.day()).isEqualTo("2026-11-08");
            assertThat(point.mode()).isEqualTo("BUILD");
            assertThat(point.runs()).isEqualTo(3);
        });
        assertThat(result).anySatisfy(point -> {
            assertThat(point.day()).isEqualTo("2026-11-10");
            assertThat(point.mode()).isEqualTo("CHAT");
            assertThat(point.runs()).isZero();
        });
    }
}
