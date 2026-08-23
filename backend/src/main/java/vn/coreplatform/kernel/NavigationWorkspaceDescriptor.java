package vn.coreplatform.kernel;

import java.util.regex.Pattern;

/** Adapter nội bộ cho section điều hướng cấp cao của application shell hợp nhất. */
public record NavigationWorkspaceDescriptor(
    String key,
    String label,
    String labelKey,
    String icon,
    String category,
    int sortOrder,
    String requiredAuthority) {
  public static final Pattern KEY_PATTERN = Pattern.compile("[a-z][a-z0-9-]{1,79}");

  public NavigationWorkspaceDescriptor {
    key = text(key);
    label = text(label);
    labelKey = text(labelKey);
    icon = text(icon);
    category = text(category).toUpperCase(java.util.Locale.ROOT);
    requiredAuthority = text(requiredAuthority);
  }

  private static String text(String value) { return value == null ? "" : value.trim(); }
}
