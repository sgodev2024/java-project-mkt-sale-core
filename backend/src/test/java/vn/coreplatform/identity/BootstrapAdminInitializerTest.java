package vn.coreplatform.identity;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

class BootstrapAdminInitializerTest {
  @Test void productionFailsFastWithoutStrongBootstrapPassword() {
    var jdbc = mock(JdbcTemplate.class);
    var encoder = mock(PasswordEncoder.class);
    var environment = new MockEnvironment();
    environment.setActiveProfiles("production");

    assertThatThrownBy(() -> new BootstrapAdminInitializer(jdbc, encoder, environment, "").run())
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("CORE_BOOTSTRAP_ADMIN_PASSWORD");
    assertThatThrownBy(() -> new BootstrapAdminInitializer(jdbc, encoder, environment, "Core@2026").run())
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("không được dùng giá trị demo");
    verifyNoInteractions(jdbc, encoder);
  }

  @Test void productionClaimsAdminWithConfiguredSecret() throws Exception {
    var jdbc = mock(JdbcTemplate.class);
    var encoder = mock(PasswordEncoder.class);
    var environment = new MockEnvironment();
    environment.setActiveProfiles("production");
    when(encoder.encode("Strong-Production-Secret-2026")).thenReturn("{argon2}hash");

    new BootstrapAdminInitializer(jdbc, encoder, environment, "Strong-Production-Secret-2026").run();

    verify(jdbc).update("update identity.account set password_hash=?,password_algo='ARGON2ID',password_changed_at=now() where email='admin@core.local'", "{argon2}hash");
  }
}
