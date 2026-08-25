package vn.coreplatform.identity;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@Order(100)
class BootstrapAdminInitializer implements CommandLineRunner {
  private final JdbcTemplate jdbc;
  private final PasswordEncoder encoder;
  private final Environment environment;
  private final String bootstrapPassword;

  BootstrapAdminInitializer(JdbcTemplate jdbc, PasswordEncoder encoder, Environment environment,
                            @Value("${core.bootstrap-admin-password:}") String bootstrapPassword) {
    this.jdbc = jdbc;
    this.encoder = encoder;
    this.environment = environment;
    this.bootstrapPassword = bootstrapPassword;
  }

  @Override public void run(String... args) {
    boolean production = Arrays.asList(environment.getActiveProfiles()).contains("production");
    if (production && (bootstrapPassword.isBlank() || "Core@2026".equals(bootstrapPassword)))
      throw new IllegalStateException("Production yêu cầu CORE_BOOTSTRAP_ADMIN_PASSWORD mạnh và không được dùng giá trị demo");
    if (!bootstrapPassword.isBlank()) {
      var encoded = encoder.encode(bootstrapPassword);
      if (production)
        jdbc.update("update identity.account set password_hash=?,password_algo='ARGON2ID',password_changed_at=now() where email='admin@core.local' and must_change_password=true", encoded);
      else
        jdbc.update("update identity.account set password_hash=?,password_algo='ARGON2ID',password_changed_at=now() where email='admin@core.local'", encoded);
    }
  }
}
