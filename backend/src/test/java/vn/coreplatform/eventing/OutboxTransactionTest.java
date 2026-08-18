package vn.coreplatform.eventing;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import vn.coreplatform.AbstractApiTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E6-S02: transactional outbox. Event được ghi trong cùng transaction với thao tác nghiệp vụ —
 * commit nghiệp vụ ⇒ event chắc chắn tồn tại; nghiệp vụ fail ⇒ không có event mồ côi
 * (crash sau commit không làm mất event vì event đã commit cùng dữ liệu).
 */
class OutboxTransactionTest extends AbstractApiTest {
  @Autowired OutboxService outbox;
  @Autowired org.springframework.transaction.support.TransactionTemplate tx;

  @Test
  void businessCommitImpliesEventCommitted_businessFailureLeavesNoEvent() throws Exception {
    var admin = adminToken();
    var key = "e6-tx-" + suffix();
    mvc.perform(post("/api/v1/dynamic/definitions").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"resourceKey\":\"%s\",\"name\":\"E6 TX\",\"classification\":\"INTERNAL\",\"schema\":{\"fields\":[{\"key\":\"code\",\"type\":\"string\",\"required\":true}]}}"
                .formatted(key)))
        .andExpect(status().isCreated());

    // tạo record hợp lệ -> event committed cùng transaction
    var created = json.readTree(mvc.perform(post("/api/v1/dynamic/%s/records".formatted(key)).with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"code\":\"E6-OK\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    var recordId = created.get("id").asText();
    assertThat(countEvents(key, "dynamic-record.created.v1", recordId)).isEqualTo(1);

    // thao tác fail (thiếu field bắt buộc) -> không có event thứ hai cho definition này
    mvc.perform(post("/api/v1/dynamic/%s/records".formatted(key)).with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isBadRequest());
    assertThat(jdbc.queryForObject("select count(*) from async.outbox_event where event_type='dynamic-record.created.v1' and payload->>'resourceKey'=?", Integer.class, key))
        .as("không event mồ côi cho thao tác fail").isEqualTo(1);

    // update + archive cũng phát event đúng loại
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .put("/api/v1/dynamic/%s/records/%s".formatted(key, recordId)).with(bearer(admin))
            .header("If-Match", "1").contentType(APPLICATION_JSON).content("{\"code\":\"E6-OK-2\"}"))
        .andExpect(status().isOk());
    mvc.perform(delete("/api/v1/dynamic/%s/records/%s".formatted(key, recordId)).with(bearer(admin)))
        .andExpect(status().isNoContent());
    assertThat(countEvents(key, "dynamic-record.updated.v1", recordId)).isEqualTo(1);
    assertThat(countEvents(key, "dynamic-record.archived.v1", recordId)).isEqualTo(1);

    // publish ngoài transaction bị từ chối (MANDATORY) — bảo đảm không ai ghi event lẻ loi
    assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
        outbox.publish("default", "e6.illegal.v1", "e6", "x", null)))
        .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);

    // trong transaction thì publish thành công
    tx.executeWithoutResult(status -> outbox.publish("default", "e6.tx-manual.v1", "e6", "manual-1", null));
    assertThat(jdbc.queryForObject("select count(*) from async.outbox_event where event_type='e6.tx-manual.v1'", Integer.class)).isEqualTo(1);
  }

  private int countEvents(String key, String eventType, String recordId) {
    return jdbc.queryForObject("select count(*) from async.outbox_event where event_type=? and aggregate_id=?",
        Integer.class, eventType, recordId);
  }
}
