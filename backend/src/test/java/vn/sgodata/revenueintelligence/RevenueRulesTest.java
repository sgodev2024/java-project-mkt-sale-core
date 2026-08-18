package vn.sgodata.revenueintelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import vn.coreplatform.shared.ApiExceptionHandler.ApiProblem;

class RevenueRulesTest {
  @Test void lifecycleDoesNotGuessNewWhenHistoryIsIncomplete() {
    assertThat(RevenueImportService.classifyLifecycle(0, false)).isEqualTo("UNKNOWN");
    assertThat(RevenueImportService.classifyLifecycle(0, true)).isEqualTo("NEW");
    assertThat(RevenueImportService.classifyLifecycle(1, false)).isEqualTo("RETURNING");
  }

  @Test void businessModelUsesDeclaredThenEvidence() {
    assertThat(RevenueImportService.classifyBusinessModel("WHOLESALE", "POS", "STORE")).isEqualTo("WHOLESALE");
    assertThat(RevenueImportService.classifyBusinessModel("", "CRM", "B2B_SALES")).isEqualTo("WHOLESALE");
    assertThat(RevenueImportService.classifyBusinessModel("", "POS", "STORE")).isEqualTo("RETAIL");
  }

  @Test void periodIsBounded() {
    assertThatThrownBy(() -> RevenueAnalyticsService.validatePeriod(LocalDate.parse("2024-01-01"), LocalDate.parse("2026-01-01")))
        .isInstanceOf(ApiProblem.class);
  }
}
