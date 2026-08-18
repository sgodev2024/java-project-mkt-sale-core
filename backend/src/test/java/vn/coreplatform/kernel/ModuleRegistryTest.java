package vn.coreplatform.kernel;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModuleRegistryTest {

  private static ModuleDescriptor descriptor(String key, List<String> dependencies, List<String> capabilities) {
    return new ModuleDescriptor(key, "Module " + key, "1.0.0", dependencies, capabilities, "test");
  }

  @Test
  void validGraphReturnsDependencyFirstOrder() {
    var ordered = ModuleRegistry.validate(List.of(
        descriptor("control-plane", List.of("dynamic-resource", "permission"), List.of("console")),
        descriptor("dynamic-resource", List.of("permission"), List.of("crud")),
        descriptor("permission", List.of(), List.of("authz")),
        descriptor("kernel", List.of(), List.of("registry"))));
    assertThat(ordered.stream().map(ModuleDescriptor::key).toList())
        .containsExactly("permission", "kernel", "dynamic-resource", "control-plane");
  }

  @Test
  void duplicateModuleKeyFailsStartup() {
    assertThatThrownBy(() -> ModuleRegistry.validate(List.of(
        descriptor("kernel", List.of(), List.of("a")),
        descriptor("kernel", List.of(), List.of("b")))))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("Duplicate module key");
  }

  @Test
  void duplicateCapabilityFailsStartup() {
    assertThatThrownBy(() -> ModuleRegistry.validate(List.of(
        descriptor("module-a", List.of(), List.of("crud")),
        descriptor("module-b", List.of(), List.of("crud")))))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("Capability 'crud'");
  }

  @Test
  void unknownDependencyFailsStartup() {
    assertThatThrownBy(() -> ModuleRegistry.validate(List.of(
        descriptor("module-a", List.of("ghost-module"), List.of("a")))))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("ghost-module");
  }

  @Test
  void dependencyCycleFailsStartup() {
    assertThatThrownBy(() -> ModuleRegistry.validate(List.of(
        descriptor("module-a", List.of("module-b"), List.of("a")),
        descriptor("module-b", List.of("module-a"), List.of("b")))))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("cycle");
  }

  @Test
  void malformedVersionAndKeyAreRejected() {
    assertThatThrownBy(() -> ModuleRegistry.validate(List.of(new ModuleDescriptor("kernel", "Kernel", "1.0", List.of(), List.of("a"), "d"))))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("semver");
    assertThatThrownBy(() -> ModuleRegistry.validate(List.of(new ModuleDescriptor("Bad_Key", "Kernel", "1.0.0", List.of(), List.of("a"), "d"))))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("Module key");
  }

  @Test
  void incompatibleOrMalformedCoreRangeFailsStartup() {
    assertThatThrownBy(() -> ModuleRegistry.validate(List.of(new ModuleDescriptor(
        "future-module", "Future", "1.0.0", List.of(), List.of("future"), "d", ">=2.0.0"))))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("không tương thích Core");
    assertThatThrownBy(() -> ModuleRegistry.validate(List.of(new ModuleDescriptor(
        "bad-range", "Bad", "1.0.0", List.of(), List.of("bad"), "d", "~1.1"))))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("coreVersionRange");
  }
}
