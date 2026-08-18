package vn.coreplatform.dynamicresource;

import org.junit.jupiter.api.Test;
import vn.coreplatform.AbstractApiTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** E9: breaking schema gate, DOMAIN guard, governed index compiler, custom fields. */
class DynamicResourceAdvancedTest extends AbstractApiTest {

  private String createDef(String admin, String key, String fields) throws Exception {
    mvc.perform(post("/api/v1/dynamic/definitions").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"resourceKey\":\"%s\",\"name\":\"%s\",\"classification\":\"INTERNAL\",\"schema\":{\"fields\":%s}}"
                .formatted(key, key, fields)))
        .andExpect(status().isCreated());
    return key;
  }

  @Test void s01_breakingSchemaNeedsMigrationConfirmation() throws Exception {
    var admin = adminToken();
    var key = createDef(admin, "e9-brk-" + suffix(), "[{\"key\":\"code\",\"type\":\"string\",\"required\":true},{\"key\":\"note\",\"type\":\"string\"}]");

    // non-breaking: thêm field optional -> áp dụng ngay
    mvc.perform(post("/api/v1/dynamic/%s/schema".formatted(key)).with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"schema\":{\"fields\":[{\"key\":\"code\",\"type\":\"string\",\"required\":true},{\"key\":\"note\",\"type\":\"string\"},{\"key\":\"extra\",\"type\":\"string\"}]}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(2))
        .andExpect(jsonPath("$.schema.fields.length()").value(3));

    // breaking: đổi type -> không activate, pending migration
    mvc.perform(post("/api/v1/dynamic/%s/schema".formatted(key)).with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"schema\":{\"fields\":[{\"key\":\"code\",\"type\":\"number\",\"required\":true},{\"key\":\"note\",\"type\":\"string\"},{\"key\":\"extra\",\"type\":\"string\"}]}}"))
        .andExpect(status().isOk());
    assertThat(jdbc.queryForObject("select migration_state from dynamic_resource.definition where resource_key=?", String.class, key)).isEqualTo("REQUIRED");
    // schema đang hiệu lực vẫn là bản cũ (code vẫn string)
    assertThat(jdbc.queryForObject("select schema_json->'fields'->0->>'type' from dynamic_resource.definition where resource_key=?", String.class, key)).isEqualTo("string");

    // record mới vẫn validate theo schema cũ — code string
    mvc.perform(post("/api/v1/dynamic/%s/records".formatted(key)).with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"code\":\"ABC\"}"))
        .andExpect(status().isCreated());

    // xác nhận migration -> schema mới áp dụng
    mvc.perform(post("/api/v1/dynamic/%s/migration-confirmed".formatted(key)).with(bearer(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.schema.fields[0].type").value("number"));
    assertThat(jdbc.queryForObject("select migration_state from dynamic_resource.definition where resource_key=?", String.class, key)).isEqualTo("APPLIED");
  }

  @Test void s03_genericEndpointRejectsDomainDescriptor() throws Exception {
    var admin = adminToken();
    var domainKey = "e9-domain-" + suffix();
    // đăng ký DOMAIN descriptor qua control-plane registry (SPI)
    mvc.perform(post("/api/v1/control-plane/resources").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"name\":\"%s\",\"storageMode\":\"DOMAIN\",\"ownerModule\":\"dynamic-resource\",\"schemaVersion\":\"v1\"}"
                .formatted(domainKey.substring(0,1).toUpperCase()+domainKey.substring(1))))
        .andExpect(status().isCreated());

    // generic CRUD từ chối DOMAIN descriptor với 409 riêng, KHÔNG phải 404 mù
    mvc.perform(get("/api/v1/dynamic/%s/records".formatted(domainKey)).with(bearer(admin)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.title").value("DOMAIN_RESOURCE_NOT_GENERIC"));
    mvc.perform(post("/api/v1/dynamic/%s/records".formatted(domainKey)).with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"code\":\"x\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.title").value("DOMAIN_RESOURCE_NOT_GENERIC"));
  }

  @Test void s04_governedIndexCompilerRejectsUnknownFieldsAndIsReproducible() throws Exception {
    var admin = adminToken();
    var key = createDef(admin, "e9-idx-" + suffix(), "[{\"key\":\"code\",\"type\":\"string\",\"required\":true},{\"key\":\"score\",\"type\":\"number\"}]");

    // field không có trong schema -> từ chối
    mvc.perform(post("/api/v1/dynamic/%s/indexes".formatted(key)).with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"fieldKey\":\"ghost_field\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("FIELD_NOT_IN_SCHEMA"));

    // tạo hợp lệ -> index_name deterministic (dyn_<def>_<field>_idx)
    var view = mvc.perform(post("/api/v1/dynamic/%s/indexes".formatted(key)).with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"fieldKey\":\"score\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.indexName").value("dyn_e9_idx_" + key.substring(7) + "_score_idx"))
        .andReturn().getResponse().getContentAsString();
    var indexName = json.readTree(view).get("indexName").asText();

    // reproducible: index tồn tại vật lý trong database
    assertThat(jdbc.queryForObject("select count(*) from pg_indexes where indexname=?", Integer.class, indexName)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from dynamic_resource.managed_index where index_name=?", Integer.class, indexName)).isEqualTo(1);

    // gọi lần 2 (idempotent — create index if not exists + upsert)
    mvc.perform(post("/api/v1/dynamic/%s/indexes".formatted(key)).with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"fieldKey\":\"score\"}"))
        .andExpect(status().isCreated());
    assertThat(jdbc.queryForObject("select count(*) from pg_indexes where indexname=?", Integer.class, indexName)).isEqualTo(1);
  }

  @Test void s05_customFieldsNeverOverrideTypedSchemaFields() throws Exception {
    var admin = adminToken();
    var key = createDef(admin, "e9-custom-" + suffix(), "[{\"key\":\"code\",\"type\":\"string\",\"required\":true},{\"key\":\"score\",\"type\":\"number\"}]");

    // _custom cố đè "code" (typed field) + thêm "priority" hợp lệ + "INVALID KEY!" bị lọc
    var body = mvc.perform(post("/api/v1/dynamic/%s/records".formatted(key)).with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"code\":\"REAL\",\"score\":5,\"_custom\":{\"code\":\"HACKED\",\"priority\":\"high\",\"Bad Key\":\"x\"}}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    var id = json.readTree(body).get("id").asText();

    var stored = jdbc.queryForList("select data->>'code' as code, custom_attributes->>'code' as custom_code, custom_attributes->>'priority' as priority from dynamic_resource.record where id=?::uuid", id).getFirst();
    assertThat(stored.get("code")).as("typed field giữ nguyên").isEqualTo("REAL");
    assertThat(stored.get("custom_code")).as("custom không đè được typed field").isNull();
    assertThat(stored.get("priority")).as("custom key hợp lệ được giữ").isEqualTo("high");
  }
}
