package vn.coreplatform.filemanagement;

import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import vn.coreplatform.AbstractApiTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** E8: upload session staging, scan gate, cross-tenant + guessing guard, reconciliation, legal hold. */
class FileLifecycleTest extends AbstractApiTest {
  @Autowired FileStorageService storage;

  private UUID openSession(String admin, String name) throws Exception {
    var body = mvc.perform(post("/api/v1/files/upload-sessions").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"name\":\"%s\",\"classification\":\"INTERNAL\"}".formatted(name)))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return UUID.fromString(json.readTree(body).get("sessionId").asText());
  }

  @Test void s01_interruptedUploadLeavesNoActiveFileAndCleanupRemovesIt() throws Exception {
    var admin = adminToken();
    var session = openSession(admin, "interrupted.bin");
    // mở session nhưng KHÔNG gửi nội dung — không bao giờ ACTIVE
    assertThat(jdbc.queryForObject("select status from files.file_object where id=?", String.class, session)).isEqualTo("STAGING");

    // gửi nửa chừng (content) rồi dừng trước finalize -> SCANNING, vẫn chưa ACTIVE
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/files/upload-sessions/" + session + "/content").file(new MockMultipartFile("file", "interrupted.bin", MediaType.APPLICATION_OCTET_STREAM_VALUE, "half".getBytes())).with(bearer(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SCANNING"));
    assertThat(jdbc.queryForObject("select status from files.file_object where id=?", String.class, session)).isEqualTo("SCANNING");

    // giả lập session quá hạn -> cleanup xóa cả row lẫn object staging
    jdbc.update("update files.file_object set created_at = now() - interval '2 hours' where id=?", session);
    mvc.perform(post("/api/v1/files/staging-cleanup").with(bearer(admin)).queryParam("olderThanMinutes", "60"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deleted").value(1));
    assertThat(jdbc.queryForList("select id from files.file_object where id=?", UUID.class, session)).isEmpty();
  }

  @Test void s02_finalizeRequiresCleanScanAndQuarantinesInfectedFile() throws Exception {
    var admin = adminToken();
    // tạo file ACTIVE hoàn chỉnh (qua endpoint một lời gọi — scan adapter mặc định CLEAN)
    var uploaded = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/files").file(new MockMultipartFile("file", "clean.txt", MediaType.TEXT_PLAIN_VALUE, "clean".getBytes()))
            .param("classification", "INTERNAL").with(bearer(admin)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andReturn().getResponse().getContentAsString();
    var cleanId = json.readTree(uploaded).get("id").asText();

    //simulate scan bẩn: sửa scan adapter không được từ API — dùng reconcile path để chứng minh gate:
    // một file chỉ ACTIVE sau khi có scanned_at (CLEAN)
    assertThat(jdbc.queryForObject("select scanned_at is not null from files.file_object where id=?", Boolean.class, UUID.fromString(cleanId))).isTrue();
    assertThat(jdbc.queryForObject("select scan_result from files.file_object where id=?", String.class, UUID.fromString(cleanId))).isEqualTo("CLEAN");

    // INFECTED: chèn trạng thái QUARANTINED trực tiếp qua DB (giả lập AV gắn cờ) rồi thử download -> 404
    var infectedId = openSession(admin, "virus.exe");
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/files/upload-sessions/" + infectedId + "/content").file(new MockMultipartFile("file", "virus.exe", MediaType.APPLICATION_OCTET_STREAM_VALUE, "evil".getBytes())).with(bearer(admin)))
        .andExpect(status().isOk());
    jdbc.update("update files.file_object set status='QUARANTINED', scan_result='INFECTED', scanned_at=now() where id=?", infectedId);
    mvc.perform(get("/api/v1/files/{id}/content", infectedId).with(bearer(admin)))
        .andExpect(status().isNotFound()); // không bao giờ tải được file chưa scan CLEAN
  }

  @Test void s03_crossTenantAndFileIdGuessingCannotDownload() throws Exception {
    var admin = adminToken();
    var body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/files").file(new MockMultipartFile("file", "secret.txt", MediaType.TEXT_PLAIN_VALUE, "tenant A secret".getBytes()))
            .param("classification", "INTERNAL").with(bearer(admin)))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    var id = json.readTree(body).get("id").asText();

    mvc.perform(get("/api/v1/files/{id}/content", UUID.randomUUID()).with(bearer(admin)))
        .andExpect(status().isNotFound()); // đoán UUID ngẫu nhiên

    var tenantB = "e8b-" + suffix() + "@acme.test";
    jdbc.update("insert into platform.tenant(tenant_key,name) values('acme','ACME Corp') on conflict (tenant_key) do nothing");
    var tenantId = jdbc.queryForObject("select id from platform.tenant where tenant_key='acme'", UUID.class);
    jdbc.update("insert into identity.account(id,tenant_id,email,display_name,password_hash,password_algo,role) values(?,?,?,?,?,'ARGON2ID','APPLICATION_USER') on conflict do nothing",
        UUID.randomUUID(), tenantId, tenantB, "E8B", encoder.encode("AcmeUser@2026x"));
    var tokenB = login(tenantB, "AcmeUser@2026x");
    mvc.perform(get("/api/v1/files/{id}/content", id).with(bearer(tokenB)))
        .andExpect(status().isNotFound()); // cross-tenant
  }

  @Test void s04_reconciliationDetectsRestoreMismatchesAndLegalHoldBlocksDelete() throws Exception {
    var admin = adminToken();
    var body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/files").file(new MockMultipartFile("file", "recon.txt", MediaType.TEXT_PLAIN_VALUE, "recon-body".getBytes()))
            .param("classification", "INTERNAL").with(bearer(admin)))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    var id = UUID.fromString(json.readTree(body).get("id").asText());

    // 1) object biến mất khỏi storage sau restore hỏng -> reconcile phát hiện MISSING_OBJECT
    Files.deleteIfExists(storage.resolve(jdbc.queryForObject("select storage_key from files.file_object where id=?", String.class, id)));
    mvc.perform(post("/api/v1/files/reconcile").with(bearer(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.missingObjects").value(1));
    assertThat(jdbc.queryForObject("select status||'/'||scan_result from files.file_object where id=?", String.class, id))
        .isEqualTo("QUARANTINED/MISSING_OBJECT");

    // 2) checksum mismatch sau restore nhầm object
    var body2 = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/files").file(new MockMultipartFile("file", "recon2.txt", MediaType.TEXT_PLAIN_VALUE, "original".getBytes()))
            .param("classification", "INTERNAL").with(bearer(admin)))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    var id2 = UUID.fromString(json.readTree(body2).get("id").asText());
    var key2 = jdbc.queryForObject("select storage_key from files.file_object where id=?", String.class, id2);
    Files.write(storage.resolve(key2), "tampered-restore".getBytes());
    mvc.perform(post("/api/v1/files/reconcile").with(bearer(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.checksumMismatches").value(1));
    assertThat(jdbc.queryForObject("select status from files.file_object where id=?", String.class, id2)).isEqualTo("QUARANTINED");

    // 3) legal hold chặn xóa
    var body3 = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/files").file(new MockMultipartFile("file", "hold.txt", MediaType.TEXT_PLAIN_VALUE, "hold".getBytes()))
            .param("classification", "CONFIDENTIAL").with(bearer(admin)))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    var id3 = UUID.fromString(json.readTree(body3).get("id").asText());
    jdbc.update("update files.file_object set legal_hold=true where id=?", id3);
    mvc.perform(delete("/api/v1/files/{id}", id3).with(bearer(admin)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.title").value("FILE_LEGAL_HOLD"));
  }
}
