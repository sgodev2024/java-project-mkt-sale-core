package vn.coreplatform.permission;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.coreplatform.AbstractApiTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * E4-S03: PDP fail-closed — policy hỏng/missing luôn Deny (403, không 500), kèm security audit;
 * PEP interceptor chặn trước controller; cache decision tự vô hiệu khi revision đổi (E4-S02).
 */
class PepFailClosedTest extends AbstractApiTest {

  /** Tạo user KHÔNG có role/policy nào — mọi request phải Deny. */
  private String orphanToken(String email) {
    jdbc.update("insert into identity.account(id,tenant_id,email,display_name,password_hash,password_algo,role) select ?,t.id,?,?,?,'ARGON2ID','APPLICATION_USER' from platform.tenant t where t.tenant_key='default' on conflict do nothing",
        UUID.randomUUID(), email, "Orphan", encoder.encode("OrphanPass@2026x"));
    try { return login(email, "OrphanPass@2026x"); }
    catch (Exception e) { throw new IllegalStateException(e); }
  }

  @Test
  void accountWithoutAnyPolicyIsDeniedEverywhere() throws Exception {
    var token = orphanToken("e4-orphan-" + suffix() + "@test.local");
    mvc.perform(get("/api/v1/dynamic/definitions").with(bearer(token)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    mvc.perform(get("/api/v1/files").with(bearer(token))).andExpect(status().isForbidden());
  }

  @Test
  void brokenPdpStateFailsClosedInsteadOf500() throws Exception {
    // scenario "PDP hỏng": tenant thiếu permission_revision row (dữ liệu inconsistent)
    var tenantKey = "e4-norev-" + suffix();
    jdbc.update("insert into platform.tenant(tenant_key,name) values(?,?) on conflict (tenant_key) do nothing", tenantKey, tenantKey);
    var email = "e4-norev-" + suffix() + "@test.local";
    var password = "NoRevPass@2026x";
    var tenantId = jdbc.queryForObject("select id from platform.tenant where tenant_key=?", UUID.class, tenantKey);
    jdbc.update("insert into identity.account(id,tenant_id,email,display_name,password_hash,password_algo,role) values(?,?,?,?,?,'ARGON2ID','APPLICATION_USER')",
        UUID.randomUUID(), tenantId, email, "NoRev", encoder.encode(password));

    var token = login(email, password);
    // phải là 403 Deny + security audit, KHÔNG PHẢI 500
    mvc.perform(get("/api/v1/files").with(bearer(token))).andExpect(status().isForbidden());
    var audits = jdbc.queryForObject("select count(*) from audit.event where action='POLICY_EVALUATION_ERROR' and result='FAILED'", Integer.class);
    assertThat(audits).isGreaterThanOrEqualTo(1);
  }

  @Test
  void cacheInvalidatesWhenPermissionRevisionChanges() throws Exception {
    var email = "e4-cache-" + suffix() + "@test.local";
    seedDefaultTenantAccount(email, "CacheUser@2026x");
    var token = login(email, "CacheUser@2026x");
    // application-user có dynamic-record-owner -> definitions list vẫn Deny (không có DYNAMIC_DEFINITION)
    mvc.perform(get("/api/v1/dynamic/definitions").with(bearer(token))).andExpect(status().isForbidden());

    // tạo role + policy DYNAMIC_DEFINITION READ rồi bind — không restart, không delay
    var admin = adminToken();
    var roleBody = mvc.perform(post("/api/v1/access/roles").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"code\":\"e4-grant-%s\",\"name\":\"E4 Grant\"}".formatted(suffix())))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    var roleId = json.readTree(roleBody).get("id").asText();
    var policyBody = mvc.perform(post("/api/v1/access/policies").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"code\":\"e4-def-read-%s\",\"resourceType\":\"DYNAMIC_DEFINITION\",\"action\":\"READ\",\"effect\":\"ALLOW\"}".formatted(suffix())))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    var policyId = json.readTree(policyBody).get("id").asText();

    var accountId = jdbc.queryForObject("select id from identity.account where email=?", UUID.class, email);
    var tenantId = jdbc.queryForObject("select tenant_id from identity.account where email=?", UUID.class, email);
    jdbc.update("insert into identity.role_policy(tenant_id,role_id,policy_id) values(?,?,?)", tenantId, UUID.fromString(roleId), UUID.fromString(policyId));
    jdbc.update("insert into identity.account_role(tenant_id,account_id,role_id) values(?,?,?)", tenantId, accountId, UUID.fromString(roleId));
    jdbc.update("update identity.permission_revision set revision=revision+1 where tenant_id=?", tenantId);

    // decision mới phải có hiệu lực ngay (cache keyed theo permission revision)
    mvc.perform(get("/api/v1/dynamic/definitions").with(bearer(token))).andExpect(status().isOk());
  }
}
