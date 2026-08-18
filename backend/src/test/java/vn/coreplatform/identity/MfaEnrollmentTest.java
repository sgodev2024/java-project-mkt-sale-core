package vn.coreplatform.identity;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import vn.coreplatform.AbstractApiTest;
import vn.coreplatform.security.SecurityConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * E3-S04: TOTP per-account + recovery codes + fail-closed.
 * Context riêng với core.mfa.allow-bootstrap=false — đúng cấu hình production
 * (@TestPropertySource của subclass override của superclass theo tài liệu Spring).
 */
@org.springframework.test.context.TestPropertySource(properties = "core.mfa.allow-bootstrap=false")
class MfaEnrollmentTest extends AbstractApiTest {
  @Autowired JdbcTemplate jdbc;

  private String loginAndGetChallenge(String email, String password) throws Exception {
    return json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("challengeId").asText();
  }

  private void completeMfa(String challengeId, String code) throws Exception {
    mvc.perform(post("/api/v1/auth/mfa").contentType(APPLICATION_JSON)
            .content("{\"challengeId\":\"%s\",\"code\":\"%s\",\"remember\":false}".formatted(challengeId, code)))
        .andExpect(status().isOk());
  }

  @Test
  void adminWithoutEnrollmentCannotLoginWhenBootstrapDisabled() throws Exception {
    jdbc.update("delete from identity.mfa_enrollment where account_id=(select id from identity.account where email='admin@core.local')");
    mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
            .content("{\"email\":\"admin@core.local\",\"password\":\"%s\"}".formatted(ADMIN_TEST_PASSWORD)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.title").value("MFA_ENROLLMENT_REQUIRED"));
  }

  @Test
  void totpCodeAuthenticatesAndBootstrapCodeIsRejected() throws Exception {
    var email = "e3-totp-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
    seedDefaultTenantAccount(email, "TotpUser@2026xx");
    var secret = Totp.generateSecret();
    var accountId = jdbc.queryForObject("select id from identity.account where email=?", UUID.class, email);
    jdbc.update("insert into identity.mfa_enrollment(account_id,tenant_id,secret_base32,confirmed_at,recovery_code_hashes) values(?,?,?,now(),'{}'::text[]) on conflict (account_id) do update set secret_base32=excluded.secret_base32, confirmed_at=now()",
        accountId, jdbc.queryForObject("select tenant_id from identity.account where email=?", UUID.class, email), secret);

    var challengeId = loginAndGetChallenge(email, "TotpUser@2026xx");
    mvc.perform(post("/api/v1/auth/mfa").contentType(APPLICATION_JSON)
            .content("{\"challengeId\":\"%s\",\"code\":\"%s\",\"remember\":false}".formatted(challengeId, MFA_CODE)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.title").value("INVALID_MFA_CODE"));

    challengeId = loginAndGetChallenge(email, "TotpUser@2026xx");
    completeMfa(challengeId, Totp.code(secret, 0));
  }

  @Test
  void recoveryCodeAuthenticatesOnceAndIsConsumed() throws Exception {
    var email = "e3-recovery-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
    seedDefaultTenantAccount(email, "RecoveryUser@26x");
    var secret = Totp.generateSecret();
    var accountId = jdbc.queryForObject("select id from identity.account where email=?", UUID.class, email);
    var recovery = "AB23CD45EF";
    jdbc.update("insert into identity.mfa_enrollment(account_id,tenant_id,secret_base32,confirmed_at,recovery_code_hashes) values(?,?,?,now(),?::text[]) on conflict (account_id) do update set confirmed_at=now(), recovery_code_hashes=excluded.recovery_code_hashes",
        accountId, jdbc.queryForObject("select tenant_id from identity.account where email=?", UUID.class, email), secret, "{" + SecurityConfig.sha256(recovery) + "}");

    var challengeId = loginAndGetChallenge(email, "RecoveryUser@26x");
    completeMfa(challengeId, recovery);

    challengeId = loginAndGetChallenge(email, "RecoveryUser@26x");
    mvc.perform(post("/api/v1/auth/mfa").contentType(APPLICATION_JSON)
            .content("{\"challengeId\":\"%s\",\"code\":\"%s\",\"remember\":false}".formatted(challengeId, recovery)))
        .andExpect(status().isUnauthorized());

    var used = jdbc.queryForObject("select count(*) from audit.event where action='AUTH_MFA_RECOVERY_USED'", Integer.class);
    assertThat(used).isGreaterThanOrEqualTo(1);
  }
}
