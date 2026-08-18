package vn.coreplatform.dynamicresource;

import org.junit.jupiter.api.Test;
import vn.coreplatform.AbstractApiTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** E4-S05: dynamic definition thiếu classification được phê duyệt không thể kích hoạt. */
class ClassificationGateTest extends AbstractApiTest {
  String admin;

  @Test
  void definitionWithoutClassificationStaysPendingAndInvisible() throws Exception {
    admin = adminToken();
    var key = "e4-gate-" + suffix();
    var created = mvc.perform(post("/api/v1/dynamic/definitions").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"resourceKey\":\"%s\",\"name\":\"Gate Doc\",\"schema\":{\"fields\":[{\"key\":\"code\",\"type\":\"string\"}]}}".formatted(key)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andExpect(jsonPath("$.dataClassification").doesNotExist())
        .andReturn().getResponse().getContentAsString();
    assertThat(json.readTree(created).get("status").asText()).isEqualTo("PENDING");

    // definition PENDING không nhận record qua generic CRUD — đó là classification gate
    mvc.perform(post("/api/v1/dynamic/%s/records".formatted(key)).with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"code\":\"X\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.title").value("DEFINITION_NOT_FOUND"));
    // export/CSV cũng phải chặn
    mvc.perform(get("/api/v1/dynamic/%s/export.csv".formatted(key)).with(bearer(admin)))
        .andExpect(status().isNotFound());
  }

  @Test
  void classificationApprovalActivatesDefinition() throws Exception {
    admin = adminToken();
    var key = "e4-approve-" + suffix();
    mvc.perform(post("/api/v1/dynamic/definitions").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"resourceKey\":\"%s\",\"name\":\"Approve Doc\",\"schema\":{\"fields\":[{\"key\":\"code\",\"type\":\"string\"}]}}".formatted(key)))
        .andExpect(status().isCreated());

    // classification không hợp lệ bị từ chối
    mvc.perform(post("/api/v1/dynamic/%s/classification".formatted(key)).with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"classification\":\"TOP_SECRET\"}"))
        .andExpect(status().isBadRequest());

    // phê duyệt đúng -> ACTIVE + descriptor cập nhật classification + audit
    mvc.perform(post("/api/v1/dynamic/%s/classification".formatted(key)).with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"classification\":\"CONFIDENTIAL\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.dataClassification").value("CONFIDENTIAL"));

    mvc.perform(post("/api/v1/dynamic/%s/records".formatted(key)).with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"code\":\"AFTER-GATE\"}"))
        .andExpect(status().isCreated());

    var descriptor = jdbc.queryForObject("select data_classification from platform.resource_descriptor where resource_type=?", String.class, key);
    assertThat(descriptor).isEqualTo("CONFIDENTIAL");
    var audits = jdbc.queryForObject("select count(*) from audit.event where action='DYNAMIC_CLASSIFICATION_APPROVED' and resource_type=?", Integer.class, key);
    assertThat(audits).isEqualTo(1);
  }

  @Test
  void definitionWithApprovedClassificationIsActiveImmediately() throws Exception {
    admin = adminToken();
    var key = "e4-fast-" + suffix();
    mvc.perform(post("/api/v1/dynamic/definitions").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"resourceKey\":\"%s\",\"name\":\"Fast Doc\",\"classification\":\"INTERNAL\",\"schema\":{\"fields\":[{\"key\":\"code\",\"type\":\"string\"}]}}".formatted(key)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.dataClassification").value("INTERNAL"));
  }

  @Test
  void plainUserCannotApproveClassification() throws Exception {
    admin = adminToken();
    var key = "e4-who-" + suffix();
    mvc.perform(post("/api/v1/dynamic/definitions").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"resourceKey\":\"%s\",\"name\":\"Who Doc\",\"schema\":{\"fields\":[]}}".formatted(key)))
        .andExpect(status().isCreated());
    var email = "e4-who-" + suffix() + "@test.local";
    seedDefaultTenantAccount(email, "WhoUser@2026xx");
    mvc.perform(post("/api/v1/dynamic/%s/classification".formatted(key)).with(bearer(login(email, "WhoUser@2026xx"))).contentType(APPLICATION_JSON)
            .content("{\"classification\":\"INTERNAL\"}"))
        .andExpect(status().isForbidden());
  }
}
