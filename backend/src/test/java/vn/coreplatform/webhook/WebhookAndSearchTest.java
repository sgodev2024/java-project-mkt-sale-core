package vn.coreplatform.webhook;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import vn.coreplatform.AbstractApiTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** E11: full-text search, CSV idempotent import, webhook SSRF guard. */
class WebhookAndSearchTest extends AbstractApiTest {

  private String createDef(String admin, String key) throws Exception {
    mvc.perform(post("/api/v1/dynamic/definitions").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"resourceKey\":\"%s\",\"name\":\"E11\",\"classification\":\"INTERNAL\",\"schema\":{\"fields\":[{\"key\":\"code\",\"type\":\"string\",\"required\":true},{\"key\":\"body\",\"type\":\"string\"}]}}"
                .formatted(key)))
        .andExpect(status().isCreated());
    for (int i = 0; i < 5; i++)
      mvc.perform(post("/api/v1/dynamic/%s/records".formatted(key)).with(bearer(admin)).contentType(APPLICATION_JSON)
              .content("{\"code\":\"DOC-%02d\",\"body\":\"quarterly financial report for department %d\"}".formatted(i, i)))
          .andExpect(status().isCreated());
    return key;
  }

  @Test void s01_fullTextSearchUsesPostgreSQLTsvector() throws Exception {
    var admin = adminToken();
    var key = "e11-fts-" + suffix();
    createDef(admin, key);

    var result = mvc.perform(get("/api/v1/dynamic/%s/search".formatted(key)).with(bearer(admin)).queryParam("q", "financial report"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(5))
        .andReturn().getResponse().getContentAsString();
    assertThat(json.readTree(result).get("items").size()).isEqualTo(5);

    mvc.perform(get("/api/v1/dynamic/%s/search".formatted(key)).with(bearer(admin)).queryParam("q", "nonexistent-topic-xyz"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
  }

  @Test void s02_csvImportRetryWithBatchKeyCreatesNoDuplicates() throws Exception {
    var admin = adminToken();
    var key = "e11-csv-" + suffix();
    createDef(admin, key);
    var csv = "code,body\nIMP-1,first import\nIMP-2,second import\n".getBytes();
    var batchKey = "batch-" + suffix();

    // import lần 1 với batchKey -> 2 records mới
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .multipart("/api/v1/dynamic/%s/import.csv?batchKey=%s".formatted(key, batchKey))
            .file(new MockMultipartFile("file", "import.csv", "text/csv", csv))
            .with(bearer(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.imported").value(2));

    // batch record phải tồn tại
    assertThat(jdbc.queryForObject("select count(*) from dynamic_resource.import_batch where batch_key=?", Integer.class, batchKey))
        .as("batch record phải được ghi sau import đầu").isEqualTo(1);

    // import lần 2 CÙNG batchKey -> idempotent skip, 0 records mới
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .multipart("/api/v1/dynamic/%s/import.csv?batchKey=%s".formatted(key, batchKey))
            .file(new MockMultipartFile("file", "import.csv", "text/csv", csv))
            .with(bearer(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.imported").value(0))
        .andExpect(jsonPath("$.errors.length()").value(1));

    // tổng cộng vẫn 5 + 2 = 7 (không duplicate)
    mvc.perform(get("/api/v1/dynamic/%s/records".formatted(key)).with(bearer(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(7));
  }

  @Test void s03_webhookSsrfGuardBlocksPrivateAndLocalhost() throws Exception {
    var admin = adminToken();
    // localhost -> chặn
    mvc.perform(post("/api/v1/webhooks").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"url\":\"https://localhost:9000/hook\",\"eventTypes\":[\"dynamic-record.created.v1\"]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("WEBHOOK_HOST_BLOCKED"));

    // 169.254.169.254 (AWS metadata) -> chặn
    mvc.perform(post("/api/v1/webhooks").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"url\":\"https://169.254.169.254/latest/meta-data\",\"eventTypes\":[]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("WEBHOOK_HOST_BLOCKED"));

    // http (không phải https) -> chặn
    mvc.perform(post("/api/v1/webhooks").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"url\":\"http://example.com/hook\",\"eventTypes\":[]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("WEBHOOK_SCHEME"));

    // 192.168.x.x (private) -> chặn
    mvc.perform(post("/api/v1/webhooks").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"url\":\"https://192.168.1.1/hook\",\"eventTypes\":[]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("WEBHOOK_HOST_BLOCKED"));

    // public https (IP literal — không cần DNS) -> OK
    mvc.perform(post("/api/v1/webhooks").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"url\":\"https://93.184.216.34/core-platform\",\"eventTypes\":[\"dynamic-record.created.v1\",\"file.uploaded.v1\"]}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("ACTIVE"));
  }
}
