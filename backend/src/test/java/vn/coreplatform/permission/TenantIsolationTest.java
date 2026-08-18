package vn.coreplatform.permission;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import vn.coreplatform.AbstractApiTest;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TenantIsolationTest extends AbstractApiTest {
  static final String TENANT_B_KEY = "acme";
  static final String TENANT_B_PASSWORD = "AcmeOwner@2026";

  String admin;
  String tenantBEmail;
  String resourceKey;
  String tenantARecordId;
  String tenantAFileId;

  @BeforeEach
  void seedTenantADataAndTenantBUser() throws Exception {
    admin = adminToken();
    tenantBEmail = "acme-" + suffix() + "@acme.test";
    provisionTenantBUser();

    resourceKey = "iso-" + suffix();
    mvc.perform(post("/api/v1/dynamic/definitions").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"resourceKey\":\"%s\",\"name\":\"ISO Doc\",\"classification\":\"INTERNAL\",\"schema\":{\"fields\":[{\"key\":\"code\",\"type\":\"string\",\"required\":true}]}}"
                .formatted(resourceKey)))
        .andExpect(status().isCreated());
    tenantARecordId = json.readTree(mvc.perform(post("/api/v1/dynamic/%s/records".formatted(resourceKey)).with(bearer(admin)).contentType(APPLICATION_JSON)
                .content("{\"code\":\"TENANT-A-SECRET\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();

    tenantAFileId = json.readTree(mvc.perform(multipart("/api/v1/files")
                .file(new MockMultipartFile("file", "tenant-a-secret.txt", MediaType.TEXT_PLAIN_VALUE, "tenant A secret".getBytes()))
                .param("classification", "INTERNAL").with(bearer(admin)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
  }

  @Test
  void tenantBUserCannotReadTenantADynamicRecord() throws Exception {
    var tokenB = login(tenantBEmail, TENANT_B_PASSWORD);
    // Tenant B không thấy definition của tenant A nên record không bao giờ truy cập được;
    // cả hai đường đều phải chặn, không rò rỉ dữ liệu tenant A.
    mvc.perform(get("/api/v1/dynamic/%s/records/%s".formatted(resourceKey, tenantARecordId)).with(bearer(tokenB)))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/dynamic/%s/records".formatted(resourceKey)).with(bearer(tokenB)))
        .andExpect(status().isNotFound());
  }

  @Test
  void tenantBUserCannotUseTenantADefinition() throws Exception {
    var tokenB = login(tenantBEmail, TENANT_B_PASSWORD);
    mvc.perform(post("/api/v1/dynamic/%s/records".formatted(resourceKey)).with(bearer(tokenB)).contentType(APPLICATION_JSON)
            .content("{\"code\":\"TENANT-B-INJECT\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.title").value("DEFINITION_NOT_FOUND"));
  }

  @Test
  void tenantBUserCannotDownloadTenantAFile() throws Exception {
    var tokenB = login(tenantBEmail, TENANT_B_PASSWORD);
    mvc.perform(get("/api/v1/files/%s/content".formatted(tenantAFileId)).with(bearer(tokenB)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.title").value("FILE_NOT_FOUND"));
  }

  @Test
  void tenantBUserSeesEmptyFileList() throws Exception {
    var tokenB = login(tenantBEmail, TENANT_B_PASSWORD);
    mvc.perform(get("/api/v1/files").with(bearer(tokenB)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
  }

  private void provisionTenantBUser() {
    jdbc.update("insert into platform.tenant(tenant_key,name) values(?, 'ACME Corp') on conflict (tenant_key) do nothing", TENANT_B_KEY);
    // provisioning giống API createTenant: permission revision + role/policy baseline
    jdbc.update("insert into identity.permission_revision(tenant_id) select id from platform.tenant where tenant_key=? on conflict do nothing", TENANT_B_KEY);
    jdbc.update("""
        insert into identity.role(tenant_id,code,name,system_role)
        select t.id,'application-user','Application User',false from platform.tenant t where t.tenant_key=?
        on conflict do nothing""", TENANT_B_KEY);
    jdbc.update("""
        insert into identity.policy(tenant_id,code,resource_type,action,effect,condition_json)
        select t.id,'dynamic-record-owner','DYNAMIC_RECORD','*','ALLOW','{"ownerOnly":true}'::jsonb from platform.tenant t where t.tenant_key=?
        on conflict do nothing""", TENANT_B_KEY);
    jdbc.update("""
        insert into identity.policy(tenant_id,code,resource_type,action,effect,condition_json)
        select t.id,'file-owner','FILE','*','ALLOW','{"ownerOnly":true}'::jsonb from platform.tenant t where t.tenant_key=?
        on conflict do nothing""", TENANT_B_KEY);
    jdbc.update("""
        insert into identity.role_policy(tenant_id,role_id,policy_id)
        select r.tenant_id,r.id,p.id from identity.role r join identity.policy p on p.tenant_id=r.tenant_id
        where r.tenant_id=(select id from platform.tenant where tenant_key=?) and r.code='application-user' and p.code in ('dynamic-record-owner','file-owner')
        on conflict do nothing""", TENANT_B_KEY);
    var accountId = UUID.randomUUID();
    jdbc.update("""
        insert into identity.account(id,tenant_id,email,display_name,password_hash,password_algo,role)
        select ?,t.id,?,?,?,'ARGON2ID','APPLICATION_USER' from platform.tenant t where t.tenant_key=?
        on conflict do nothing""", accountId, tenantBEmail, "ACME Owner", encoder.encode(TENANT_B_PASSWORD), TENANT_B_KEY);
    jdbc.update("""
        insert into identity.account_role(tenant_id,account_id,role_id)
        select t.id,?,r.id from platform.tenant t join identity.role r on r.tenant_id=t.id and r.code='application-user'
        where t.tenant_key=? and exists(select 1 from identity.account a where a.id=? and a.tenant_id=t.id)
        on conflict do nothing""", accountId, TENANT_B_KEY, accountId);
  }
}
