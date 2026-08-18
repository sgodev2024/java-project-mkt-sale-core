package vn.coreplatform.identity;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import vn.coreplatform.AbstractApiTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** E3-S02: Argon2id là chuẩn mới, hash bcrypt cũ tự rehash khi đăng nhập; lockout; reset/change password. */
class PasswordPolicyTest extends AbstractApiTest {

  @Test
  void newPasswordsAreStoredAsArgon2id() {
    var email = "e3-argon-" + suffix() + "@test.local";
    seedDefaultTenantAccount(email, "StrongPass@12345");
    var hash = jdbc.queryForObject("select password_hash from identity.account where email=?", String.class, email);
    assertThat(hash).startsWith("{argon2}");
    var algo = jdbc.queryForObject("select password_algo from identity.account where email=?", String.class, email);
    assertThat(algo).isEqualTo("ARGON2ID");
  }

  @Test
  void legacyBcryptHashIsRehashedOnSuccessfulLogin() throws Exception {
    var email = "e3-legacy-" + suffix() + "@test.local";
    seedDefaultTenantAccount(email, "WillBeReplaced@1");
    var legacy = "{bcrypt}" + new BCryptPasswordEncoder(12).encode("LegacyPass@2026x");
    jdbc.update("update identity.account set password_hash=?,password_algo='BCRYPT' where email=?", legacy, email);

    login(email, "LegacyPass@2026x");

    var hash = jdbc.queryForObject("select password_hash from identity.account where email=?", String.class, email);
    assertThat(hash).as("login thành công phải nâng cấp hash lên argon2id").startsWith("{argon2}");
    assertThat(jdbc.queryForObject("select password_algo from identity.account where email=?", String.class, email)).isEqualTo("ARGON2ID");
  }

  @Test
  void accountLocksAfterFiveFailedAttempts() throws Exception {
    var email = "e3-lock-" + suffix() + "@test.local";
    seedDefaultTenantAccount(email, "CorrectHorse@9x");
    for (int i = 0; i < AuthController.MAX_FAILED_ATTEMPTS; i++)
      mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
              .content("{\"email\":\"%s\",\"password\":\"wrong-password-%d\"}".formatted(email, i)))
          .andExpect(status().isUnauthorized());

    mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"CorrectHorse@9x\"}".formatted(email)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.title").value("ACCOUNT_LOCKED"));

    var failedAudits = jdbc.queryForObject("select count(*) from audit.event where actor_email=? and action='AUTH_LOGIN_CHALLENGE' and result='FAILED'", Integer.class, email);
    assertThat(failedAudits).isEqualTo(AuthController.MAX_FAILED_ATTEMPTS);
  }

  @Test
  void adminResetForcesPasswordChangeAndOldSessionsDie() throws Exception {
    var admin = adminToken();
    var email = "e3-reset-" + suffix() + "@test.local";
    seedDefaultTenantAccount(email, "InitialPass@1234");
    var userId = jdbc.queryForObject("select id from identity.account where email=?", java.util.UUID.class, email);

    var temp = json.readTree(mvc.perform(post("/api/v1/access/users/%s/reset-password".formatted(userId)).with(bearer(admin)))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("tempPassword").asText();
    assertThat(temp).hasSizeGreaterThanOrEqualTo(12);

    var token = login(email, temp);
    mvc.perform(get("/api/v1/files").with(bearer(token)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));

    mvc.perform(post("/api/v1/auth/change-password").with(bearer(token)).contentType(APPLICATION_JSON)
            .content("{\"currentPassword\":\"%s\",\"newPassword\":\"FreshPass@2026x\"}".formatted(temp)))
        .andExpect(status().isOk());

    mvc.perform(get("/api/v1/files").with(bearer(token))).andExpect(status().isOk());
    mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, temp)))
        .andExpect(status().isUnauthorized());
  }
}
