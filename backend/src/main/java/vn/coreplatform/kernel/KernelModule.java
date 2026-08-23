package vn.coreplatform.kernel;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class KernelModule implements ModuleContributor {
  @Override public ModuleDescriptor descriptor() {
    return new ModuleDescriptor("kernel", "Platform Kernel", "1.0.0", List.of(),
        List.of("module-registry", "migration-coordination", "tenant-context"), "Module runtime, migration coordination và tenant context");
  }

  @Override public List<NavigationWorkspaceDescriptor> navigationWorkspaces() {
    return List.of(
        new NavigationWorkspaceDescriptor("home", "Trang chủ", "navigation.section.home", "home", "BUSINESS", 10, ""),
        new NavigationWorkspaceDescriptor("business", "Nghiệp vụ", "navigation.section.business", "apps", "BUSINESS", 20, ""),
        new NavigationWorkspaceDescriptor("system-administration", "Quản trị hệ thống", "navigation.section.systemAdministration", "settings", "ADMIN", 90, "ROLE_PLATFORM_ADMIN"));
  }

  @Override public List<NavigationItemDescriptor> navigationItems() {
    return List.of(
        new NavigationItemDescriptor("core.home", "home", "", "Trang chủ", "navigation.home", "home", "home", "/home", 10, "", "", "", List.of("trang chủ", "tổng quan")),
        new NavigationItemDescriptor("core.runtime", "system-administration", "", "Nền tảng", "navigation.runtime", "layers", "", "", 20, "ROLE_PLATFORM_ADMIN", "", "", List.of("runtime", "module")),
        new NavigationItemDescriptor("core.modules", "system-administration", "core.runtime", "Quản lý module", "navigation.modules", "modules", "modules", "/administration/modules", 21, "ROLE_PLATFORM_ADMIN", "", "", List.of("module", "compatibility", "version")));
  }
}
