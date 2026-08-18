package vn.coreplatform;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base cho integration test: mặc định dùng Testcontainers PostgreSQL (CI/máy có Docker).
 * Đặt IT_DB_URL/IT_DB_USER/IT_DB_PASSWORD để chạy chống PostgreSQL ngoài (máy không có Docker);
 * Flyway tự migrate schema. Mọi dữ liệu tạo trong test phải dùng suffix ngẫu nhiên để chạy lại được.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@org.springframework.test.context.TestPropertySource(properties = "core.mfa.allow-bootstrap=true")
public abstract class AbstractApiTest {
  public static final String MFA_CODE = "246810";
  public static final String ADMIN_TEST_PASSWORD = "ItAdminPass@2026";
  private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
  private static final Path FILE_ROOT = tempRoot();

  @Autowired protected MockMvc mvc;
  @Autowired protected ObjectMapper json;
  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected PasswordEncoder encoder;

  @DynamicPropertySource
  static void testProperties(DynamicPropertyRegistry registry) {
    var externalUrl = System.getenv("IT_DB_URL");
    if (externalUrl == null) {
      synchronized (AbstractApiTest.class) {
        if (!POSTGRES.isRunning()) {
          POSTGRES.start();
          // Không dựa vào Ryuk: trên Windows npipe, Ryuk có thể tưởng JVM chết và
          // giết container giữa chừng. JVM exit thì shutdown hook tự dọn.
          Runtime.getRuntime().addShutdownHook(new Thread(POSTGRES::stop, "testcontainers-postgres-stop"));
        }
      }
      registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
      registry.add("spring.datasource.username", POSTGRES::getUsername);
      registry.add("spring.datasource.password", POSTGRES::getPassword);
    } else {
      registry.add("spring.datasource.url", () -> externalUrl);
      registry.add("spring.datasource.username", () -> env("IT_DB_USER", "core_app"));
      registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "core_app_dev"));
    }
    registry.add("core.bootstrap-mfa-code", () -> MFA_CODE);
    registry.add("core.bootstrap-admin-password", () -> ADMIN_TEST_PASSWORD);
    registry.add("core.file-storage-root", () -> FILE_ROOT.toString());
  }

  protected static String suffix() { return UUID.randomUUID().toString().substring(0, 8); }
  protected static RequestPostProcessor bearer(String token) { return req -> { req.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token); return req; }; }
  protected String adminToken() throws Exception { return login("admin@core.local", ADMIN_TEST_PASSWORD); }
  protected String login(String email, String password) throws Exception {
    var loginBody = mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    var loginResponse = json.readTree(loginBody);
    if (!loginResponse.path("mfaRequired").asBoolean(true)) return loginResponse.path("session").path("accessToken").asText();
    var challengeId = loginResponse.get("challengeId").asText();
    var sessionBody = mvc.perform(post("/api/v1/auth/mfa").contentType(APPLICATION_JSON)
            .content("{\"challengeId\":\"%s\",\"code\":\"%s\",\"remember\":false}".formatted(challengeId, MFA_CODE)))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    return json.readTree(sessionBody).get("accessToken").asText();
  }
  protected void seedDefaultTenantAccount(String email, String password) {
    var id = UUID.randomUUID();
    jdbc.update("""
        insert into identity.account(id,tenant_id,email,display_name,password_hash,password_algo,role)
        select ?,t.id,?,?,?,'ARGON2ID','APPLICATION_USER' from platform.tenant t where t.tenant_key='default'
        on conflict do nothing""", id, email, email, encoder.encode(password));
    jdbc.update("""
        insert into identity.account_role(tenant_id,account_id,role_id)
        select t.id,?,r.id from platform.tenant t join identity.role r on r.tenant_id=t.id and r.code='application-user'
        where t.tenant_key='default' and exists(select 1 from identity.account a where a.id=? and a.tenant_id=t.id)
        on conflict do nothing""", id, id);
  }
  private static Path tempRoot() {
    try { return Files.createTempDirectory("core-files"); } catch (IOException e) { throw new IllegalStateException(e); }
  }
  private static String env(String key, String fallback) { var value = System.getenv(key); return value == null ? fallback : value; }
}
