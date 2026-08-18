package vn.coreplatform.dynamicresource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import vn.coreplatform.AbstractApiTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DynamicResourceTest extends AbstractApiTest {
  String admin;
  String resourceKey;

  @BeforeEach
  void seed() throws Exception {
    admin = adminToken();
    resourceKey = "it-doc-" + suffix();
    createDefinition(resourceKey);
  }

  @Test
  void definitionWithInvalidKeyIsRejected() throws Exception {
    mvc.perform(post("/api/v1/dynamic/definitions").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"resourceKey\":\"1-Bad_Key!\",\"name\":\"Bad\",\"schema\":{\"fields\":[{\"key\":\"code\",\"type\":\"string\"}]}}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void definitionWithMalformedSchemaIsRejected() throws Exception {
    mvc.perform(post("/api/v1/dynamic/definitions").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"resourceKey\":\"ok-key-%s\",\"name\":\"Bad schema\",\"schema\":{\"nope\":true}}".formatted(suffix())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("INVALID_SCHEMA"));
  }

  @Test
  void duplicateDefinitionKeyIsRejected() throws Exception {
    mvc.perform(post("/api/v1/dynamic/definitions").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"resourceKey\":\"%s\",\"name\":\"Trùng key\",\"schema\":{\"fields\":[{\"key\":\"code\",\"type\":\"string\"}]}}"
                .formatted(resourceKey)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.title").value("DEFINITION_EXISTS"));
  }

  @Test
  void recordValidationBlocksMissingRequiredAndWrongType() throws Exception {
    mvc.perform(post("/api/v1/dynamic/%s/records".formatted(resourceKey)).with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"score\":10}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("REQUIRED_FIELD"));

    mvc.perform(post("/api/v1/dynamic/%s/records".formatted(resourceKey)).with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"code\":\"X\",\"score\":\"not-a-number\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("FIELD_TYPE_MISMATCH"));
  }

  @Test
  void optimisticLockingBlocksStaleUpdate() throws Exception {
    var recordId = createRecord("{\"code\":\"C-001\",\"score\":5,\"active\":true}");

    var updated = mvc.perform(put("/api/v1/dynamic/%s/records/%s".formatted(resourceKey, recordId)).with(bearer(admin))
            .header("If-Match", "1").contentType(APPLICATION_JSON).content("{\"code\":\"C-001B\",\"score\":9,\"active\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(2))
        .andExpect(jsonPath("$.data.code").value("C-001B"))
        .andReturn().getResponse().getContentAsString();
    assertThat(json.readTree(updated).get("version").asInt()).isEqualTo(2);

    mvc.perform(put("/api/v1/dynamic/%s/records/%s".formatted(resourceKey, recordId)).with(bearer(admin))
            .header("If-Match", "1").contentType(APPLICATION_JSON).content("{\"code\":\"C-001C\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.title").value("VERSION_CONFLICT"));
  }

  @Test
  void historyRecordsCreateAndUpdateRevisions() throws Exception {
    var recordId = createRecord("{\"code\":\"H-001\"}");
    mvc.perform(put("/api/v1/dynamic/%s/records/%s".formatted(resourceKey, recordId)).with(bearer(admin))
            .header("If-Match", "1").contentType(APPLICATION_JSON).content("{\"code\":\"H-002\"}"))
        .andExpect(status().isOk());

    mvc.perform(get("/api/v1/dynamic/%s/records/%s/history".formatted(resourceKey, recordId)).with(bearer(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].operation").value("UPDATE"))
        .andExpect(jsonPath("$[0].version").value(2))
        .andExpect(jsonPath("$[1].operation").value("CREATE"))
        .andExpect(jsonPath("$[1].version").value(1));
  }

  @Test
  void archiveRemovesRecordFromActiveQueries() throws Exception {
    var recordId = createRecord("{\"code\":\"A-001\"}");
    mvc.perform(delete("/api/v1/dynamic/%s/records/%s".formatted(resourceKey, recordId)).with(bearer(admin)))
        .andExpect(status().isNoContent());

    mvc.perform(get("/api/v1/dynamic/%s/records/%s".formatted(resourceKey, recordId)).with(bearer(admin)))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/dynamic/%s/records".formatted(resourceKey)).with(bearer(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
  }

  @Test
  void csvExportReflectsRecords() throws Exception {
    createRecord("{\"code\":\"E-001\",\"score\":7,\"active\":true}");
    var csv = mvc.perform(get("/api/v1/dynamic/%s/export.csv".formatted(resourceKey)).with(bearer(admin)))
        .andExpect(status().isOk())
        .andExpect(content().contentType("text/csv"))
        .andReturn().getResponse().getContentAsString();
    assertThat(csv).contains("code,score,active").contains("E-001,7,true");
  }

  @Test
  void csvImportCreatesValidRowsAndReportsInvalidOnes() throws Exception {
    var csv = "code,score,active\nI-001,10,true\nI-002,not-a-number,true\n".getBytes();
    var result = mvc.perform(multipart("/api/v1/dynamic/%s/import.csv".formatted(resourceKey))
            .file(new MockMultipartFile("file", "import.csv", "text/csv", csv)).with(bearer(admin)))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    var tree = json.readTree(result);
    assertThat(tree.get("imported").asInt()).isEqualTo(1);
    assertThat(tree.get("failed").asInt()).isEqualTo(1);
    assertThat(tree.get("errors").get(0).asText()).contains("Dòng 3");

    var list = mvc.perform(get("/api/v1/dynamic/%s/records?q=i-001".formatted(resourceKey)).with(bearer(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andReturn().getResponse().getContentAsString();
    assertThat(json.readTree(list).get("items").get(0).get("data").get("code").asText()).isEqualTo("I-001");
  }

  @Test
  void csvExportEscapesSpecialCharacters() throws Exception {
    createRecord("{\"code\":\"a,b \\\"quote\\\"\"}");
    var csv = mvc.perform(get("/api/v1/dynamic/%s/export.csv".formatted(resourceKey)).with(bearer(admin)))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    assertThat(csv).contains("\"a,b \"\"quote\"\"\"");
  }

  private void createDefinition(String key) throws Exception {
    mvc.perform(post("/api/v1/dynamic/definitions").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"resourceKey\":\"%s\",\"name\":\"IT Doc\",\"classification\":\"INTERNAL\",\"schema\":{\"fields\":[{\"key\":\"code\",\"type\":\"string\",\"required\":true},{\"key\":\"score\",\"type\":\"number\"},{\"key\":\"active\",\"type\":\"boolean\"}]}}"
                .formatted(key)))
        .andExpect(status().isCreated());
  }

  private String createRecord(String data) throws Exception {
    var body = mvc.perform(post("/api/v1/dynamic/%s/records".formatted(resourceKey)).with(bearer(admin)).contentType(APPLICATION_JSON).content(data))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(body).get("id").asText();
  }
}
