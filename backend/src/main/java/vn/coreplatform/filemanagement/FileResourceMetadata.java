package vn.coreplatform.filemanagement;

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
class FileResourceMetadata implements ApplicationRunner {
  private final ResourceRegistry resources;
  private final JdbcTemplate jdbc;

  FileResourceMetadata(ResourceRegistry resources, JdbcTemplate jdbc) {
    this.resources = resources;
    this.jdbc = jdbc;
  }

  @Override public void run(ApplicationArguments args) {
    resources.register(new ResourceDescriptor("file-object", "File Object", "file-management", "DOMAIN", "v1",
        List.of("READ", "CREATE", "DELETE"), "ALWAYS", "INTERNAL"));
    var count = jdbc.queryForObject("select count(*) from files.file_object where status in ('ACTIVE','QUARANTINED')", Long.class);
    resources.synchronizeRecordCount("file-object", count == null ? 0 : count);
  }
}
