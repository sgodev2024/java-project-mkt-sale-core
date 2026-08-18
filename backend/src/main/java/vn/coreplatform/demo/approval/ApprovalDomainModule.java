package vn.coreplatform.demo.approval;

import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import vn.coreplatform.kernel.ModuleContributor;
import vn.coreplatform.kernel.ModuleDescriptor;
import vn.coreplatform.kernel.NavigationItemDescriptor;

@Component
@Profile({"demo", "test"})
public class ApprovalDomainModule implements ModuleContributor {
  @Override public ModuleDescriptor descriptor() {
    return new ModuleDescriptor("approval-domain", "Approval Domain", "1.0.0", List.of("permission", "event-outbox", "audit-store"),
        List.of("approval-aggregate", "sample-domain"), "Code-first typed aggregate (Plane 1) — không phụ thuộc Dynamic Resource");
  }

  @Override public List<NavigationItemDescriptor> navigationItems() {
    return List.of(
        new NavigationItemDescriptor(
            "module.approval-domain.demo-group", "business", "", "Nghiệp vụ mẫu", "navigation.demoBusiness", "apps",
            "", "", 20, "", "", "", List.of("demo", "nghiệp vụ mẫu")),
        new NavigationItemDescriptor(
            "module.approval-domain.approvals", "business", "module.approval-domain.demo-group",
            "Đề nghị phê duyệt", "navigation.approvals", "clipboard-check",
            "approvals", "/business/approvals", 21, "", "APPROVAL_REQUEST", "READ",
            List.of("approval", "phê duyệt", "đề nghị", "workflow", "demo")));
  }
}
