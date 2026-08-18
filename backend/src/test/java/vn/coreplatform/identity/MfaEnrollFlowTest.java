package vn.coreplatform.identity;

import org.junit.jupiter.api.Test;
import vn.coreplatform.AbstractApiTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** E3-S04: luồng enroll/confirm TOTP đầy đủ qua API (context bootstrap-allowed như môi trường demo). */
class MfaEnrollFlowTest extends AbstractApiTest {

  @Test
  void enrollConfirmThenLoginWithTotp() throws Exception {
    var admin = adminToken(); // bootstrap code (demo path)

    var enroll = mvc.perform(post("/api/v1/auth/mfa/enroll").with(bearer(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.secret").exists())
        .andExpect(jsonPath("$.otpauthUri").value(org.hamcrest.Matchers.containsString("otpauth://totp/")))
        .andReturn().getResponse().getContentAsString();
    var secret = json.readTree(enroll).get("secret").asText();

    var confirm = mvc.perform(post("/api/v1/auth/mfa/confirm").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"code\":\"%s\"}".formatted(Totp.code(secret, 0))))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    var recoveryCodes = json.readTree(confirm).get("recoveryCodes");
    assertThat(recoveryCodes.size()).isEqualTo(8);

    // từ giờ đăng nhập bằng đúng mã TOTP sinh từ secret đã confirm
    var login = mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
            .content("{\"email\":\"admin@core.local\",\"password\":\"%s\"}".formatted(ADMIN_TEST_PASSWORD)))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    var challengeId = json.readTree(login).get("challengeId").asText();
    mvc.perform(post("/api/v1/auth/mfa").contentType(APPLICATION_JSON)
            .content("{\"challengeId\":\"%s\",\"code\":\"%s\",\"remember\":false}".formatted(challengeId, Totp.code(secret, 0))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").exists());

    // dọn trạng thái để các test khác dùng bootstrap path
    jdbc.update("delete from identity.mfa_enrollment where account_id=(select id from identity.account where email='admin@core.local')");
  }

  @Test
  void confirmWithWrongCodeFails() throws Exception {
    var admin = adminToken();
    mvc.perform(post("/api/v1/auth/mfa/enroll").with(bearer(admin))).andExpect(status().isOk());
    mvc.perform(post("/api/v1/auth/mfa/confirm").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"code\":\"000000\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("MFA_CONFIRM_FAILED"));
    jdbc.update("delete from identity.mfa_enrollment where account_id=(select id from identity.account where email='admin@core.local')");
  }
}
