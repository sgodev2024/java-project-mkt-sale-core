package vn.coreplatform.identity;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import vn.coreplatform.AbstractApiTest;

@TestPropertySource(properties = "core.mfa.enabled=false")
class MfaDisabledLoginTest extends AbstractApiTest {
  @Test void issuesSessionDirectlyAndDoesNotCreateChallenge() throws Exception {
    var before = jdbc.queryForObject("select count(*) from identity.mfa_challenge", Integer.class);
    mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
            .content("{\"email\":\"admin@core.local\",\"password\":\"%s\",\"remember\":true}".formatted(ADMIN_TEST_PASSWORD)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mfaRequired").value(false))
        .andExpect(jsonPath("$.session.accessToken").isNotEmpty())
        .andExpect(jsonPath("$.session.refreshToken").isNotEmpty())
        .andExpect(jsonPath("$.session.user.role").value("PLATFORM_ADMIN"));
    var after = jdbc.queryForObject("select count(*) from identity.mfa_challenge", Integer.class);
    org.assertj.core.api.Assertions.assertThat(after).isEqualTo(before);
  }
}
