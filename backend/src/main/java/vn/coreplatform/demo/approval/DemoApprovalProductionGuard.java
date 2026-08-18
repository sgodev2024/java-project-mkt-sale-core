package vn.coreplatform.demo.approval;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Fail-safe cho deployment Production đã từng chạy bản có sample domain: metadata
 * demo bị loại khỏi catalog mỗi lần khởi động, dữ liệu bảng được giữ để rollback.
 */
@Component
@Profile("!demo & !test")
class DemoApprovalProductionGuard implements ApplicationRunner {
  private final JdbcTemplate jdbc;

  DemoApprovalProductionGuard(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override public void run(ApplicationArguments args) {
    jdbc.update("delete from platform.resource_descriptor where resource_type='approval-request'");
    jdbc.update("delete from platform.module where module_key='approval-domain'");
  }
}
