package vn.coreplatform.demo.approval;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Đồng bộ metadata mẫu khi và chỉ khi runtime chạy profile demo/test. */
@Component
@Profile({"demo", "test"})
class DemoApprovalMetadata implements ApplicationRunner {
  private final JdbcTemplate jdbc;

  DemoApprovalMetadata(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override public void run(ApplicationArguments args) {
    jdbc.update("""
        insert into platform.resource_descriptor(name,storage_mode,owner_module,record_count,schema_version,resource_type)
        values ('Approval Request','DOMAIN','approval-domain',0,'v1','approval-request')
        on conflict(resource_type) do update set name=excluded.name,storage_mode=excluded.storage_mode,
          owner_module=excluded.owner_module,schema_version=excluded.schema_version,updated_at=now()
        """);
  }
}
