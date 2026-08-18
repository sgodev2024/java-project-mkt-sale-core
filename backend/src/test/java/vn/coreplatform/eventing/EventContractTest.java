package vn.coreplatform.eventing;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** E6-S01: integration event envelope là hợp đồng JSON ổn định — compatibility gate cho CI. */
class EventContractTest {
  private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
      new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  @Test
  void envelopeSerializesToExactlyTheContractedFieldSet() throws Exception {
    var event = new IntegrationEvent(UUID.randomUUID(), "dynamic-record.created.v1", "v1",
        "default", "dynamic-record", "6f1c2a34-5678-4bcd-9abc-def012345678",
        Instant.parse("2026-08-16T00:00:00Z"), MAPPER.readTree("{\"resourceKey\":\"x\"}"));
    var json = MAPPER.readTree(MAPPER.writeValueAsString(event));
    var fields = new java.util.TreeSet<java.lang.String>();
    json.fieldNames().forEachRemaining(fields::add);
    // xóa/đổi tên field là breaking change; thêm field optional phải qua review + ghi trong decisions
    assertThat(fields).containsExactlyInAnyOrder("eventId", "eventType", "schemaVersion", "tenantKey",
        "aggregateType", "aggregateId", "occurredAt", "payload");
    assertThat(json.get("eventId").isTextual()).isTrue();
    assertThat(json.get("occurredAt").asText()).isEqualTo("2026-08-16T00:00:00Z");
  }

  @Test
  void eventTypeMustFollowAggregateActionVersionFormat() {
    assertThatThrownBy(() -> new IntegrationEvent(UUID.randomUUID(), "created", "v1", "t", "a", "1", Instant.now(), null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new IntegrationEvent(UUID.randomUUID(), "Record.Created.v1", "v1", "t", "a", "1", Instant.now(), null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new IntegrationEvent(UUID.randomUUID(), "record.created", "v1", "t", "a", "1", Instant.now(), null))
        .isInstanceOf(IllegalArgumentException.class);
    // hợp lệ
    new IntegrationEvent(UUID.randomUUID(), "file.uploaded.v2", "v1", "t", "a", "1", Instant.now(), null);
  }

  @Test
  void payloadIsPlainJsonNeverAnEntity() {
    // payload chỉ chứa dữ liệu hợp đồng (JsonNode), không bao giờ là object entity nội bộ
    var event = new IntegrationEvent(UUID.randomUUID(), "aa.bb.v1", "v1", "t", "a", "1", Instant.now(),
        MAPPER.createObjectNode().put("resourceKey", "k").put("version", 1));
    assertThat(event.payload().get("resourceKey").asText()).isEqualTo("k");
    assertThat(event.payload()).isInstanceOf(JsonNode.class);
  }
}
