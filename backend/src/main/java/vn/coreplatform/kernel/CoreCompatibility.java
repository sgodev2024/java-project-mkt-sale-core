package vn.coreplatform.kernel;

import java.util.regex.Pattern;

/** Minimal semver range gate cho module: *, exact hoặc chuỗi comparator cách nhau bằng space. */
public final class CoreCompatibility {
  public static final String CURRENT_API_VERSION = "1.1.0";
  private static final Pattern VERSION = Pattern.compile("\\d+\\.\\d+\\.\\d+");

  private CoreCompatibility() {}

  public static boolean supportsCurrent(String range) {
    if (range == null || range.isBlank() || "*".equals(range.trim())) return true;
    var current = Version.parse(CURRENT_API_VERSION);
    for (var token : range.trim().split("\\s+")) {
      var operator = token.startsWith(">=") || token.startsWith("<=") ? token.substring(0, 2)
          : token.startsWith(">") || token.startsWith("<") || token.startsWith("=") ? token.substring(0, 1) : "=";
      var rawVersion = token.substring(operator.equals("=") && !token.startsWith("=") ? 0 : operator.length());
      if (!VERSION.matcher(rawVersion).matches()) throw new IllegalArgumentException("Core version range không hợp lệ: " + range);
      int comparison = current.compareTo(Version.parse(rawVersion));
      boolean matches = switch (operator) {
        case ">=" -> comparison >= 0;
        case ">" -> comparison > 0;
        case "<=" -> comparison <= 0;
        case "<" -> comparison < 0;
        default -> comparison == 0;
      };
      if (!matches) return false;
    }
    return true;
  }

  private record Version(int major, int minor, int patch) implements Comparable<Version> {
    static Version parse(String value) {
      var parts = value.split("\\.");
      return new Version(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }
    @Override public int compareTo(Version other) {
      int majorCompare = Integer.compare(major, other.major);
      if (majorCompare != 0) return majorCompare;
      int minorCompare = Integer.compare(minor, other.minor);
      return minorCompare != 0 ? minorCompare : Integer.compare(patch, other.patch);
    }
  }
}
