package vn.coreplatform.kernel;

import java.util.ArrayList;
import java.util.List;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * E1-S02: module boundary verification. Kernel/shared là nền, permission là PDP hợp đồng
 * dùng chung; các module nghiệp vụ không được chạm nội bộ của nhau. Fixture vi phạm cố ý
 * phải làm rule fail — chứng minh rule thật sự bắt được lỗi, không phải rule suông.
 */
class ModuleBoundaryTest {
  static final List<String> MODULES = List.of("identity", "permission", "dynamicresource", "filemanagement", "controlplane", "audit", "eventing", "jobs", "demo", "webhook");
  static JavaClasses productionClasses;

  @BeforeAll static void importProductionCode() {
    productionClasses = new ClassFileImporter()
        .withImportOption(new ImportOption.DoNotIncludeTests())
        .importPackages("vn.coreplatform", "vn.sgodata");
  }

  static List<ArchRule> forbiddenPairs() {
    var rules = new ArrayList<ArchRule>();
    for (var from : MODULES) for (var to : MODULES) {
      // permission (PDP), audit, eventing, jobs là hợp đồng dùng chung
      if (from.equals(to) || to.equals("permission") || to.equals("audit") || to.equals("eventing") || to.equals("jobs") || to.equals("webhook")) continue;
      rules.add(noClasses().that().resideInAPackage(".." + from + "..")
          .should().dependOnClassesThat().resideInAPackage(".." + to + "..")
          .because("module chỉ được phụ thuộc kernel/shared/security/permission/audit, không chạm module khác"));
    }
    return rules;
  }

  @Test void moduleBoundariesHoldInProductionCode() {
    for (var rule : forbiddenPairs()) rule.check(productionClasses);
  }

  @Test void kernelMustNotDependOnBusinessModules() {
    noClasses().that().resideInAPackage("..kernel..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "..identity..", "..permission..", "..dynamicresource..", "..filemanagement..", "..controlplane..", "..audit..", "..eventing..", "..demo..")
        .because("kernel phải trung tính nghiệp vụ (risk: kernel phình thành business framework)")
        .check(productionClasses);
  }

  @Test void sharedMustNotDependOnModules() {
    noClasses().that().resideInAPackage("..shared..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "..identity..", "..permission..", "..dynamicresource..", "..filemanagement..", "..controlplane..", "..audit..", "..eventing..", "..demo..", "..kernel..")
        .check(productionClasses);
  }

  @Test void projectModulesUseOnlyPublishedCoreContracts() {
    noClasses().that().resideInAPackage("vn.sgodata..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "..identity..", "..dynamicresource..", "..filemanagement..", "..controlplane..", "..demo..", "..jobs..", "..webhook..")
        .because("module dự án chỉ được dùng các hợp đồng Core công khai: kernel, shared, permission, audit và eventing")
        .check(productionClasses);
  }

  @Test void intentionalViolationInFixtureMakesRuleFail() {
    var fixture = new ClassFileImporter().importPackages("vn.coreplatform.boundaryfixture");
    var identityRule = noClasses().that().resideInAPackage("..identity..")
        .should().dependOnClassesThat().resideInAPackage("..dynamicresource..");
    var dynamicResourceRule = noClasses().that().resideInAPackage("..dynamicresource..")
        .should().dependOnClassesThat().resideInAPackage("..identity..");
    assertThatThrownBy(() -> identityRule.check(fixture)).isInstanceOf(AssertionError.class);
    assertThatThrownBy(() -> dynamicResourceRule.check(fixture)).isInstanceOf(AssertionError.class);
  }
}
