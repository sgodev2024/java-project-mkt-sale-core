package vn.coreplatform.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class NavigationRegistryTest {
  @Test void validatesAndOrdersSectionManifest() {
    var validated = NavigationRegistry.validate(List.of(contributor("kernel", List.of(
        workspace("business"), workspace("system-administration")), List.of(
        page("core.home", "business", "", "home", 10),
        group("core.runtime", "system-administration", 20),
        page("core.modules", "system-administration", "core.runtime", "modules", 21)))));
    assertThat(validated.workspaces()).extracting(x -> x.descriptor().key()).containsExactly("business", "system-administration");
    assertThat(validated.items()).hasSize(3);
  }

  @Test void rejectsDuplicateAndForeignModuleNamespace() {
    var duplicate = List.of(
        contributor("kernel", List.of(workspace("business")), List.of(page("core.home", "business", "", "home", 10))),
        contributor("other", List.of(), List.of(page("core.home", "business", "", "home", 20))));
    assertThatThrownBy(() -> NavigationRegistry.validate(duplicate)).hasMessageContaining("Duplicate navigation item");

    var foreign = List.of(contributor("sales", List.of(workspace("business")),
        List.of(page("module.inventory.items", "business", "", "items", 10))));
    assertThatThrownBy(() -> NavigationRegistry.validate(foreign)).hasMessageContaining("namespace không thuộc module");
  }

  @Test void rejectsMissingParentAndUnsafeRoute() {
    var missing = List.of(contributor("kernel", List.of(workspace("business")),
        List.of(page("core.home", "business", "core.missing", "home", 10))));
    assertThatThrownBy(() -> NavigationRegistry.validate(missing)).hasMessageContaining("parent không tồn tại");

    var unsafe = new NavigationItemDescriptor("core.home", "business", "", "Home", "nav.home", "⌂",
        "home", "https://outside.example", 10, "", "", "", List.of());
    assertThatThrownBy(() -> NavigationRegistry.validate(List.of(contributor("kernel", List.of(workspace("business")), List.of(unsafe)))))
        .hasMessageContaining("application route nội bộ");
  }

  @Test void enforcesThreeLevelTreeAndAssignmentVisibilityContract() {
    var nested = List.of(
        group("core.parent", "business", 10),
        new NavigationItemDescriptor("core.child", "business", "core.parent", "Child", "nav.child", "C",
            "", "", 11, "", "", "", List.of()));
    assertThatThrownBy(() -> NavigationRegistry.validate(List.of(contributor("kernel", List.of(workspace("business")), nested))))
        .hasMessageContaining("Section -> Group -> Page");

    var missingPermission = new NavigationItemDescriptor("core.my-work", "business", "", "Công việc của tôi",
        "nav.myWork", "W", "my-work", "/business/my-work", 20, "", "", "", "ASSIGNMENT", List.of());
    assertThatThrownBy(() -> NavigationRegistry.validate(List.of(contributor("kernel", List.of(workspace("business")), List.of(missingPermission)))))
        .hasMessageContaining("ASSIGNMENT phải có permission");

    var assigned = new NavigationItemDescriptor("core.my-work", "business", "", "Công việc của tôi",
        "nav.myWork", "W", "my-work", "/business/my-work", 20, "", "WORK_ITEM", "READ_ASSIGNED", "ASSIGNMENT", List.of());
    assertThat(NavigationRegistry.validate(List.of(contributor("kernel", List.of(workspace("business")), List.of(assigned)))).items()).hasSize(1);

    var assignmentGroup = new NavigationItemDescriptor("core.my-work-group", "business", "", "Việc cá nhân",
        "nav.myWorkGroup", "W", "", "", 20, "", "WORK_ITEM", "READ_ASSIGNED", "ASSIGNMENT", List.of());
    assertThatThrownBy(() -> NavigationRegistry.validate(List.of(contributor("kernel", List.of(workspace("business")), List.of(assignmentGroup)))))
        .hasMessageContaining("ASSIGNMENT chỉ áp dụng cho PAGE");
  }

  private static ModuleContributor contributor(String key,List<NavigationWorkspaceDescriptor> workspaces,List<NavigationItemDescriptor> items) {
    return new ModuleContributor() {
      public ModuleDescriptor descriptor(){return new ModuleDescriptor(key,"Test","1.0.0",List.of(),List.of(),"");}
      public List<NavigationWorkspaceDescriptor> navigationWorkspaces(){return workspaces;}
      public List<NavigationItemDescriptor> navigationItems(){return items;}
    };
  }
  private static NavigationWorkspaceDescriptor workspace(String key) {
    return new NavigationWorkspaceDescriptor(key,key,"section."+key,"W",key.equals("system-administration")?"ADMIN":"BUSINESS",key.equals("business")?10:90,"");
  }
  private static NavigationItemDescriptor page(String key,String workspace,String parent,String view,int order) {
    return new NavigationItemDescriptor(key,workspace,parent,key,"nav."+view,"P",view,"/"+workspace+"/"+view,order,"","","",List.of());
  }
  private static NavigationItemDescriptor group(String key,String workspace,int order) {
    return new NavigationItemDescriptor(key,workspace,"",key,"nav.group","G","","",order,"","","",List.of());
  }
}
