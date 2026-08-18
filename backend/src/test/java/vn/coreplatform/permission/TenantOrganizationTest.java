package vn.coreplatform.permission;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import vn.coreplatform.AbstractApiTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** E3-S01: tenant lifecycle + organization không thể tạo/di chuyển chéo tenant (chặn cả ở DB). */
class TenantOrganizationTest extends AbstractApiTest {
  String admin;

  @Test
  void tenantCreationIsPlatformAdminOnlyAndProvisionsBaseline() throws Exception {
    admin = adminToken();
    var key = "e3-" + suffix();
    mvc.perform(post("/api/v1/access/tenants").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"key\":\"%s\",\"name\":\"E3 Tenant %s\"}".formatted(key, key)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.key").value(key));

    // baseline provisioning cho tenant mới
    assertThat(jdbc.queryForObject("select count(*) from identity.role r join platform.tenant t on t.id=r.tenant_id where t.tenant_key=? and r.code='application-user'", Integer.class, key)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from identity.permission_revision pr join platform.tenant t on t.id=pr.tenant_id where t.tenant_key=?", Integer.class, key)).isEqualTo(1);

    var plain = "e3-plain-" + suffix() + "@test.local";
    seedDefaultTenantAccount(plain, "PlainUser@2026x");
    mvc.perform(get("/api/v1/access/tenants").with(bearer(login(plain, "PlainUser@2026x"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void organizationsAreScopedToCallerTenant() throws Exception {
    admin = adminToken();
    var code = "e3-org-" + suffix();
    mvc.perform(post("/api/v1/access/organizations").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"code\":\"%s\",\"name\":\"Head Office\"}".formatted(code)))
        .andExpect(status().isCreated());
    mvc.perform(post("/api/v1/access/organizations").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"code\":\"%s-child\",\"name\":\"Branch\",\"parentCode\":\"%s\"}".formatted(code, code)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.parentCode").value(code));
    mvc.perform(post("/api/v1/access/organizations").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"code\":\"%s-dup\",\"name\":\"Trùng\",\"parentCode\":\"org-khong-ton-tai\"}".formatted(code)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("PARENT_NOT_FOUND"));
  }

  @Test
  void crossTenantParentIsInvisibleToCallerTenant() throws Exception {
    // tạo org thuộc tenant acme (tenant B) rồi thử dùng làm parent từ tenant default
    var tenantB = jdbc.queryForObject("select id from platform.tenant where tenant_key='acme'", UUID.class);
    if (tenantB == null) { jdbc.update("insert into platform.tenant(tenant_key,name) values('acme','ACME Corp') on conflict do nothing"); tenantB = jdbc.queryForObject("select id from platform.tenant where tenant_key='acme'", UUID.class); }
    var foreignCode = "e3-foreign-" + suffix();
    jdbc.update("insert into identity.organization(tenant_id,code,name) values(?,?,'ACME Secret Org')", tenantB, foreignCode);

    admin = adminToken();
    mvc.perform(post("/api/v1/access/organizations").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"code\":\"e3-self-%s\",\"name\":\"Local\",\"parentCode\":\"%s\"}".formatted(suffix(), foreignCode)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("PARENT_NOT_FOUND"));
  }

  @Test
  void databaseBlocksAccountToForeignOrganizationAndOrgMove() {
    var tenantB = acmeTenantId();
    var foreignOrg = UUID.randomUUID();
    jdbc.update("insert into identity.organization(id,tenant_id,code,name) values(?,?,'e3-fk-%s','Foreign')".formatted(suffix()), foreignOrg, tenantB);
    var defaultTenant = jdbc.queryForObject("select id from platform.tenant where tenant_key='default'", UUID.class);

    // account thuộc tenant default không thể trỏ tới org của tenant khác: composite FK chặn
    assertThatThrownBy(() -> jdbc.update("""
        insert into identity.account(id,tenant_id,email,display_name,password_hash,role,org_id)
        values(?,?,?,?,'{argon2}$x','APPLICATION_USER',?)
        """, UUID.randomUUID(), defaultTenant, "e3-fk-" + suffix() + "@test.local", "FK Test", foreignOrg))
        .isInstanceOf(DataIntegrityViolationException.class);

    // org đã bị account tham chiếu không thể chuyển sang tenant khác
    var localOrg = UUID.randomUUID();
    jdbc.update("insert into identity.organization(id,tenant_id,code,name) values(?,?,?,'Local Org')", localOrg, defaultTenant, "e3-local-" + suffix());
    var accountId = UUID.randomUUID();
    jdbc.update("insert into identity.account(id,tenant_id,email,display_name,password_hash,role,org_id) values(?,?,?,?,'{argon2}$x','APPLICATION_USER',?)",
        accountId, defaultTenant, "e3-anchor-" + suffix() + "@test.local", "Anchor", localOrg);
    assertThatThrownBy(() -> jdbc.update("update identity.organization set tenant_id=? where id=?", tenantB, localOrg))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private UUID acmeTenantId() {
    jdbc.update("insert into platform.tenant(tenant_key,name) values('acme','ACME Corp') on conflict (tenant_key) do nothing");
    return jdbc.queryForObject("select id from platform.tenant where tenant_key='acme'", UUID.class);
  }
}
