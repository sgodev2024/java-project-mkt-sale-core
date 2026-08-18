package vn.coreplatform.identity;

import org.junit.jupiter.api.Test;
import vn.coreplatform.AbstractApiTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** E3-S03: refresh token rotate mỗi lần dùng; dùng lại token cũ thu hồi cả family + security audit. */
class RefreshRotationTest extends AbstractApiTest {

  private record Tokens(String accessToken, String refreshToken) {}

  private Tokens fullLogin() throws Exception {
    var loginBody = mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
            .content("{\"email\":\"admin@core.local\",\"password\":\"%s\"}".formatted(ADMIN_TEST_PASSWORD)))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    var challengeId = json.readTree(loginBody).get("challengeId").asText();
    var session = mvc.perform(post("/api/v1/auth/mfa").contentType(APPLICATION_JSON)
            .content("{\"challengeId\":\"%s\",\"code\":\"%s\",\"remember\":true}".formatted(challengeId, MFA_CODE)))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    var tree = json.readTree(session);
    return new Tokens(tree.get("accessToken").asText(), tree.get("refreshToken").asText());
  }

  private Tokens refresh(String refreshToken) throws Exception {
    var body = mvc.perform(post("/api/v1/auth/refresh").contentType(APPLICATION_JSON)
            .content("{\"refreshToken\":\"%s\"}".formatted(refreshToken)))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    var tree = json.readTree(body);
    return new Tokens(tree.get("accessToken").asText(), tree.get("refreshToken").asText());
  }

  @Test
  void refreshRotatesTokensAndOldRefreshBecomesSingleUse() throws Exception {
    var first = fullLogin();
    var second = refresh(first.refreshToken());
    assertThat(second.accessToken()).isNotEqualTo(first.accessToken());

    // dùng lại refresh ĐÃ rotate -> phát hiện reuse, thu hồi cả family
    mvc.perform(post("/api/v1/auth/refresh").contentType(APPLICATION_JSON)
            .content("{\"refreshToken\":\"%s\"}".formatted(first.refreshToken())))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.title").value("REFRESH_TOKEN_REUSE"));

    // access token mới cũng chết vì cả family bị thu hồi
    mvc.perform(get("/api/v1/auth/me").with(bearer(second.accessToken()))).andExpect(status().is4xxClientError());

    var auditCount = jdbc.queryForObject("select count(*) from audit.event where action='AUTH_REFRESH_REUSE_DETECTED' and result='FAILED'", Integer.class);
    assertThat(auditCount).isGreaterThanOrEqualTo(1);
  }

  @Test
  void logoutRevokesWholeFamilyIncludingRefreshTokens() throws Exception {
    var tokens = fullLogin();
    mvc.perform(post("/api/v1/auth/logout").with(bearer(tokens.accessToken()))).andExpect(status().isNoContent());

    mvc.perform(post("/api/v1/auth/refresh").contentType(APPLICATION_JSON)
            .content("{\"refreshToken\":\"%s\"}".formatted(tokens.refreshToken())))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.title").value("INVALID_REFRESH_TOKEN"));
  }

  @Test
  void garbageRefreshTokenIsRejected() throws Exception {
    mvc.perform(post("/api/v1/auth/refresh").contentType(APPLICATION_JSON)
            .content("{\"refreshToken\":\"not-a-token\"}"))
        .andExpect(status().isUnauthorized());
  }
}
