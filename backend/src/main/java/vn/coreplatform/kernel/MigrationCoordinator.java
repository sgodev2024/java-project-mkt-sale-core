package vn.coreplatform.kernel;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Migration coordinator (E1-S04): mọi instance khởi động phải giữ PostgreSQL advisory lock
 * trong suốt quá trình migrate. Hai instance bật đồng thời bị serialize — instance sau chờ
 * (lock_timeout 60s rồi fail to ra sớm) và thấy schema đã migrate nên no-op.
 *
 * Chạy dưới dạng BeanFactoryPostProcessor (sau khi Environment đầy đủ — kể cả
 * DynamicPropertySource của test — nhưng trước khi bất kỳ bean nào tạo) để Flyway migrate
 * xong với credential migration riêng (DB_MIGRATION_*) trước khi runtime pool (DB_USER) mở.
 */
@Component
public class MigrationCoordinator implements BeanFactoryPostProcessor, EnvironmentAware, Ordered {
  static final Logger log = LoggerFactory.getLogger(MigrationCoordinator.class);
  public static final int ADVISORY_LOCK_KEY = 0x434F5245; // "CORE"

  private Environment environment;

  @Override public void setEnvironment(Environment environment) { this.environment = environment; }
  @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }

  @Override public void postProcessBeanFactory(org.springframework.beans.factory.config.ConfigurableListableBeanFactory beanFactory) {
    var url = environment.getProperty("spring.datasource.url");
    if (url == null || !environment.getProperty("core.migration.enabled", Boolean.class, true)) return;
    var user = orFallback(environment.getProperty("core.migration.user"), environment.getProperty("spring.datasource.username"));
    var password = orFallback(environment.getProperty("core.migration.password"), environment.getProperty("spring.datasource.password"));
    long applied = migrate(url, user, password);
    log.info("Migration coordinator finished: {} migrations applied", applied);
  }

  static String orFallback(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }

  public static long migrate(String url, String user, String password) {
    try (Connection lock = DriverManager.getConnection(url, user, password)) {
      lock.createStatement().execute("set lock_timeout = '60s'");
      lock.createStatement().execute("select pg_advisory_lock(" + ADVISORY_LOCK_KEY + ")");
      try {
        var result = Flyway.configure().dataSource(url, user, password).load().migrate();
        return result.migrationsExecuted;
      } finally {
        lock.createStatement().execute("select pg_advisory_unlock(" + ADVISORY_LOCK_KEY + ")");
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Migration coordination failed against " + url, e);
    }
  }
}
