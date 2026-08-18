package vn.coreplatform.navigation;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import vn.coreplatform.kernel.NavigationItemDescriptor;
import vn.coreplatform.permission.PermissionService;

/**
 * Chính sách hiển thị page trong navigation manifest hiệu lực.
 *
 * <p>Đây là discovery policy, không thay thế authorization ở endpoint. FE-BA-13
 * được giữ tại một điểm duy nhất: page ASSIGNMENT luôn cần capability chính xác
 * của tài khoản; quyền wildcard của System Administrator không tạo ra nhiệm vụ.
 */
@Component
public class NavigationVisibilityPolicy {
  private final PermissionService permissions;

  public NavigationVisibilityPolicy(PermissionService permissions) {
    this.permissions = permissions;
  }

  boolean canRender(Authentication auth, NavigationItemDescriptor item, boolean administrator) {
    if (item.group()) return true;
    if (item.assignmentScoped()) {
      return permissions.scopeExplicit(auth, item.permissionResource(), item.permissionAction()).allowed();
    }
    if (item.permissionResource().isBlank()) return true;
    return administrator || permissions.scope(auth, item.permissionResource(), item.permissionAction()).allowed();
  }
}
