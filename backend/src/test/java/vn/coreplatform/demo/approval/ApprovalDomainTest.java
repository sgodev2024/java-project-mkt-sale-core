package vn.coreplatform.demo.approval;

import org.junit.jupiter.api.Test;
import vn.coreplatform.AbstractApiTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** E10: typed aggregate chứng minh code-first path — domain invariants + events + custom fields + permissions. */
class ApprovalDomainTest extends AbstractApiTest {

  private String createApproval(String admin, String title, String priority) throws Exception {
    var body = mvc.perform(post("/api/v1/approvals").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"title\":\"%s\",\"priority\":\"%s\",\"amount\":1500.00,\"customAttributes\":{\"department\":\"IT\",\"cost_center\":\"CC-001\"}}"
                .formatted(title, priority)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.customAttributes.department").value("IT"))
        .andReturn().getResponse().getContentAsString();
    return json.readTree(body).get("id").asText();
  }

  @Test void s01_typedAggregateWorksIndependentlyOfGenericCrud() throws Exception {
    var admin = adminToken();
    var id = createApproval(admin, "Server purchase Q3", "HIGH");
    // NOT going through /dynamic/* — it has its own table and own API
    assertThat(jdbc.queryForObject("select count(*) from domain.approval_request where id=?::uuid", Integer.class, id)).isEqualTo(1);
    // DOMAIN descriptor in registry, not DYNAMIC
    assertThat(jdbc.queryForObject("select storage_mode from platform.resource_descriptor where resource_type='approval-request'", String.class)).isEqualTo("DOMAIN");
    // generic CRUD rejects it
    mvc.perform(get("/api/v1/dynamic/approval-request/records").with(bearer(admin)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.title").value("DOMAIN_RESOURCE_NOT_GENERIC"));
  }

  @Test void s02_domainInvariantsBlockInvalidTransitions() throws Exception {
    var admin = adminToken();
    var id = createApproval(admin, "Quarterly report", "MEDIUM");

    // DRAFT -> APPROVE trực tiếp = sai
    mvc.perform(post("/api/v1/approvals/{id}/approve", id).with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"note\":\"skip submit\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.title").value("INVALID_TRANSITION"));

    // DRAFT -> SUBMITTED -> APPROVED = đúng
    mvc.perform(post("/api/v1/approvals/{id}/submit", id).with(bearer(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUBMITTED"));
    mvc.perform(post("/api/v1/approvals/{id}/approve", id).with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"note\":\"OK\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"))
        .andExpect(jsonPath("$.decidedBy").exists());

    // APPROVED -> CANCEL = sai (đã kết thúc)
    mvc.perform(post("/api/v1/approvals/{id}/cancel", id).with(bearer(admin)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.title").value("INVALID_TRANSITION"));

    // outbox events published
    assertThat(jdbc.queryForObject("select count(*) from async.outbox_event where aggregate_id=? and event_type like 'approval-request.%'", Integer.class, id))
        .isGreaterThanOrEqualTo(3); // created + submitted + approved
  }

  @Test void s03_customFieldsNeverOverrideTypedFields() throws Exception {
    var admin = adminToken();
    var id = createApproval(admin, "Custom field test", "LOW");

    // cố đè "title" (typed field) qua custom — bị strip
    mvc.perform(put("/api/v1/approvals/{id}/custom-fields", id).with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"customAttributes\":{\"title\":\"HACKED\",\"urgency\":\"tomorrow\"}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.customAttributes.urgency").value("tomorrow"))
        .andExpect(jsonPath("$.customAttributes.title").doesNotExist()) // bị strip!
        .andExpect(jsonPath("$.title").value("Custom field test"));    // typed field nguyên vẹn

    // audit có ghi nhận
    assertThat(jdbc.queryForObject("select count(*) from audit.event where action='APPROVAL_CUSTOM_FIELDS_UPDATED' and resource_id=?", Integer.class, id)).isEqualTo(1);
  }

  @Test void s03_userWithoutApprovalPermissionIsDenied() throws Exception {
    var admin = adminToken();
    var email = "e10-plain-" + suffix() + "@test.local";
    seedDefaultTenantAccount(email, "PlainUser@2026x");
    var token = login(email, "PlainUser@2026x");
    mvc.perform(get("/api/v1/approvals").with(bearer(token)))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/v1/approvals").with(bearer(token)).contentType(APPLICATION_JSON)
            .content("{\"title\":\"Should fail\"}"))
        .andExpect(status().isForbidden());
  }
}
