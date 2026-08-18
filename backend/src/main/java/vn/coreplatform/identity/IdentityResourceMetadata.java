package vn.coreplatform.identity;

import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import vn.coreplatform.kernel.ResourceDescriptor;
import vn.coreplatform.kernel.ResourceRegistry;

@Component
@Order(100)
class IdentityResourceMetadata implements ApplicationRunner {
  private final ResourceRegistry resources;
  private final JdbcTemplate jdbc;

  IdentityResourceMetadata(ResourceRegistry resources, JdbcTemplate jdbc) {
    this.resources = resources;
    this.jdbc = jdbc;
  }

  @Override public void run(ApplicationArguments args) {
    resources.register(new ResourceDescriptor("service-account", "Service Account", "local-identity", "DOMAIN", "v1",
        List.of("READ", "CREATE", "UPDATE"), "ALWAYS", "RESTRICTED"));
    var count = jdbc.queryForObject("select count(*) from identity.account where account_type='SERVICE' and enabled", Long.class);
    resources.synchronizeRecordCount("service-account", count == null ? 0 : count);
  }
}
