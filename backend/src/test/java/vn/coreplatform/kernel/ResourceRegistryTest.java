package vn.coreplatform.kernel;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import vn.coreplatform.AbstractApiTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** E4-S01: duplicate owner/type và descriptor drift không bao giờ được chấp nhận im lặng. */
class ResourceRegistryTest extends AbstractApiTest {
  @Autowired ResourceRegistry registry;

  private ResourceDescriptor sample(String key, String name, String classification) {
    return new ResourceDescriptor(key, name, "dynamic-resource", "DYNAMIC", "v1",
        java.util.List.of("READ", "CREATE", "UPDATE", "DELETE"), "ALWAYS", classification);
  }

  @Test
  void identicalRegistrationIsIdempotent() {
    var key = "e4-idem-" + suffix();
    registry.register(sample(key, "Idem Resource", "INTERNAL"));
    registry.register(sample(key, "Idem Resource", "INTERNAL"));
    var count = jdbc.queryForObject("select count(*) from platform.resource_descriptor where resource_type=?", Integer.class, key);
    assertThat(count).isEqualTo(1);
  }

  @Test
  void driftIsRejectedWithConflict() {
    var key = "e4-drift-" + suffix();
    registry.register(sample(key, "Drift Resource", "INTERNAL"));
    var drift = org.assertj.core.api.Assertions.catchThrowable(() -> registry.register(sample(key, "Drift Resource khác", "INTERNAL")));
    assertThat(drift).isInstanceOf(vn.coreplatform.shared.ApiExceptionHandler.ApiProblem.class)
        .hasMessageContaining("không được ghi đè im lặng");
    var classificationDrift = org.assertj.core.api.Assertions.catchThrowable(() -> registry.register(sample(key, "Drift Resource", "CONFIDENTIAL")));
    assertThat(classificationDrift).isInstanceOf(vn.coreplatform.shared.ApiExceptionHandler.ApiProblem.class);
    var storedName = jdbc.queryForObject("select name from platform.resource_descriptor where resource_type=?", String.class, key);
    assertThat(storedName).isEqualTo("Drift Resource");
  }

  @Test
  void unknownOwnerModuleIsRejected() {
    var drift = org.assertj.core.api.Assertions.catchThrowable(() -> registry.register(
        new ResourceDescriptor("e4-ghost-" + suffix(), "Ghost", "ghost-module", "DYNAMIC", "v1",
            java.util.List.of("READ"), "ALWAYS", null)));
    assertThat(drift).isInstanceOf(vn.coreplatform.shared.ApiExceptionHandler.ApiProblem.class)
        .hasMessageContaining("ghost-module");
  }

  @Test
  void controlPlaneCreateResourceGoesThroughRegistry() throws Exception {
    var admin = adminToken();
    var name = "E4 Domain Sample " + suffix();
    var body = mvc.perform(post("/api/v1/control-plane/resources").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"name\":\"%s\",\"storageMode\":\"DOMAIN\",\"ownerModule\":\"file-management\",\"schemaVersion\":\"v2\"}".formatted(name)))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    var id = json.readTree(body).get("id").asText();

    // tạo lại TRÙNG TÊN (cùng slug -> cùng resource_type) nhưng storage mode khác -> drift 409
    mvc.perform(post("/api/v1/control-plane/resources").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"name\":\"%s\",\"storageMode\":\"DYNAMIC\",\"ownerModule\":\"file-management\",\"schemaVersion\":\"v2\"}".formatted(name)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.title").value("DESCRIPTOR_DRIFT"));

    mvc.perform(post("/api/v1/control-plane/resources").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"name\":\"E4 Owner Test\",\"storageMode\":\"DOMAIN\",\"ownerModule\":\"chua-dang-ky\",\"schemaVersion\":\"v1\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.title").value("OWNER_MODULE_NOT_FOUND"));
  }
}
