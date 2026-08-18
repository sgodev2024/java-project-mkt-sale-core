package vn.coreplatform.permission;

import java.util.List;
import org.springframework.stereotype.Component;
import vn.coreplatform.kernel.ModuleContributor;
import vn.coreplatform.kernel.ModuleDescriptor;

@Component
public class PermissionModule implements ModuleContributor {
  @Override public ModuleDescriptor descriptor() {
    return new ModuleDescriptor("permission", "Permission", "1.0.0", List.of(),
        List.of("authorization", "access-management"), "PDP/PEP: role, policy, record scope và permission revision");
  }
}
