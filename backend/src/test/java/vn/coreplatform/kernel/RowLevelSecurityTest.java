package vn.coreplatform.kernel;

import java.sql.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * E2-S03 RLS test kit: chứng minh isolation ở TẦNG DATABASE, không phụ thuộc WHERE clause
 * của ứng dụng. Chạy với vai trò runtime core_app (không superuser/owner/BYPASSRLS) qua SET ROLE:
 * read/write/list của tenant khác đều bị chặn, thiếu tenant context thì fail-closed (0 dòng).
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RowLevelSecurityTest {
  @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
  static Connection admin;
  static UUID tenantA;
  static UUID tenantB;
  static UUID definitionA;
  static UUID recordOfTenantB;

  static String sql(String query, Object... params) throws SQLException {
    try (var statement = admin.prepareStatement(query)) {
      for (int i = 0; i < params.length; i++) statement.setObject(i + 1, params[i]);
      var result = statement.executeQuery();
      return result.next() ? result.getString(1) : null;
    }
  }
  static long count(String query, Object... params) throws SQLException {
    try (var statement = admin.prepareStatement(query)) {
      for (int i = 0; i < params.length; i++) statement.setObject(i + 1, params[i]);
      var result = statement.executeQuery();
      result.next();
      return result.getLong(1);
    }
  }

  @BeforeAll static void migrateAndSeed() throws Exception {
    admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    // vai trò runtime phải tồn tại TRƯỚC khi V6 chạy để nhận DML grant
    admin.createStatement().execute("create role core_app with login password 'runtime-pass' nosuperuser nocreatedb nocreaterole nobypassrls");
    admin.createStatement().execute("grant connect on database " + POSTGRES.getDatabaseName() + " to core_app");
    MigrationCoordinator.migrate(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());

    admin.createStatement().execute("insert into platform.tenant(tenant_key, name) values ('rls-a', 'Tenant A')");
    admin.createStatement().execute("insert into platform.tenant(tenant_key, name) values ('rls-b', 'Tenant B')");
    tenantA = UUID.fromString(sql("select id from platform.tenant where tenant_key='rls-a'"));
    tenantB = UUID.fromString(sql("select id from platform.tenant where tenant_key='rls-b'"));
    var adminAccount = UUID.fromString(sql("select id from identity.account where email='admin@core.local'"));

    definitionA = UUID.randomUUID();
    admin.createStatement().execute("insert into dynamic_resource.definition(id, tenant_id, resource_key, name, schema_json, created_by) values ('"
        + definitionA + "', '" + tenantA + "', 'rls-doc', 'RLS Doc', '{\"fields\":[]}', '" + adminAccount + "')");
    for (int i = 0; i < 2; i++)
      admin.createStatement().execute("insert into dynamic_resource.record(id, tenant_id, definition_id, data, created_by) values ('"
          + UUID.randomUUID() + "', '" + tenantA + "', '" + definitionA + "', '{}', '" + adminAccount + "')");
    recordOfTenantB = UUID.randomUUID();
    admin.createStatement().execute("insert into dynamic_resource.definition(id, tenant_id, resource_key, name, schema_json, created_by) values ('"
        + UUID.randomUUID() + "', '" + tenantB + "', 'rls-doc', 'RLS Doc B', '{\"fields\":[]}', '" + adminAccount + "')");
    admin.createStatement().execute("insert into dynamic_resource.record(id, tenant_id, definition_id, data, created_by) values ('"
        + recordOfTenantB + "', '" + tenantB + "', (select id from dynamic_resource.definition where tenant_id='" + tenantB + "'), '{}', '" + adminAccount + "')");
  }

  @AfterAll static void closeAdmin() throws SQLException { admin.close(); }

  @AfterEach void resetRole() throws SQLException {
    admin.createStatement().execute("reset role");
    admin.createStatement().execute("select set_config('core.tenant_id', '', false)");
  }

  private void actAsTenantA() throws SQLException {
    admin.createStatement().execute("set role core_app");
    admin.createStatement().execute("select set_config('core.tenant_id', '" + tenantA + "', false)");
  }

  @Test @Order(1) void runtimeRoleHasNoOwnerOrBypass() throws SQLException {
    admin.createStatement().execute("set role core_app");
    var attributes = sql("select rolsuper || '/' || rolbypassrls from pg_roles where rolname = 'core_app'");
    assertThat(attributes).isEqualTo("false/false");
    var ownership = count("select count(*) from pg_tables where tableowner = current_user");
    assertThat(ownership).as("runtime role không sở hữu bảng nào").isZero();
    assertThatThrownBy(() -> admin.createStatement().execute("create table public.runtime_hack(id int)"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("permission denied");
  }

  @Test @Order(2) void crossTenantReadIsBlocked() throws SQLException {
    actAsTenantA();
    assertThat(count("select count(*) from dynamic_resource.record")).isEqualTo(2);
    assertThat(count("select count(*) from dynamic_resource.record where id = ?", recordOfTenantB)).isZero();
    assertThat(count("select count(*) from dynamic_resource.definition where tenant_id = ?", tenantB)).isZero();
  }

  @Test @Order(3) void crossTenantWriteIsBlocked() throws SQLException {
    actAsTenantA();
    try (var insert = admin.prepareStatement(
        "insert into dynamic_resource.record(id, tenant_id, definition_id, data, created_by) values (?, ?, ?, '{}', (select id from identity.account where email='admin@core.local'))")) {
      insert.setObject(1, UUID.randomUUID());
      insert.setObject(2, tenantB);
      insert.setObject(3, definitionA);
      assertThatThrownBy(insert::executeUpdate).isInstanceOf(SQLException.class).hasMessageContaining("row-level security");
    }
  }

  @Test @Order(4) void crossTenantUpdateAndDeleteAreNoOp() throws SQLException {
    actAsTenantA();
    try (var update = admin.prepareStatement("update dynamic_resource.record set data = '{\"hacked\":true}' where id = ?")) {
      update.setObject(1, recordOfTenantB);
      assertThat(update.executeUpdate()).isZero();
    }
    try (var delete = admin.prepareStatement("delete from dynamic_resource.record where id = ?")) {
      delete.setObject(1, recordOfTenantB);
      assertThat(delete.executeUpdate()).isZero();
    }
    admin.createStatement().execute("reset role");
    assertThat(count("select count(*) from dynamic_resource.record where id = ?", recordOfTenantB))
        .as("record của tenant B phải nguyên vẹn").isEqualTo(1);
  }

  @Test @Order(5) void missingTenantContextFailsClosed() throws SQLException {
    admin.createStatement().execute("set role core_app");
    assertThat(count("select count(*) from dynamic_resource.record")).isZero();
    assertThat(count("select count(*) from dynamic_resource.definition")).isZero();
  }
}
