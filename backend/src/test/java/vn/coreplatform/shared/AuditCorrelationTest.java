package vn.coreplatform.shared;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.coreplatform.AbstractApiTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuditCorrelationTest extends AbstractApiTest {

  @Test
  void incomingCorrelationIdIsEchoedAndStoredOnAuditEvent() throws Exception {
    var correlationId = UUID.randomUUID();
    mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
            .header(CorrelationIdFilter.HEADER, correlationId.toString())
            .content("{\"email\":\"admin@core.local\",\"password\":\"%s\"}".formatted(ADMIN_TEST_PASSWORD)))
        .andExpect(status().isOk())
        .andExpect(header().string(CorrelationIdFilter.HEADER, correlationId.toString()));

    var count = jdbc.queryForObject("select count(*) from audit.event where correlation_id=?::uuid and action='AUTH_LOGIN_CHALLENGE'", Integer.class, correlationId.toString());
    assertThat(count).isEqualTo(1);
  }

  @Test
  void errorEnvelopeCarriesCorrelationId() throws Exception {
    var correlationId = UUID.randomUUID();
    mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
            .header(CorrelationIdFilter.HEADER, correlationId.toString())
            .content("{\"email\":\"admin@core.local\",\"password\":\"wrong-password\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string(CorrelationIdFilter.HEADER, correlationId.toString()))
        .andExpect(jsonPath("$.correlationId").value(correlationId.toString()));
  }

  @Test
  void malformedCorrelationIdIsReplacedNotTrusted() throws Exception {
    var response = mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
            .header(CorrelationIdFilter.HEADER, "../../etc/passwd<script>")
            .content("{\"email\":\"admin@core.local\",\"password\":\"%s\"}".formatted(ADMIN_TEST_PASSWORD)))
        .andExpect(status().isOk()).andReturn().getResponse();
    var generated = response.getHeader(CorrelationIdFilter.HEADER);
    assertThat(generated).isNotEqualTo("../../etc/passwd<script>");
    assertThat(generated).matches("[0-9a-f-]{36}");
  }

  @Test
  void requestWithoutHeaderGetsGeneratedCorrelationIdOnAudit() throws Exception {
    mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
            .content("{\"email\":\"admin@core.local\",\"password\":\"%s\"}".formatted(ADMIN_TEST_PASSWORD)))
        .andExpect(status().isOk())
        .andExpect(header().exists(CorrelationIdFilter.HEADER));

    var missing = jdbc.queryForObject("select count(*) from audit.event where correlation_id is null and action='AUTH_LOGIN_CHALLENGE'", Integer.class);
    assertThat(missing).isZero();
  }
}
