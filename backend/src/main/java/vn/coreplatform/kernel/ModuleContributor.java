package vn.coreplatform.kernel;

import java.util.List;

/**
 * Một module tích hợp vào kernel bằng cách expose đúng một descriptor.
 * Spring discovery tự thu thập mọi ModuleContributor bean; module tương lai chỉ cần
 * thêm contributor mới mà không sửa kernel.
 */
public interface ModuleContributor {
  ModuleDescriptor descriptor();

  /**
   * Module có thể đóng góp workspace/menu mà không sửa Core shell. Default rỗng giữ
   * compatibility với module chỉ cung cấp backend capability.
   */
  default List<NavigationWorkspaceDescriptor> navigationWorkspaces() { return List.of(); }
  default List<NavigationItemDescriptor> navigationItems() { return List.of(); }
}
