package vn.coreplatform.kernel;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.coreplatform.AbstractApiTest;

import static org.assertj.core.api.Assertions.assertThat;

/** E1-S03: registration reproducible qua nhiều lần startup, giữ nguyên trạng thái runtime. */
class ModuleRegistrationTest extends AbstractApiTest {
  @Autowired ModuleRegistry registry;
  @Autowired List<ModuleContributor> contributors;

  @Test void registryBootsWithAllContributorsInDependencyOrder() {
    var keys = registry.modules().stream().map(ModuleDescriptor::key).toList();
    assertThat(keys).contains("kernel", "local-identity", "permission", "dynamic-resource", "file-management", "control-plane");
    assertThat(keys.indexOf("permission")).isLessThan(keys.indexOf("dynamic-resource"));
    assertThat(keys.indexOf("dynamic-resource")).isLessThan(keys.indexOf("control-plane"));
  }

  @Test void registrationIsIdempotentAcrossRestarts() {
    registry.run(null);
    registry.run(null);
    for (var descriptor : registry.modules()) {
      var count = jdbc.queryForObject("select count(*) from platform.module where module_key=?", Integer.class, descriptor.key());
      assertThat(count).as("module %s không được đăng ký trùng", descriptor.key()).isEqualTo(1);
    }
  }

  @Test void runtimeStatusSurvivesResync() {
    registry.run(null);
    jdbc.update("update platform.module set status='ATTENTION' where module_key='dynamic-resource'");
    registry.run(null);
    var status = jdbc.queryForObject("select status from platform.module where module_key='dynamic-resource'", String.class);
    assertThat(status).isEqualTo("ATTENTION");
    jdbc.update("update platform.module set status='HEALTHY' where module_key='dynamic-resource'");
  }
}
