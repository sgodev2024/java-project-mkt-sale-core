package vn.coreplatform.audit;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import vn.coreplatform.AbstractApiTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * E5: audit integrity end-to-end.
 * S01 audit fail rollback thao tác nghiệp vụ; S02 không secret trong audit;
 * S03 hash chain phát hiện sửa/xóa; S04 retention tôn trọng checkpoint + legal hold.
 */
class AuditIntegrityTest extends AbstractApiTest {
  @Autowired AuditService audits;

  private void seedTenantUserAndLogin(String tenantKey) throws Exception {
    var tenantId = jdbc.queryForObject("select id from platform.tenant where tenant_key=?", UUID.class, tenantKey);
    var email = tenantKey + "@e5.test";
    var password = "E5UserPass@2026";
    jdbc.update("insert into identity.account(id,tenant_id,email,display_name,password_hash,password_algo,role) values(?,?,?,?,?,'ARGON2ID','APPLICATION_USER') on conflict do nothing",
        UUID.randomUUID(), tenantId, email, "E5 User", encoder.encode(password));
    for (int i = 0; i < 3; i++) login(email, password); // AUTH events vào chain của tenant này
  }

  @Test
  void s01_auditFailureRollsBackBusinessTransaction() throws Exception {
    var admin = adminToken();
    var resourceKey = "e5-tx-" + suffix();
    mvc.perform(post("/api/v1/dynamic/definitions").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"resourceKey\":\"%s\",\"name\":\"E5 TX\",\"classification\":\"INTERNAL\",\"schema\":{\"fields\":[{\"key\":\"code\",\"type\":\"string\"}]}}"
                .formatted(resourceKey)))
        .andExpect(status().isCreated());
    var before = countRecords(resourceKey);

    // ép audit insert fail bằng trigger (giả lập hạ tầng audit chết)
    jdbc.update("create or replace function audit.e5_fail() returns trigger language plpgsql as $f$ begin raise exception 'AUDIT_INSERT_FORCED_FAILURE'; end $f$");
    jdbc.update("create trigger e5_fail_trigger before insert on audit.event for each row when (new.action like 'DYNAMIC_RECORD%') execute function audit.e5_fail()");

    mvc.perform(post("/api/v1/dynamic/%s/records".formatted(resourceKey)).with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"code\":\"MUST-ROLLBACK\"}"))
        .andExpect(status().isInternalServerError());

    jdbc.update("drop trigger e5_fail_trigger on audit.event");
    jdbc.update("drop function audit.e5_fail()");

    // nghiệp vụ PHẢI rollback: không có record, không có event mồ côi, chain liền mạch
    assertThat(countRecords(resourceKey)).as("record phải được rollback khi audit fail").isEqualTo(before);
    var orphan = jdbc.queryForObject("select count(*) from audit.event where resource_id in (select id::text from dynamic_resource.record where data->>'code'='MUST-ROLLBACK')", Integer.class);
    assertThat(orphan).isZero();
    var verification = audits.verify("default");
    assertThat(verification.verified()).as("chain không được hỏng sau rollback: " + verification.reason()).isTrue();
  }

  private long countRecords(String key) {
    return jdbc.queryForObject("select count(*) from dynamic_resource.record r join dynamic_resource.definition d on d.id=r.definition_id where d.resource_key=?", Long.class, key);
  }

  @Test
  void s02_secretsNeverAppearInAudit() throws Exception {
    var secretPassword = "Ultr4Secret-" + suffix() + "@Pass";
    var email = "e5-secret-" + suffix() + "@test.local";
    seedDefaultTenantAccount(email, secretPassword);
    var tokens = login(email, secretPassword); // token sinh ra
    mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"WrongButDistinct-%s\"}".formatted(email, suffix())))
        .andExpect(status().isUnauthorized());

    var dump = jdbc.queryForObject("select coalesce(string_agg(concat_ws('|',coalesce(actor_email,''),action,coalesce(resource_type,''),coalesce(resource_id,''),coalesce(details::text,'')), chr(10)), '') from audit.event", String.class);
    assertThat(dump).doesNotContain(secretPassword);
    assertThat(dump).doesNotContain("WrongButDistinct");
    assertThat(dump).doesNotContain(tokens);

    var masked = AuditService.mask("{\"password\":\"abc\",\"nested\":{\"apiKey\":\"k-123\",\"keep\":\"yes\"},\"authorization\":\"Bearer zzz\"}");
    assertThat(masked).contains("***").doesNotContain("abc").doesNotContain("k-123").doesNotContain("zzz").contains("yes");
  }

  @Test
  void s03_tamperAndDeletionBreakChain() throws Exception {
    var admin = adminToken();
    var tenant = "e5-tamper-" + suffix();
    mvc.perform(post("/api/v1/access/tenants").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"key\":\"%s\",\"name\":\"%s\"}".formatted(tenant, tenant)))
        .andExpect(status().isCreated());
    seedTenantUserAndLogin(tenant);
    var events = jdbc.queryForObject("select count(*) from audit.event where tenant_key=? and sequence_no is not null", Integer.class, tenant);
    assertThat(events).isGreaterThanOrEqualTo(3);

    assertThat(audits.verify(tenant).verified()).isTrue();

    // sửa nội dung 1 event -> verify phải chỉ đúng sequence bị sửa
    jdbc.update("update audit.event set action='TAMPERED_ACTION' where tenant_key=? and sequence_no=2", tenant);
    var tampered = audits.verify(tenant);
    assertThat(tampered.verified()).isFalse();
    assertThat(tampered.brokenAtSequence()).isEqualTo(2);
    assertThat(tampered.reason()).contains("TAMPERED");

    // checkpoint từ chối khi chain hỏng
    mvc.perform(post("/api/v1/control-plane/audit/checkpoint").with(bearer(admin)).queryParam("tenantKey", tenant))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.title").value("AUDIT_CHAIN_BROKEN"));

    // tenant riêng cho deletion: xóa 1 event giữa chain -> phát hiện gap
    var tenant2 = "e5-delete-" + suffix();
    mvc.perform(post("/api/v1/access/tenants").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"key\":\"%s\",\"name\":\"%s\"}".formatted(tenant2, tenant2)))
        .andExpect(status().isCreated());
    seedTenantUserAndLogin(tenant2);
    assertThat(audits.verify(tenant2).verified()).isTrue();
    jdbc.update("delete from audit.event where tenant_key=? and sequence_no=2", tenant2);
    var gapped = audits.verify(tenant2);
    assertThat(gapped.verified()).isFalse();
    assertThat(gapped.reason()).contains("SEQUENCE_GAP");
  }

  @Test
  void s04_retentionRespectsCheckpointAndLegalHold() throws Exception {
    var admin = adminToken();
    var tenant = "e5-retain-" + suffix();
    mvc.perform(post("/api/v1/access/tenants").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"key\":\"%s\",\"name\":\"%s\"}".formatted(tenant, tenant)))
        .andExpect(status().isCreated());
    seedTenantUserAndLogin(tenant);

    // chưa checkpoint -> purge không xóa gì
    mvc.perform(post("/api/v1/control-plane/audit/purge").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"tenantKey\":\"%s\",\"olderThanDays\":30}".formatted(tenant)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deleted").value(0));

    // checkpoint rồi tuổi hóa events -> purge chỉ xóa batch đã checkpoint
    var cp = mvc.perform(post("/api/v1/control-plane/audit/checkpoint").with(bearer(admin)).queryParam("tenantKey", tenant))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    var verifiedSeq = json.readTree(cp).get("verifiedSequence").asLong();
    assertThat(verifiedSeq).isGreaterThanOrEqualTo(3);
    jdbc.update("update audit.event set occurred_at = now() - interval '90 days' where tenant_key=? and sequence_no <= ?", tenant, verifiedSeq);

    mvc.perform(post("/api/v1/control-plane/audit/purge").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"tenantKey\":\"%s\",\"olderThanDays\":30}".formatted(tenant)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deleted").value((int) verifiedSeq));

    // legal hold chặn purge kể cả khi đủ điều kiện
    seedTenantUserAndLogin(tenant); // events mới sau checkpoint
    mvc.perform(post("/api/v1/control-plane/audit/checkpoint").with(bearer(admin)).queryParam("tenantKey", tenant)).andExpect(status().isOk());
    jdbc.update("update audit.event set occurred_at = now() - interval '90 days' where tenant_key=? and sequence_no > ?", tenant, verifiedSeq);
    var aged = jdbc.queryForObject("select count(*) from audit.event where tenant_key=? and sequence_no > ?", Integer.class, tenant, verifiedSeq);
    assertThat(aged).isGreaterThan(0);
    mvc.perform(post("/api/v1/control-plane/audit/legal-hold").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"tenantKey\":\"%s\",\"reason\":\"Luật tranh chấp ABC-123\"}".formatted(tenant)))
        .andExpect(status().isOk());
    mvc.perform(post("/api/v1/control-plane/audit/purge").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"tenantKey\":\"%s\",\"olderThanDays\":30}".formatted(tenant)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.title").value("LEGAL_HOLD_ACTIVE"));

    // giải giữ legal hold -> purge chạy lại được
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .delete("/api/v1/control-plane/audit/legal-hold/{tenantKey}", tenant).with(bearer(admin)))
        .andExpect(status().isOk());
    mvc.perform(post("/api/v1/control-plane/audit/purge").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"tenantKey\":\"%s\",\"olderThanDays\":30}".formatted(tenant)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deleted").value(aged));
  }
}
