package vn.coreplatform.kernel;

import java.sql.Connection;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.coreplatform.AbstractApiTest;

import static org.assertj.core.api.Assertions.assertThat;

/** E2-S02: GUC tenant được đặt khi lease và bị reset khi connection trả pool — không rò rỉ giữa các request. */
class TenantDataSourceLeakTest extends AbstractApiTest {
  @Autowired DataSource dataSource;

  @Test void dataSourceIsWrappedByTenantAwareDecorator() {
    assertThat(dataSource).isInstanceOf(TenantAwareDataSource.class);
  }

  @Test void tenantGuCIsSetOnLeaseAndClearedOnReturn() throws Exception {
    var tenant = jdbc.queryForObject("select id::text from platform.tenant where tenant_key='default'", String.class);

    TenantContext.set(UUID.fromString(tenant));
    String duringLease;
    try (Connection connection = dataSource.getConnection()) {
      duringLease = tenantGuC(connection);
    }
    assertThat(duringLease).isEqualTo(tenant);

    TenantContext.clear();
    try (Connection reused = dataSource.getConnection()) {
      assertThat(tenantGuC(reused))
          .as("connection tái sử dụng từ pool không được mang tenant của request trước")
          .isEmpty();
    }
  }

  private String tenantGuC(Connection connection) throws Exception {
    try (var statement = connection.createStatement();
         var result = statement.executeQuery("select current_setting('core.tenant_id', true)")) {
      result.next();
      return result.getString(1);
    }
  }
}
