package vn.coreplatform.controlplane;

import java.util.List;
import org.springframework.stereotype.Component;
import vn.coreplatform.kernel.ModuleContributor;
import vn.coreplatform.kernel.ModuleDescriptor;
import vn.coreplatform.kernel.NavigationItemDescriptor;

@Component
public class ControlPlaneModule implements ModuleContributor {
  @Override public ModuleDescriptor descriptor() {
    return new ModuleDescriptor("control-plane", "Control Plane", "1.0.0",
        List.of("local-identity", "permission", "dynamic-resource", "file-management"),
        List.of("admin-console"), "API quản trị cho admin console");
  }

  @Override public List<NavigationItemDescriptor> navigationItems() {
    return List.of(
        new NavigationItemDescriptor("core.resources", "system-administration", "core.runtime", "Tài nguyên mở rộng", "navigation.resources", "database", "resources", "/administration/resources", 22, "ROLE_PLATFORM_ADMIN", "", "", List.of("resource", "dynamic", "schema")),
        new NavigationItemDescriptor("core.security", "system-administration", "", "Tổ chức & truy cập", "navigation.security", "shield", "", "", 30, "ROLE_PLATFORM_ADMIN", "", "", List.of("security", "access", "organization")),
        new NavigationItemDescriptor("core.users", "system-administration", "core.security", "Người dùng", "navigation.users", "users", "users", "/administration/users", 31, "ROLE_PLATFORM_ADMIN", "", "", List.of("user", "account", "employee")),
        new NavigationItemDescriptor("core.organizations", "system-administration", "core.security", "Cơ cấu tổ chức", "navigation.organizations", "building", "organizations", "/administration/organizations", 32, "ROLE_PLATFORM_ADMIN", "", "", List.of("organization", "department", "phòng ban")),
        new NavigationItemDescriptor("core.access", "system-administration", "core.security", "Vai trò & phân quyền", "navigation.access", "shield", "access", "/administration/access", 33, "ROLE_PLATFORM_ADMIN", "", "", List.of("role", "policy", "permission")),
        new NavigationItemDescriptor("core.operations", "system-administration", "", "Vận hành", "navigation.operations", "activity", "", "", 40, "ROLE_PLATFORM_ADMIN", "", "", List.of("operations", "job", "event")),
        new NavigationItemDescriptor("core.activity", "system-administration", "core.operations", "Events & Jobs", "navigation.activity", "activity", "activity", "/administration/activity", 41, "ROLE_PLATFORM_ADMIN", "", "", List.of("event", "outbox", "job", "schedule")),
        new NavigationItemDescriptor("core.files", "system-administration", "core.operations", "Tệp tin", "navigation.files", "files", "files", "/administration/files", 42, "ROLE_PLATFORM_ADMIN", "", "", List.of("file", "storage", "upload")),
        new NavigationItemDescriptor("core.settings", "system-administration", "", "Cấu hình hệ thống", "navigation.settings", "settings", "settings", "/administration/settings", 90, "ROLE_PLATFORM_ADMIN", "", "", List.of("settings", "environment", "deployment")));
  }
}
