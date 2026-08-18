package vn.coreplatform.permission;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import vn.coreplatform.AbstractApiTest;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PermissionTest extends AbstractApiTest {
  String admin;
  String resourceKey;
  @Autowired PermissionService permissionService;

  @BeforeEach
  void seed() throws Exception {
    admin = adminToken();
    resourceKey = "it-perm-" + suffix();
    mvc.perform(post("/api/v1/dynamic/definitions").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"resourceKey\":\"%s\",\"name\":\"IT Perm Doc\",\"classification\":\"INTERNAL\",\"schema\":{\"fields\":[{\"key\":\"code\",\"type\":\"string\",\"required\":true}]}}"
                .formatted(resourceKey)))
        .andExpect(status().isCreated());
  }

  @Test
  void ownerOnlyUserSeesOnlyOwnRecords() throws Exception {
    var editor = "editor-" + suffix() + "@test.local";
    var editorToken = createUserWithPolicies(editor, "{\"ownerOnly\":true}", null);

    mvc.perform(post("/api/v1/dynamic/%s/records".formatted(resourceKey)).with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"code\":\"ADMIN-OWNED\"}"))
        .andExpect(status().isCreated());

    var created = mvc.perform(post("/api/v1/dynamic/%s/records".formatted(resourceKey)).with(bearer(editorToken)).contentType(APPLICATION_JSON)
            .content("{\"code\":\"EDITOR-OWNED\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    var editorRecordId = json.readTree(created).get("id").asText();

    var list = mvc.perform(get("/api/v1/dynamic/%s/records".formatted(resourceKey)).with(bearer(editorToken)))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    var tree = json.readTree(list);
    assertThat(tree.get("total").asLong()).isEqualTo(1);
    assertThat(tree.get("items").get(0).get("id").asText()).isEqualTo(editorRecordId);
  }

  @Test
  void explicitDenyOverridesAllow() throws Exception {
    var manager = "manager-" + suffix() + "@test.local";
    var managerToken = createUserWithPolicies(manager, null, "DELETE");

    var created = mvc.perform(post("/api/v1/dynamic/%s/records".formatted(resourceKey)).with(bearer(managerToken)).contentType(APPLICATION_JSON)
            .content("{\"code\":\"M-1\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    var recordId = json.readTree(created).get("id").asText();

    mvc.perform(delete("/api/v1/dynamic/%s/records/%s".formatted(resourceKey, recordId)).with(bearer(managerToken)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.title").value("PERMISSION_DENIED"));
  }

  @Test
  void userWithoutAccessAdminPolicyCannotManageAccess() throws Exception {
    var user = "plain-" + suffix() + "@test.local";
    var token = createUserWithPolicies(user, "{\"ownerOnly\":true}", null);

    mvc.perform(get("/api/v1/access/users").with(bearer(token))).andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/access/policies").with(bearer(token))).andExpect(status().isForbidden());
  }

  @Test
  void permissionRevisionIncrementsOnAccessChange() throws Exception {
    var before = jdbc.queryForObject("select pr.revision from identity.permission_revision pr join platform.tenant t on t.id=pr.tenant_id where t.tenant_key='default'", Long.class);
    createUserWithPolicies("rev-" + suffix() + "@test.local", null, null);
    var after = jdbc.queryForObject("select pr.revision from identity.permission_revision pr join platform.tenant t on t.id=pr.tenant_id where t.tenant_key='default'", Long.class);
    assertThat(after).isGreaterThan(before);
  }

  @Test
  void explicitAssignmentScopeDoesNotAcceptPlatformAdminWildcard() {
    var tenantId = jdbc.queryForObject("select id from platform.tenant where tenant_key='default'", UUID.class);
    var accountId = jdbc.queryForObject("select id from identity.account where tenant_id=? and email='admin@core.local'", UUID.class, tenantId);
    var authentication = new UsernamePasswordAuthenticationToken("admin@core.local", "", List.of());
    authentication.setDetails(Map.of("tenantId", tenantId, "accountId", accountId));

    assertThat(permissionService.scope(authentication, "WORK_ITEM", "READ_ASSIGNED").allowed()).isTrue();
    assertThat(permissionService.scopeExplicit(authentication, "WORK_ITEM", "READ_ASSIGNED").allowed()).isFalse();

    var policyId = UUID.randomUUID();
    try {
      jdbc.update("insert into identity.policy(id,tenant_id,code,resource_type,action,effect,condition_json) values(?,?,?,'WORK_ITEM','READ_ASSIGNED','ALLOW','{}')",
          policyId, tenantId, "assigned-work-" + suffix());
      jdbc.update("insert into identity.role_policy(tenant_id,role_id,policy_id) select ?,id,? from identity.role where tenant_id=? and code='platform-admin'",
          tenantId, policyId, tenantId);
      jdbc.update("update identity.permission_revision set revision=revision+1 where tenant_id=?", tenantId);
      assertThat(permissionService.scopeExplicit(authentication, "WORK_ITEM", "READ_ASSIGNED").allowed()).isTrue();
    } finally {
      jdbc.update("delete from identity.role_policy where tenant_id=? and policy_id=?", tenantId, policyId);
      jdbc.update("delete from identity.policy where tenant_id=? and id=?", tenantId, policyId);
      jdbc.update("update identity.permission_revision set revision=revision+1 where tenant_id=?", tenantId);
    }
  }

  private String createUserWithPolicies(String email, String allowCondition, String deniedAction) throws Exception {
    var sfx = suffix();
    var roleBody = mvc.perform(post("/api/v1/access/roles").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"code\":\"it-role-%s\",\"name\":\"IT Role %s\"}".formatted(sfx, sfx)))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    var roleId = json.readTree(roleBody).get("id").asText();

    var allowBody = mvc.perform(post("/api/v1/access/policies").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"code\":\"it-allow-%s\",\"resourceType\":\"DYNAMIC_RECORD\",\"action\":\"*\",\"effect\":\"ALLOW\",\"condition\":\"%s\"}"
                .formatted(sfx, allowCondition == null ? "{}" : allowCondition.replace("\"", "\\\""))))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    var allowId = json.readTree(allowBody).get("id").asText();

    var policyIds = new StringBuilder("[\"" + allowId + "\"");
    if (deniedAction != null) {
      var denyBody = mvc.perform(post("/api/v1/access/policies").with(bearer(admin)).contentType(APPLICATION_JSON)
              .content("{\"code\":\"it-deny-%s\",\"resourceType\":\"DYNAMIC_RECORD\",\"action\":\"%s\",\"effect\":\"DENY\"}".formatted(sfx, deniedAction)))
          .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
      policyIds.append(",\"").append(json.readTree(denyBody).get("id").asText()).append("\"");
    }
    policyIds.append("]");

    mvc.perform(put("/api/v1/access/roles/%s/policies".formatted(roleId)).with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"ids\":%s}".formatted(policyIds)))
        .andExpect(status().isOk());

    mvc.perform(post("/api/v1/access/users").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"displayName\":\"IT User\",\"password\":\"ItUserPass@2026\",\"roleIds\":[\"%s\"]}".formatted(email, roleId)))
        .andExpect(status().isCreated());

    return login(email, "ItUserPass@2026");
  }
}
