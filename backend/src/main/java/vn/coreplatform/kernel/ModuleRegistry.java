package vn.coreplatform.kernel;

import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Kernel module registry (E1-S01, E1-S03). Validate toàn bộ descriptor trước Ready
 * (duplicate key/capability, dependency thiếu, version sai, dependency cycle) và đăng ký
 * reproducible vào platform.module theo thứ tự topological. Sync là idempotent UPSERT
 * theo module_key, giữ nguyên status/metric runtime của deployment hiện tại.
 */
@Component
@Order(0)
public class ModuleRegistry implements InitializingBean, ApplicationRunner {
  static final Logger log = LoggerFactory.getLogger(ModuleRegistry.class);
  private final List<ModuleContributor> contributors;
  private final JdbcTemplate jdbc;
  private List<ModuleDescriptor> orderedModules = List.of();

  public ModuleRegistry(List<ModuleContributor> contributors, JdbcTemplate jdbc) { this.contributors = contributors; this.jdbc = jdbc; }

  public List<ModuleDescriptor> modules() { return orderedModules; }

  @Override public void afterPropertiesSet() {
    orderedModules = List.copyOf(validate(contributors.stream().map(ModuleContributor::descriptor).toList()));
    log.info("Module registry validated {} modules in dependency order: {}", orderedModules.size(), orderedModules.stream().map(ModuleDescriptor::key).toList());
  }

  @Override public void run(ApplicationArguments args) { syncToDatabase(); }

  static List<ModuleDescriptor> validate(List<ModuleDescriptor> input) {
    var byKey = new LinkedHashMap<String, ModuleDescriptor>();
    var capabilityOwner = new HashMap<String, String>();
    for (var descriptor : input) {
      if (!ModuleDescriptor.KEY_PATTERN.matcher(descriptor.key()).matches())
        throw new IllegalStateException("Module key không hợp lệ: '" + descriptor.key() + "'");
      if (!ModuleDescriptor.VERSION_PATTERN.matcher(descriptor.version()).matches())
        throw new IllegalStateException("Module version không tuân semver: " + descriptor.key() + "@" + descriptor.version());
      try {
        if (!CoreCompatibility.supportsCurrent(descriptor.coreVersionRange()))
          throw new IllegalStateException("Module " + descriptor.key() + " không tương thích Core " + CoreCompatibility.CURRENT_API_VERSION + " (requires " + descriptor.coreVersionRange() + ")");
      } catch (IllegalArgumentException invalidRange) {
        throw new IllegalStateException("Module " + descriptor.key() + " khai báo coreVersionRange không hợp lệ: " + descriptor.coreVersionRange(), invalidRange);
      }
      if (byKey.put(descriptor.key(), descriptor) != null)
        throw new IllegalStateException("Duplicate module key: " + descriptor.key());
      for (var capability : descriptor.capabilities()) {
        var owner = capabilityOwner.put(capability, descriptor.key());
        if (owner != null) throw new IllegalStateException("Capability '" + capability + "' bị khai báo bởi cả " + owner + " và " + descriptor.key());
      }
    }
    for (var descriptor : input)
      for (var dependency : descriptor.dependencies())
        if (!byKey.containsKey(dependency))
          throw new IllegalStateException("Module " + descriptor.key() + " phụ thuộc module chưa đăng ký: " + dependency);
    return topologicalOrder(byKey);
  }

  /** Kahn's algorithm; deterministic theo thứ tự khai báo và phát hiện cycle. */
  static List<ModuleDescriptor> topologicalOrder(Map<String, ModuleDescriptor> byKey) {
    var inDegree = new LinkedHashMap<String, Integer>();
    var edges = new HashMap<String, List<String>>();
    byKey.forEach((key, descriptor) -> {
      inDegree.putIfAbsent(key, 0);
      for (var dependency : new LinkedHashSet<>(descriptor.dependencies())) {
        edges.computeIfAbsent(dependency, x -> new ArrayList<>()).add(key);
        inDegree.merge(key, 1, Integer::sum);
      }
    });
    var queue = new ArrayDeque<>(inDegree.entrySet().stream().filter(e -> e.getValue() == 0).map(Map.Entry::getKey).toList());
    var ordered = new ArrayList<ModuleDescriptor>(byKey.size());
    while (!queue.isEmpty()) {
      var current = queue.poll();
      ordered.add(byKey.get(current));
      for (var dependent : edges.getOrDefault(current, List.of())) {
        var remaining = inDegree.merge(dependent, -1, Integer::sum);
        if (remaining == 0) queue.add(dependent);
      }
    }
    if (ordered.size() != byKey.size()) {
      var cyclic = new TreeSet<>(inDegree.keySet());
      ordered.forEach(d -> cyclic.remove(d.key()));
      throw new IllegalStateException("Dependency cycle giữa các module: " + cyclic);
    }
    return ordered;
  }

  void syncToDatabase() {
    var sort = 10;
    for (var descriptor : orderedModules) {
      jdbc.update("""
          insert into platform.module(name, module_key, version, status, description, metric, sort_order)
          values (?, ?, ?, 'HEALTHY', ?, '', ?)
          on conflict (module_key) do update
            set name = excluded.name, version = excluded.version, description = excluded.description, sort_order = excluded.sort_order
          """, descriptor.name(), descriptor.key(), descriptor.version(), descriptor.description(), sort);
      sort += 10;
    }
    log.info("Module registry synced {} descriptors into platform.module", orderedModules.size());
  }
}
