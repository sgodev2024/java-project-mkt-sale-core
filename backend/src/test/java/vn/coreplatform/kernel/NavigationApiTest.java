package vn.coreplatform.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import vn.coreplatform.AbstractApiTest;

class NavigationApiTest extends AbstractApiTest {
  @Test void platformAdminReceivesUnifiedBusinessAndAdministrationSections() throws Exception {
    var body = navigation(adminToken());
    assertThat(keys(body.path("sections"))).containsExactly("business", "system-administration");
    assertThat(itemKeys(body, "system-administration")).contains("core.modules", "core.resources", "core.users",
        "core.organizations", "core.access", "core.activity", "core.files", "core.settings");
    assertThat(itemKeys(body, "business")).contains("core.home", "module.approval-domain.demo-group",
        "module.approval-domain.approvals");
    assertThat(body.has("workspaces")).isFalse();
  }

  @Test void applicationUserOnlyReceivesAuthorizedBusinessItems() throws Exception {
    var email = "navigation-" + suffix() + "@test.local";
    seedDefaultTenantAccount(email, "ApplicationPass@2026");
    var token = login(email, "ApplicationPass@2026");
    var before = navigation(token);
    assertThat(keys(before.path("sections"))).containsExactly("business");
    assertThat(itemKeys(before, "business")).containsExactly("core.home");

    var tenantId = jdbc.queryForObject("select id from platform.tenant where tenant_key='default'", UUID.class);
    var policyId = UUID.randomUUID();
    jdbc.update("insert into identity.policy(id,tenant_id,code,effect,resource_type,action,condition_json) values(?,?,?,'ALLOW','APPROVAL_REQUEST','READ','{}')",
        policyId, tenantId, "approval-nav-" + suffix());
    jdbc.update("insert into identity.role_policy(tenant_id,role_id,policy_id) select ?,id,? from identity.role where tenant_id=? and code='application-user'",
        tenantId, policyId, tenantId);
    jdbc.update("update identity.permission_revision set revision=revision+1 where tenant_id=?", tenantId);
    try {
      var after = navigation(token);
      assertThat(itemKeys(after, "business")).contains("core.home", "module.approval-domain.demo-group",
          "module.approval-domain.approvals");
    } finally {
      jdbc.update("delete from identity.role_policy where tenant_id=? and policy_id=?", tenantId, policyId);
      jdbc.update("delete from identity.policy where tenant_id=? and id=?", tenantId, policyId);
      jdbc.update("update identity.permission_revision set revision=revision+1 where tenant_id=?", tenantId);
    }
  }

  @Test void disabledModuleRemovesItsNavigationContribution() throws Exception {
    jdbc.update("update platform.module set status='DISABLED' where module_key='approval-domain'");
    try {
      assertThat(itemKeys(navigation(adminToken()), "business")).containsExactly("core.home");
    } finally {
      jdbc.update("update platform.module set status='HEALTHY' where module_key='approval-domain'");
    }
  }

  @Test void preferencesRoundTripAndDiscardUnauthorizedKeys() throws Exception {
    var token = adminToken();
    var response = mvc.perform(put("/api/v1/navigation/me/preferences").with(bearer(token)).contentType(APPLICATION_JSON)
            .content("{\"favoriteKeys\":[\"core.modules\",\"module.unknown.page\"],\"recentKeys\":[\"core.files\",\"core.modules\"]}"))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    var body = json.readTree(response);
    assertThat(texts(body.path("favoriteKeys"))).containsExactly("core.modules");
    assertThat(texts(body.path("recentKeys"))).containsExactly("core.files", "core.modules");
    assertThat(body.has("currentWorkspaceKey")).isFalse();
    assertThat(body.has("defaultWorkspaceKey")).isFalse();
  }

  private JsonNode navigation(String token) throws Exception {
    var body = mvc.perform(get("/api/v1/navigation/me").with(bearer(token)))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    return json.readTree(body);
  }
  private List<String> keys(JsonNode nodes) { var result=new ArrayList<String>();nodes.forEach(n->result.add(n.path("key").asText()));return result; }
  private List<String> texts(JsonNode nodes) { var result=new ArrayList<String>();nodes.forEach(n->result.add(n.asText()));return result; }
  private List<String> itemKeys(JsonNode body,String section) {
    for (var node : body.path("sections")) if (section.equals(node.path("key").asText()))
      return keys(node.path("items"));
    return List.of();
  }
}
