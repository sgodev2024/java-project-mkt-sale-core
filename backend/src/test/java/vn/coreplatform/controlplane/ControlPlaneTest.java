package vn.coreplatform.controlplane;

import org.junit.jupiter.api.Test;
import vn.coreplatform.AbstractApiTest;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ControlPlaneTest extends AbstractApiTest {

  @Test
  void bootstrapReturnsDeploymentStateForAdmin() throws Exception {
    mvc.perform(get("/api/v1/control-plane/bootstrap").with(bearer(adminToken())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.summary").exists())
        // 8 module seed demo + kernel, control-plane, approval-domain do ModuleRegistry
        .andExpect(jsonPath("$.summary.modules").value(11))
        .andExpect(jsonPath("$.modules").isArray())
        .andExpect(jsonPath("$.audit").isArray())
        .andExpect(jsonPath("$.settings").isMap());
  }

  @Test
  void bootstrapRequiresPlatformAdmin() throws Exception {
    var email = "plain-" + suffix() + "@test.local";
    seedDefaultTenantAccount(email, "PlainUser@2026");
    mvc.perform(get("/api/v1/control-plane/bootstrap").with(bearer(login(email, "PlainUser@2026"))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.title").value("PERMISSION_DENIED"));
  }

  @Test
  void auditListingRequiresPlatformAdmin() throws Exception {
    var email = "auditor-" + suffix() + "@test.local";
    seedDefaultTenantAccount(email, "AuditorUser@2026");
    mvc.perform(get("/api/v1/control-plane/audit").with(bearer(login(email, "AuditorUser@2026"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void moduleStatusChangeIsAuditedAndValidated() throws Exception {
    var admin = adminToken();
    var moduleId = jdbc.queryForObject("select id::text from platform.module where module_key='webhook'", String.class);
    var before = jdbc.queryForObject("select count(*) from audit.event where action='MODULE_STATUS_CHANGED' and resource_id=?", Integer.class, moduleId);

    mvc.perform(patch("/api/v1/control-plane/modules/%s/status".formatted(moduleId)).with(bearer(admin))
            .contentType(APPLICATION_JSON).content("{\"status\":\"HEALTHY\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("HEALTHY"));

    var after = jdbc.queryForObject("select count(*) from audit.event where action='MODULE_STATUS_CHANGED' and resource_id=? and actor_email='admin@core.local'", Integer.class, moduleId);
    org.assertj.core.api.Assertions.assertThat(after).isGreaterThan(before);
  }

  @Test
  void settingsUpdatePersistsAndAuditTrail() throws Exception {
    var admin = adminToken();
    var key = "it.setting." + suffix();
    mvc.perform(put("/api/v1/control-plane/settings").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("[{\"key\":\"%s\",\"value\":\"it-value\"}]".formatted(key)))
        .andExpect(status().isOk());

    var value = jdbc.queryForObject("select setting_value from platform.setting where setting_key=?", String.class, key);
    org.assertj.core.api.Assertions.assertThat(value).isEqualTo("it-value");
    var count = jdbc.queryForObject("select count(*) from audit.event where action='SETTINGS_UPDATED'", Integer.class);
    org.assertj.core.api.Assertions.assertThat(count).isGreaterThanOrEqualTo(1);
  }
}
