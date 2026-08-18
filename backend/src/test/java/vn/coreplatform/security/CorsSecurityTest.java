package vn.coreplatform.security;

import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD;
import static org.springframework.http.HttpHeaders.ORIGIN;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import vn.coreplatform.AbstractApiTest;

@TestPropertySource(properties = {
    "core.mfa.allow-bootstrap=true",
    "core.cors.allowed-origin-patterns=https://crm-mkt-sale.sgodata.com"
})
class CorsSecurityTest extends AbstractApiTest {

  @Test
  void configuredProjectOriginCanPreflightLogin() throws Exception {
    mvc.perform(options("/api/v1/auth/login")
            .header(ORIGIN, "https://crm-mkt-sale.sgodata.com")
            .header(ACCESS_CONTROL_REQUEST_METHOD, "POST"))
        .andExpect(status().isOk())
        .andExpect(header().string(ACCESS_CONTROL_ALLOW_ORIGIN, "https://crm-mkt-sale.sgodata.com"));
  }

  @Test
  void unknownOriginIsRejected() throws Exception {
    mvc.perform(options("/api/v1/auth/login")
            .header(ORIGIN, "https://untrusted.example")
            .header(ACCESS_CONTROL_REQUEST_METHOD, "POST"))
        .andExpect(status().isForbidden());
  }
}
