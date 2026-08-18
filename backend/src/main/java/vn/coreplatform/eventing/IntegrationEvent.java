package vn.coreplatform.eventing;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * Integration event envelope (E6-S01): JSON contract ổn định, không bao giờ serialize
 * entity nội bộ. Field set là hợp đồng công khai — xóa/đổi tên field là breaking change
 * và phải bump schemaVersion (EventContractTest chặn trong CI).
 * Format eventType: {@code <aggregate>.<action>.v<version>}, ví dụ dynamic-record.created.v1.
 */
public record IntegrationEvent(
    UUID eventId,
    String eventType,
    String schemaVersion,
    String tenantKey,
    String aggregateType,
    String aggregateId,
    Instant occurredAt,
    JsonNode payload) {
  public IntegrationEvent {
    if (eventType == null || !eventType.matches("[a-z][a-z0-9-]{1,79}(\\.[a-z][a-z0-9-]{1,59}){2}"))
      throw new IllegalArgumentException("eventType phải theo dạng <aggregate>.<action>.v<n>: " + eventType);
  }
}
