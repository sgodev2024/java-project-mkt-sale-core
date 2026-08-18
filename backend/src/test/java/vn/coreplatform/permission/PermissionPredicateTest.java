package vn.coreplatform.permission;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.coreplatform.AbstractApiTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E4-S04: list/search phải lọc ở SQL (WHERE + LIMIT/OFFSET + COUNT), không tải toàn bộ
 * rồi lọc trong memory. Bằng chứng: ownerOnly user thấy đúng total của mình qua các trang.
 */
class PermissionPredicateTest extends AbstractApiTest {
  String admin;
  String userToken;
  String resourceKey;

  @BeforeEach void seed() throws Exception {
    admin = adminToken();
    resourceKey = "e4-pred-" + suffix();
    mvc.perform(post("/api/v1/dynamic/definitions").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"resourceKey\":\"%s\",\"name\":\"Predicate Doc\",\"classification\":\"INTERNAL\",\"schema\":{\"fields\":[{\"key\":\"code\",\"type\":\"string\",\"required\":true}]}}"
                .formatted(resourceKey)))
        .andExpect(status().isCreated());

    var email = "e4-pred-" + suffix() + "@test.local";
    seedDefaultTenantAccount(email, "PredicateUser@2026");
    userToken = login(email, "PredicateUser@2026");

    for (int i = 0; i < 15; i++)
      createRecord(admin, "ADMIN-%02d".formatted(i));
    for (int i = 0; i < 5; i++)
      createRecord(userToken, "USER-%02d".formatted(i));
  }

  private void createRecord(String token, String code) throws Exception {
    mvc.perform(post("/api/v1/dynamic/%s/records".formatted(resourceKey)).with(bearer(token)).contentType(APPLICATION_JSON)
            .content("{\"code\":\"%s\"}".formatted(code)))
        .andExpect(status().isCreated());
  }

  @Test void paginationReflectsFilteredTotalsNotInMemoryFiltering() throws Exception {
    // user chỉ có 5 record của mình; nếu lọc ở memory sau khi tải trang 25 dòng thì
    // total/items sẽ sai (thấy 0 hoặc tổng 20). SQL predicate cho total=5 ở mọi trang.
    var page1 = mvc.perform(get("/api/v1/dynamic/%s/records".formatted(resourceKey)).with(bearer(userToken))
            .queryParam("page", "0").queryParam("size", "3"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(5))
        .andReturn().getResponse().getContentAsString();
    assertThat(json.readTree(page1).get("items").size()).isEqualTo(3);

    var page2 = mvc.perform(get("/api/v1/dynamic/%s/records".formatted(resourceKey)).with(bearer(userToken))
            .queryParam("page", "1").queryParam("size", "3"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(5))
        .andReturn().getResponse().getContentAsString();
    assertThat(json.readTree(page2).get("items").size()).isEqualTo(2);

    mvc.perform(get("/api/v1/dynamic/%s/records".formatted(resourceKey)).with(bearer(userToken))
            .queryParam("page", "2").queryParam("size", "3"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(5))
        .andExpect(jsonPath("$.items.length()").value(0));
  }

  @Test void searchPredicateStaysInsideOwnerScope() throws Exception {
    mvc.perform(get("/api/v1/dynamic/%s/records".formatted(resourceKey)).with(bearer(userToken))
            .queryParam("q", "admin-0"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
    mvc.perform(get("/api/v1/dynamic/%s/records".formatted(resourceKey)).with(bearer(userToken))
            .queryParam("q", "user-0"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(5)); // USER-00..04 đều khớp "user-0"
  }

  @Test void adminSeesAllTwentyRecordsWithCorrectPagination() throws Exception {
    mvc.perform(get("/api/v1/dynamic/%s/records".formatted(resourceKey)).with(bearer(admin))
            .queryParam("page", "0").queryParam("size", "25"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(20))
        .andExpect(jsonPath("$.items.length()").value(20));
  }
}
