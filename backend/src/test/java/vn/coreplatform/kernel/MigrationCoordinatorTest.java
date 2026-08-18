package vn.coreplatform.kernel;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E1-S04 + E2-S04: hai instance khởi động đồng thời trên database trống không chạy
 * migration xung đột (advisory lock serialize — instance sau chờ rồi no-op),
 * chạy lại trên DB đã migrate là no-op (upgrade path an toàn), lock được giải phóng.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MigrationCoordinatorTest {
  @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  static String url() { return POSTGRES.getJdbcUrl(); }
  static String user() { return POSTGRES.getUsername(); }
  static String password() { return POSTGRES.getPassword(); }

  @Test @Order(1)
  void concurrentFreshStartupsRunNoConflictingMigration() throws Exception {
    var executor = Executors.newFixedThreadPool(2);
    List<Future<Long>> results;
    try {
      results = executor.invokeAll(List.of(
          (Callable<Long>) () -> MigrationCoordinator.migrate(url(), user(), password()),
          (Callable<Long>) () -> MigrationCoordinator.migrate(url(), user(), password())));
    } finally { executor.shutdownNow(); }
    var applied = results.stream().map(future -> {
      try { return future.get(60, TimeUnit.SECONDS); }
      catch (Exception e) { throw new IllegalStateException(e); }
    }).toList();
      // đúng một instance thực thi migration, instance kia chờ lock rồi no-op
      assertThat(applied.stream().mapToLong(Long::longValue).sum()).isEqualTo(19);
    assertThat(applied).contains(0L);

    try (Connection connection = DriverManager.getConnection(url(), user(), password())) {
      var duplicates = connection.createStatement().executeQuery(
          "select count(*) from (select version, count(*) c from flyway_schema_history where version is not null group by version having count(*) > 1) d");
      duplicates.next();
      assertThat(duplicates.getLong(1)).as("không migration nào được chạy hai lần").isZero();

      var distinct = connection.createStatement().executeQuery(
          "select count(distinct version) from flyway_schema_history where version is not null");
      distinct.next();
      assertThat(distinct.getLong(1)).isEqualTo(19);

      var lockFree = connection.createStatement().executeQuery("select pg_try_advisory_lock(" + MigrationCoordinator.ADVISORY_LOCK_KEY + ")");
      lockFree.next();
      assertThat(lockFree.getBoolean(1)).as("advisory lock phải được giải phóng").isTrue();
      connection.createStatement().execute("select pg_advisory_unlock(" + MigrationCoordinator.ADVISORY_LOCK_KEY + ")");
    }
  }

  @Test @Order(2)
  void migratingAlreadyMigratedDatabaseIsNoOp() {
    assertThat(MigrationCoordinator.migrate(url(), user(), password())).isZero();
  }
}
