package vn.coreplatform.identity;

import org.junit.jupiter.api.Test;
import vn.coreplatform.AbstractApiTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** E3-S05: API key cho service account; không bao giờ dùng được như phiên người dùng/admin. */
class ServiceAccountTest extends AbstractApiTest {
  String admin;

  private String createServiceAccount() throws Exception {
    admin = adminToken();
    var body = mvc.perform(post("/api/v1/access/service-accounts").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"name\":\"E3 Integration Worker\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.apiKey").exists())
        .andReturn().getResponse().getContentAsString();
    var apiKey = json.readTree(body).get("apiKey").asText();
    assertThat(apiKey).startsWith("cpa_");
    return apiKey;
  }

  @Test
  void apiKeyAuthenticatesAsServiceRoleButNeverAsAdmin() throws Exception {
    var apiKey = createServiceAccount();

    mvc.perform(get("/api/v1/auth/me").with(bearer(apiKey)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("SERVICE"));

    mvc.perform(get("/api/v1/control-plane/bootstrap").with(bearer(apiKey)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.title").value("PERMISSION_DENIED"));

    // server chỉ lưu hash, không lưu key gốc
    var hashes = jdbc.queryForList("select key_hash from identity.api_key", String.class);
    assertThat(hashes).doesNotContain(apiKey);
  }

  @Test
  void serviceAccountCannotLoginAsHuman() throws Exception {
    var apiKey = createServiceAccount();
    var email = jdbc.queryForObject("select email from identity.api_key k join identity.account a on a.id=k.account_id where k.key_hash=?", String.class,
        vn.coreplatform.security.SecurityConfig.sha256(apiKey));

    mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"whatever-guess\"}".formatted(email)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.title").value("SERVICE_ACCOUNT_LOGIN_FORBIDDEN"));

    var blocked = jdbc.queryForObject("select count(*) from audit.event where action='SERVICE_LOGIN_BLOCKED' and result='FAILED'", Integer.class);
    assertThat(blocked).isGreaterThanOrEqualTo(1);
  }

  @Test
  void rotationInvalidatesOldKeyAndRevokeKillsAccess() throws Exception {
    var apiKey = createServiceAccount();
    var keyId = jdbc.queryForObject("select id::text from identity.api_key where key_hash=?", String.class, vn.coreplatform.security.SecurityConfig.sha256(apiKey));

    var rotated = json.readTree(mvc.perform(post("/api/v1/access/service-accounts/%s/rotate".formatted(keyId)).with(bearer(adminToken())))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("apiKey").asText();
    assertThat(rotated).isNotEqualTo(apiKey);

    mvc.perform(get("/api/v1/auth/me").with(bearer(rotated))).andExpect(status().isOk());
    mvc.perform(get("/api/v1/auth/me").with(bearer(apiKey))).andExpect(status().is4xxClientError());

    var rotatedId = jdbc.queryForObject("select id::text from identity.api_key where key_hash=?", String.class, vn.coreplatform.security.SecurityConfig.sha256(rotated));
    mvc.perform(post("/api/v1/access/service-accounts/%s/revoke".formatted(rotatedId)).with(bearer(adminToken())))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/auth/me").with(bearer(rotated))).andExpect(status().is4xxClientError());
  }

  @Test
  void nonAdminCannotManageServiceAccounts() throws Exception {
    var email = "e3-svc-" + suffix() + "@test.local";
    seedDefaultTenantAccount(email, "PlainUser@2026x");
    mvc.perform(get("/api/v1/access/service-accounts").with(bearer(login(email, "PlainUser@2026x"))))
        .andExpect(status().isForbidden());
  }
}
