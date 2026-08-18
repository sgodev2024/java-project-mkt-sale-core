package vn.coreplatform.eventing;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import vn.coreplatform.AbstractApiTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E6-S03 + E6-S04 với relay bật (context riêng; poll interval dài để scheduled loop không xen ngang,
 * test gọi claim/dispatchOnce trực tiếp). Cùng DB dùng chung với các class khác nên mọi bước đều
 * drain-before-seed và lọc theo resourceKey của chính definition mình tạo.
 */
@TestPropertySource(properties = {
    "core.outbox.enabled=true",
    "core.outbox.batch-size=3",
    "core.outbox.poll-interval-ms=600000",
    "core.outbox.initial-delay-ms=600000"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OutboxRelayTest extends AbstractApiTest {
  @Autowired OutboxService outbox;
  @Autowired OutboxRelay relay;
  @Autowired org.springframework.transaction.support.TransactionTemplate tx;

  private void drain() { while (relay.dispatchOnce() > 0) { /* deliver hết pending còn lại */ } }

  private String seedDefinition(String prefix) throws Exception {
    var admin = adminToken();
    var key = prefix + "-" + suffix();
    mvc.perform(post("/api/v1/dynamic/definitions").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"resourceKey\":\"%s\",\"name\":\"E6 Relay\",\"classification\":\"INTERNAL\",\"schema\":{\"fields\":[{\"key\":\"code\",\"type\":\"string\"}]}}"
                .formatted(key)))
        .andExpect(status().isCreated());
    return key;
  }

  private List<java.util.UUID> createRecords(String key, int count) throws Exception {
    var admin = adminToken();
    var ids = new ArrayList<java.util.UUID>();
    for (int i = 0; i < count; i++) {
      var body = mvc.perform(post("/api/v1/dynamic/%s/records".formatted(key)).with(bearer(admin)).contentType(APPLICATION_JSON)
              .content("{\"code\":\"R-%d\"}".formatted(i)))
          .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
      ids.add(java.util.UUID.fromString(json.readTree(body).get("id").asText()));
    }
    return ids;
  }

  @Test @Order(1)
  void twoConcurrentWorkersNeverClaimTheSameEvent() throws Exception {
    drain();
    var key = seedDefinition("e6-lease");
    var recordIds = createRecords(key, 6);

    var executor = Executors.newFixedThreadPool(2);
    List<Future<List<OutboxService.ClaimedEvent>>> futures;
    try {
      futures = executor.invokeAll(List.of(
          (Callable<List<OutboxService.ClaimedEvent>>) () -> outbox.claim("worker-A"),
          (Callable<List<OutboxService.ClaimedEvent>>) () -> outbox.claim("worker-B")));
    } finally { executor.shutdownNow(); }
    var fromA = futures.get(0).get(30, TimeUnit.SECONDS);
    var fromB = futures.get(1).get(30, TimeUnit.SECONDS);
    fromA.forEach(e -> outbox.markDelivered(e.id()));
    fromB.forEach(e -> outbox.markDelivered(e.id()));

    var mine = new ArrayList<java.util.UUID>();
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    java.util.function.Consumer<OutboxService.ClaimedEvent> collectMine = e -> {
      try { if (key.equals(mapper.readTree(e.payload() == null ? "{}" : e.payload()).path("resourceKey").asText())) mine.add(e.id()); }
      catch (Exception ignored) { /* payload lạ thì bỏ qua */ }
    };
    fromA.forEach(collectMine);
    fromB.forEach(collectMine);

    var idsA = fromA.stream().map(OutboxService.ClaimedEvent::id).toList();
    var idsB = fromB.stream().map(OutboxService.ClaimedEvent::id).toList();
    assertThat(idsA).doesNotContainAnyElementsOf(idsB); // hai worker không trùng lease
    assertThat(mine.size()).as("claim đủ 6 event của definition này, không trùng nhau").isEqualTo(6);
    assertThat(new java.util.HashSet<>(mine).size()).isEqualTo(6);
    Assertions.assertEquals(recordIds.size(), 6);
  }

  @Test @Order(2)
  void relayDeliversOnceAndReplayDoesNotDuplicateSideEffect() throws Exception {
    drain();
    var key = seedDefinition("e6-deliver");
    var recordId = createRecords(key, 1).getFirst().toString();

    assertThat(relay.dispatchOnce()).isGreaterThan(0);
    assertThat(jdbc.queryForObject("select status from async.outbox_event where event_type='dynamic-record.created.v1' and aggregate_id=?", String.class, recordId))
        .isEqualTo("DELIVERED");
    var activityCount = jdbc.queryForObject("select count(*) from platform.activity where name='dynamic-record.created.v1' and metadata like '%'||?||'%'", Integer.class, recordId.substring(0, 8));
    assertThat(activityCount).as("side effect (activity) đúng một lần").isEqualTo(1);

    // E6-S05: replay event DELIVERED -> dispatch lại -> inbox chặn side effect lần hai
    var eventId = jdbc.queryForObject("select id from async.outbox_event where event_type='dynamic-record.created.v1' and aggregate_id=?", java.util.UUID.class, recordId);
    mvc.perform(post("/api/v1/control-plane/outbox/%s/replay".formatted(eventId)).with(bearer(adminToken())))
        .andExpect(status().isOk());
    relay.dispatchOnce();
    var afterReplay = jdbc.queryForObject("select count(*) from platform.activity where name='dynamic-record.created.v1' and metadata like '%'||?||'%'", Integer.class, recordId.substring(0, 8));
    assertThat(afterReplay).as("replay không được tạo side effect lần hai").isEqualTo(1);
    var replayAudit = jdbc.queryForObject("select count(*) from audit.event where action='OUTBOX_REPLAYED' and resource_id=?", Integer.class, eventId.toString());
    assertThat(replayAudit).isEqualTo(1);
  }

  @Test @Order(3)
  void failingHandlerRetriesThenGoesDeadAndReplayResets() throws Exception {
    drain();
    tx.executeWithoutResult(status -> outbox.publish("default", "e6.fail-sample.v1", "e6-fail", "e6-fail-" + suffix(), null));
    var id = jdbc.queryForObject("select id from async.outbox_event where event_type='e6.fail-sample.v1' order by created_at desc limit 1", java.util.UUID.class);

    relay.dispatchOnce(); // handler ném lỗi -> RETRYING, available_at = now()+backoff
    assertThat(jdbc.queryForObject("select status from async.outbox_event where id=?", String.class, id)).isIn("RETRYING", "DEAD");
    for (int i = 0; i < 6; i++) {
      jdbc.update("update async.outbox_event set available_at=now() where id=?", id);
      relay.dispatchOnce();
    }
    assertThat(jdbc.queryForObject("select status from async.outbox_event where id=?", String.class, id))
        .as("vượt max-attempts phải vào DEAD (DLQ)").isEqualTo("DEAD");

    mvc.perform(post("/api/v1/control-plane/outbox/%s/replay".formatted(id)).with(bearer(adminToken())))
        .andExpect(status().isOk());
    assertThat(jdbc.queryForObject("select status||'/'||attempts from async.outbox_event where id=?", String.class, id)).isEqualTo("PENDING/0");
    jdbc.update("delete from async.outbox_event where id=?", id); // dọn
  }

  /** Handler cố tình fail — đăng ký qua @TestConfiguration vì component scan không thấy inner class của test. */
  @org.springframework.boot.test.context.TestConfiguration
  static class FailingHandlerConfig {
    @org.springframework.context.annotation.Bean
    FailingHandler failingHandler() { return new FailingHandler(); }
  }

  static class FailingHandler implements IntegrationEventHandler {
    @Override public String eventType() { return "e6.fail-sample.v1"; }
    @Override public void handle(IntegrationEvent event) { throw new IllegalStateException("simulated consumer failure"); }
  }
}
