package vn.coreplatform.kernel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Registry code-first cho navigation manifest. Workspace descriptor v1.0 được dùng như
 * section adapter; startup fail nếu section/item trùng, namespace sai, parent không hợp lệ,
 * route không an toàn hoặc cây điều hướng sâu quá Section -> Group -> Page.
 */
@Component
public class NavigationRegistry implements InitializingBean {
  private static final Logger log = LoggerFactory.getLogger(NavigationRegistry.class);
  private final List<ModuleContributor> contributors;
  private List<WorkspaceRegistration> workspaces = List.of();
  private List<ItemRegistration> items = List.of();
  private String revision = "";

  public NavigationRegistry(List<ModuleContributor> contributors) { this.contributors = contributors; }

  public record WorkspaceRegistration(String ownerModule, NavigationWorkspaceDescriptor descriptor) {}
  public record ItemRegistration(String ownerModule, NavigationItemDescriptor descriptor) {}

  public List<WorkspaceRegistration> workspaces() { return workspaces; }
  public List<ItemRegistration> items() { return items; }
  public String revision() { return revision; }

  @Override public void afterPropertiesSet() {
    var validated = validate(contributors);
    workspaces = validated.workspaces();
    items = validated.items();
    revision = hash(workspaces, items);
    log.info("Navigation registry validated {} workspaces and {} items, revision={}", workspaces.size(), items.size(), revision);
  }

  record Validated(List<WorkspaceRegistration> workspaces, List<ItemRegistration> items) {}

  static Validated validate(List<ModuleContributor> contributors) {
    var modules = new LinkedHashSet<String>();
    var workspaceByKey = new LinkedHashMap<String, WorkspaceRegistration>();
    var itemByKey = new LinkedHashMap<String, ItemRegistration>();
    for (var contributor : contributors) {
      var module = contributor.descriptor().key();
      if (!modules.add(module)) throw new IllegalStateException("Duplicate module contributor khi tạo navigation: " + module);
      for (var workspace : contributor.navigationWorkspaces()) {
        validateWorkspace(workspace);
        if (workspaceByKey.put(workspace.key(), new WorkspaceRegistration(module, workspace)) != null)
          throw new IllegalStateException("Duplicate navigation workspace: " + workspace.key());
      }
      for (var item : contributor.navigationItems()) {
        validateItem(module, item);
        if (itemByKey.put(item.key(), new ItemRegistration(module, item)) != null)
          throw new IllegalStateException("Duplicate navigation item: " + item.key());
      }
    }
    for (var registration : itemByKey.values()) {
      var item = registration.descriptor();
      if (!workspaceByKey.containsKey(item.workspaceKey()))
        throw new IllegalStateException("Navigation item " + item.key() + " dùng workspace chưa đăng ký: " + item.workspaceKey());
      if ("home".equals(item.workspaceKey()) && (!"core.home".equals(item.key()) || item.group() || !item.parentKey().isBlank()))
        throw new IllegalStateException("Section home chỉ được chứa page core.home cấp cao nhất: " + item.key());
      if ("core.home".equals(item.key()) && !"home".equals(item.workspaceKey()))
        throw new IllegalStateException("core.home phải tách khỏi section nghiệp vụ và thuộc section home");
      if (!item.parentKey().isBlank()) {
        var parent = itemByKey.get(item.parentKey());
        if (parent == null) throw new IllegalStateException("Navigation parent không tồn tại: " + item.parentKey());
        if (!parent.descriptor().group()) throw new IllegalStateException("Navigation parent phải là GROUP: " + item.parentKey());
        if (!parent.descriptor().workspaceKey().equals(item.workspaceKey()))
          throw new IllegalStateException("Navigation parent khác workspace: " + item.key());
        if (item.group() || !parent.descriptor().parentKey().isBlank())
          throw new IllegalStateException("Navigation tối đa Section -> Group -> Page: " + item.key());
      }
      detectParentCycle(item, itemByKey);
    }
    var orderedWorkspaces = workspaceByKey.values().stream()
        .sorted(Comparator.comparingInt(x -> x.descriptor().sortOrder())).toList();
    var orderedItems = itemByKey.values().stream()
        .sorted(Comparator.comparing((ItemRegistration x) -> x.descriptor().workspaceKey())
            .thenComparingInt(x -> x.descriptor().sortOrder()).thenComparing(x -> x.descriptor().key())).toList();
    return new Validated(List.copyOf(orderedWorkspaces), List.copyOf(orderedItems));
  }

  private static void validateWorkspace(NavigationWorkspaceDescriptor workspace) {
    if (!NavigationWorkspaceDescriptor.KEY_PATTERN.matcher(workspace.key()).matches())
      throw new IllegalStateException("Navigation workspace key không hợp lệ: " + workspace.key());
    if (workspace.label().isBlank() || workspace.labelKey().isBlank())
      throw new IllegalStateException("Navigation workspace thiếu label: " + workspace.key());
    if (!Set.of("BUSINESS", "ADMIN").contains(workspace.category()))
      throw new IllegalStateException("Navigation workspace category không hợp lệ: " + workspace.key());
    if (!workspace.requiredAuthority().isBlank() && !workspace.requiredAuthority().startsWith("ROLE_"))
      throw new IllegalStateException("Navigation workspace authority không hợp lệ: " + workspace.key());
  }

  private static void validateItem(String module, NavigationItemDescriptor item) {
    if (!NavigationItemDescriptor.KEY_PATTERN.matcher(item.key()).matches())
      throw new IllegalStateException("Navigation item key không hợp lệ: " + item.key());
    if (item.key().startsWith("module.") && !item.key().startsWith("module." + module + "."))
      throw new IllegalStateException("Navigation namespace không thuộc module " + module + ": " + item.key());
    if (item.label().isBlank() || item.labelKey().isBlank())
      throw new IllegalStateException("Navigation item thiếu label: " + item.key());
    if (!item.requiredAuthority().isBlank() && !item.requiredAuthority().startsWith("ROLE_"))
      throw new IllegalStateException("Navigation authority không hợp lệ: " + item.key());
    if (item.permissionResource().isBlank() != item.permissionAction().isBlank())
      throw new IllegalStateException("Navigation permission phải có đủ resource/action: " + item.key());
    if (!Set.of("ACCESS", "ASSIGNMENT").contains(item.visibilityMode()))
      throw new IllegalStateException("Navigation visibilityMode không hợp lệ: " + item.key());
    if (item.assignmentScoped() && item.permissionResource().isBlank())
      throw new IllegalStateException("Navigation ASSIGNMENT phải có permission resource/action: " + item.key());
    if (item.group() && item.assignmentScoped())
      throw new IllegalStateException("Navigation ASSIGNMENT chỉ áp dụng cho PAGE: " + item.key());
    if (!item.group()) {
      if (!NavigationItemDescriptor.VIEW_PATTERN.matcher(item.viewKey()).matches())
        throw new IllegalStateException("Navigation viewKey không hợp lệ: " + item.key());
      if (!item.route().matches("/[a-z0-9][a-z0-9/-]{1,159}"))
        throw new IllegalStateException("Navigation route phải là application route nội bộ: " + item.key());
    }
  }

  private static void detectParentCycle(NavigationItemDescriptor start, Map<String, ItemRegistration> all) {
    var visited = new HashSet<String>();
    var current = start;
    while (!current.parentKey().isBlank()) {
      if (!visited.add(current.key())) throw new IllegalStateException("Navigation parent cycle tại: " + start.key());
      current = all.get(current.parentKey()).descriptor();
    }
  }

  private static String hash(List<WorkspaceRegistration> workspaces, List<ItemRegistration> items) {
    try {
      var canonical = workspaces.toString() + "|" + items;
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(canonical.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
    } catch (Exception e) { throw new IllegalStateException("Không thể tạo navigation revision", e); }
  }
}
