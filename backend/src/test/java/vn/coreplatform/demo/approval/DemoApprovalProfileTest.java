package vn.coreplatform.demo.approval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Profile;

class DemoApprovalProfileTest {
  @Test void moduleAndControllerAreRestrictedToDemoAndTestProfiles() {
    assertThat(profiles(ApprovalDomainModule.class)).containsExactlyInAnyOrder("demo", "test");
    assertThat(profiles(ApprovalRequestController.class)).containsExactlyInAnyOrder("demo", "test");
  }

  @Test void productionGuardIsActiveOutsideDemoAndTest() {
    assertThat(profiles(DemoApprovalProductionGuard.class)).containsExactly("!demo & !test");
  }

  @Test void productionContextDoesNotCreateApprovalModuleOrController() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.getEnvironment().setActiveProfiles("production");
      context.register(ApprovalDomainModule.class, ApprovalRequestController.class);
      context.refresh();
      assertThat(context.getBeansOfType(ApprovalDomainModule.class)).isEmpty();
      assertThat(context.getBeansOfType(ApprovalRequestController.class)).isEmpty();
    }
  }

  @Test void demoContextCreatesApprovalModuleContributor() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.getEnvironment().setActiveProfiles("demo");
      context.register(ApprovalDomainModule.class);
      context.refresh();
      assertThat(context.getBeansOfType(ApprovalDomainModule.class)).hasSize(1);
    }
  }

  private String[] profiles(Class<?> type) {
    var annotation = type.getAnnotation(Profile.class);
    assertThat(annotation).as("@Profile on " + type.getSimpleName()).isNotNull();
    return Arrays.copyOf(annotation.value(), annotation.value().length);
  }
}
