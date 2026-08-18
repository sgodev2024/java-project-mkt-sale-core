package vn.coreplatform.audit;

import java.util.List;
import org.springframework.stereotype.Component;
import vn.coreplatform.kernel.ModuleContributor;
import vn.coreplatform.kernel.ModuleDescriptor;

@Component
public class AuditModule implements ModuleContributor {
  @Override public ModuleDescriptor descriptor() {
    return new ModuleDescriptor("audit-store", "Audit Store", "1.0.0", List.of(),
        List.of("audit-trail", "tamper-evidence"), "Audit append-only, hash chain, checkpoint và retention");
  }
}
