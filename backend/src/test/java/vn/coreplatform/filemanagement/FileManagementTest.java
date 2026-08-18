package vn.coreplatform.filemanagement;

import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import vn.coreplatform.AbstractApiTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FileManagementTest extends AbstractApiTest {
  String admin;

  @Test
  void uploadDownloadAndSoftDeleteLifecycle() throws Exception {
    admin = adminToken();
    var content = "hello core platform".getBytes();
    var checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));

    var body = mvc.perform(multipart("/api/v1/files")
            .file(new MockMultipartFile("file", "hello.txt", MediaType.TEXT_PLAIN_VALUE, content))
            .param("classification", "INTERNAL").with(bearer(admin)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.classification").value("INTERNAL"))
        .andExpect(jsonPath("$.sizeBytes").value(content.length))
        .andExpect(jsonPath("$.checksumSha256").value(checksum))
        .andReturn().getResponse().getContentAsString();
    var id = json.readTree(body).get("id").asText();

    var downloaded = mvc.perform(get("/api/v1/files/%s/content".formatted(id)).with(bearer(admin)))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("hello.txt")))
        .andReturn().getResponse().getContentAsByteArray();
    assertThat(downloaded).isEqualTo(content);

    mvc.perform(delete("/api/v1/files/%s".formatted(id)).with(bearer(admin))).andExpect(status().isNoContent());

    mvc.perform(get("/api/v1/files/%s/content".formatted(id)).with(bearer(admin))).andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/files?q=hello").with(bearer(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
  }

  @Test
  void uploadRejectsInvalidClassification() throws Exception {
    admin = adminToken();
    mvc.perform(multipart("/api/v1/files")
            .file(new MockMultipartFile("file", "x.txt", MediaType.TEXT_PLAIN_VALUE, "x".getBytes()))
            .param("classification", "TOP_SECRET").with(bearer(admin)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("CLASSIFICATION"));
  }

  @Test
  void uploadRejectsEmptyFile() throws Exception {
    admin = adminToken();
    mvc.perform(multipart("/api/v1/files")
            .file(new MockMultipartFile("file", "empty.txt", MediaType.TEXT_PLAIN_VALUE, new byte[0]))
            .param("classification", "INTERNAL").with(bearer(admin)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("FILE_SIZE"));
  }

  @Test
  void filenameIsSanitizedToPlainName() throws Exception {
    admin = adminToken();
    var body = mvc.perform(multipart("/api/v1/files")
            .file(new MockMultipartFile("file", "..\\..\\evil\\name.txt", MediaType.TEXT_PLAIN_VALUE, "x".getBytes()))
            .param("classification", "INTERNAL").with(bearer(admin)))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    assertThat(json.readTree(body).get("name").asText()).isEqualTo("name.txt");
  }
}
