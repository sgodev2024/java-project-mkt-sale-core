package vn.coreplatform.kernel;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Menu manifest do module sở hữu. Item không có viewKey/route là GROUP; item còn lại
 * là PAGE. Permission là cặp resource/action và luôn được backend đánh giá fail-closed.
 */
public record NavigationItemDescriptor(
    String key,
    String workspaceKey,
    String parentKey,
    String label,
    String labelKey,
    String icon,
    String viewKey,
    String route,
    int sortOrder,
    String requiredAuthority,
    String permissionResource,
    String permissionAction,
    String visibilityMode,
    List<String> keywords) {
  public static final Pattern KEY_PATTERN = Pattern.compile("(?:core|module|customer)\\.[a-z0-9][a-z0-9.-]{1,159}");
  public static final Pattern VIEW_PATTERN = Pattern.compile("[a-z][a-z0-9-]{1,79}");

  public NavigationItemDescriptor {
    key = text(key);
    workspaceKey = text(workspaceKey);
    parentKey = text(parentKey);
    label = text(label);
    labelKey = text(labelKey);
    icon = text(icon);
    viewKey = text(viewKey);
    route = text(route);
    requiredAuthority = text(requiredAuthority);
    permissionResource = text(permissionResource);
    permissionAction = text(permissionAction);
    visibilityMode = text(visibilityMode).toUpperCase();
    if (visibilityMode.isBlank()) visibilityMode = "ACCESS";
    keywords = keywords == null ? List.of() : keywords.stream().map(NavigationItemDescriptor::text).filter(x -> !x.isBlank()).distinct().toList();
  }

  /**
   * Giữ tương thích source với contributor v1.0. ACCESS cho phép System Administrator
   * đi qua permission của menu quản trị; ASSIGNMENT luôn đòi quyền/nhiệm vụ thực tế.
   */
  public NavigationItemDescriptor(
      String key, String workspaceKey, String parentKey, String label, String labelKey, String icon,
      String viewKey, String route, int sortOrder, String requiredAuthority,
      String permissionResource, String permissionAction, List<String> keywords) {
    this(key, workspaceKey, parentKey, label, labelKey, icon, viewKey, route, sortOrder,
        requiredAuthority, permissionResource, permissionAction, "ACCESS", keywords);
  }

  public boolean group() { return viewKey.isBlank() && route.isBlank(); }
  public boolean assignmentScoped() { return "ASSIGNMENT".equals(visibilityMode); }
  private static String text(String value) { return value == null ? "" : value.trim(); }
}
