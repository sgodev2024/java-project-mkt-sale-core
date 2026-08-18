package vn.coreplatform.navigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import vn.coreplatform.kernel.NavigationItemDescriptor;
import vn.coreplatform.permission.PermissionService;

class NavigationVisibilityPolicyTest {
  private final PermissionService permissions = mock(PermissionService.class);
  private final Authentication authentication = mock(Authentication.class);
  private final NavigationVisibilityPolicy policy = new NavigationVisibilityPolicy(permissions);

  @Test void systemAdministratorCannotBypassAssignmentCapability() {
    var item = assignmentItem();
    when(permissions.scopeExplicit(authentication, "WORK_ITEM", "READ_ASSIGNED"))
        .thenReturn(new PermissionService.Decision(false, "NO_EXPLICIT_POLICY", false));

    assertThat(policy.canRender(authentication, item, true)).isFalse();
    verify(permissions).scopeExplicit(authentication, "WORK_ITEM", "READ_ASSIGNED");
  }

  @Test void assignmentPageIsVisibleWhenAccountHasExactCapability() {
    var item = assignmentItem();
    when(permissions.scopeExplicit(authentication, "WORK_ITEM", "READ_ASSIGNED"))
        .thenReturn(new PermissionService.Decision(true, "EXPLICIT_POLICY_ALLOW", false));

    assertThat(policy.canRender(authentication, item, false)).isTrue();
  }

  @Test void ordinaryAccessPageKeepsAdministratorDiscoveryBehavior() {
    var item = new NavigationItemDescriptor(
        "core.modules", "system-administration", "", "Quản lý module", "navigation.modules", "M",
        "modules", "/administration/modules", 20, "ROLE_PLATFORM_ADMIN", "MODULE", "READ", List.of());

    assertThat(policy.canRender(authentication, item, true)).isTrue();
    verifyNoInteractions(permissions);
  }

  private NavigationItemDescriptor assignmentItem() {
    return new NavigationItemDescriptor(
        "module.workflow.my-work", "business", "", "Công việc của tôi", "navigation.myWork", "W",
        "my-work", "/business/my-work", 20, "", "WORK_ITEM", "READ_ASSIGNED", "ASSIGNMENT", List.of());
  }
}
