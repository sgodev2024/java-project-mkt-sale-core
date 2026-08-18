package vn.coreplatform.identity;

import org.junit.jupiter.api.Test;
import vn.coreplatform.AbstractApiTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthFlowTest extends AbstractApiTest {

  @Test
  void loginWithWrongPasswordIsRejectedWithStructuredError() throws Exception {
    mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
            .content("{\"email\":\"admin@core.local\",\"password\":\"wrong-password\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.title").value("INVALID_CREDENTIALS"))
        .andExpect(jsonPath("$.correlationId").exists());
  }

  @Test
  void loginWithUnknownEmailGetsSameUnauthorizedResponse() throws Exception {
    mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
            .content("{\"email\":\"nobody@core.local\",\"password\":\"whatever-pass\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.title").value("INVALID_CREDENTIALS"));
  }

  @Test
  void fullLoginMfaLogoutCycle() throws Exception {
    var token = adminToken();

    mvc.perform(get("/api/v1/auth/me").with(bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value("admin@core.local"))
        .andExpect(jsonPath("$.role").value("PLATFORM_ADMIN"));

    mvc.perform(post("/api/v1/auth/logout").with(bearer(token))).andExpect(status().isNoContent());

    mvc.perform(get("/api/v1/auth/me").with(bearer(token))).andExpect(status().is4xxClientError());
  }

  @Test
  void mfaChallengeCannotBeReused() throws Exception {
    var login = mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
            .content("{\"email\":\"admin@core.local\",\"password\":\"%s\"}".formatted(ADMIN_TEST_PASSWORD)))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    var challengeId = json.readTree(login).get("challengeId").asText();

    mvc.perform(post("/api/v1/auth/mfa").contentType(APPLICATION_JSON)
            .content("{\"challengeId\":\"%s\",\"code\":\"000000\",\"remember\":false}".formatted(challengeId)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.title").value("INVALID_MFA_CODE"));

    var session = mvc.perform(post("/api/v1/auth/mfa").contentType(APPLICATION_JSON)
            .content("{\"challengeId\":\"%s\",\"code\":\"%s\",\"remember\":true}".formatted(challengeId, MFA_CODE)))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    assertThat(json.readTree(session).get("accessToken").asText()).isNotBlank();

    mvc.perform(post("/api/v1/auth/mfa").contentType(APPLICATION_JSON)
            .content("{\"challengeId\":\"%s\",\"code\":\"%s\",\"remember\":false}".formatted(challengeId, MFA_CODE)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.title").value("INVALID_MFA_CODE"));
  }

  @Test
  void invalidOrRevokedSessionTokenIsRejected() throws Exception {
    mvc.perform(get("/api/v1/auth/me").with(bearer("not-a-real-token")))
        .andExpect(status().is4xxClientError());

    var token = adminToken();
    jdbc.update("update identity.session set revoked_at=now() where token_hash=(select token_hash from identity.session order by created_at desc limit 1)");
    mvc.perform(get("/api/v1/auth/me").with(bearer(token))).andExpect(status().is4xxClientError());
  }

  @Test
  void loginChallengeIsAudited() throws Exception {
    adminToken();
    var count = jdbc.queryForObject("select count(*) from audit.event where action='AUTH_LOGIN' and actor_id=(select id from identity.account where email='admin@core.local')", Integer.class);
    assertThat(count).isGreaterThanOrEqualTo(1);
  }
}
