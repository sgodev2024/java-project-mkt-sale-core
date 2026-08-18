package vn.coreplatform.kernel;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Định nghĩa tĩnh của một Core module (E1-S01). Mỗi module khai báo key, version,
 * dependency tới module khác và capability mà nó sở hữu. Registry kiểm tra tính hợp lệ
 * của toàn bộ descriptor trước khi application Ready: dependency thiếu, key/capability
 * trùng, version sai định dạng hoặc dependency cycle đều làm startup fail.
 */
public record ModuleDescriptor(String key, String name, String version, List<String> dependencies, List<String> capabilities, String description, String coreVersionRange) {
  public static final Pattern KEY_PATTERN = Pattern.compile("[a-z][a-z0-9-]{1,99}");
  public static final Pattern VERSION_PATTERN = Pattern.compile("\\d+\\.\\d+\\.\\d+(-[A-Za-z0-9.]+)?");
  public ModuleDescriptor(String key, String name, String version, List<String> dependencies, List<String> capabilities, String description) {
    this(key, name, version, dependencies, capabilities, description, "*");
  }

  public ModuleDescriptor {
    key = key == null ? "" : key.trim();
    name = name == null ? "" : name.trim();
    version = version == null ? "" : version.trim();
    dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
    capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    description = description == null ? "" : description.trim();
    coreVersionRange = coreVersionRange == null || coreVersionRange.isBlank() ? "*" : coreVersionRange.trim();
  }
}
