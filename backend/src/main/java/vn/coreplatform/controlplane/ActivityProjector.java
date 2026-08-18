package vn.coreplatform.controlplane;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import vn.coreplatform.eventing.IntegrationEvent;
import vn.coreplatform.eventing.IntegrationEventHandler;
import vn.coreplatform.eventing.OutboxService;

/**
 * Demo consumer (E6-S04): cập nhật activity feed từ integration event. Side effect chạy
 * qua consumeOnce nên duplicate delivery/replay không tạo activity thứ hai.
 */
@Component
public class ActivityProjector implements IntegrationEventHandler {
  private final JdbcTemplate jdbc;
  private final OutboxService outbox;

  public ActivityProjector(JdbcTemplate jdbc, OutboxService outbox) { this.jdbc = jdbc; this.outbox = outbox; }

  @Override public String eventType() { return "dynamic-record.created.v1"; }

  @Override public void handle(IntegrationEvent event) {
    outbox.consumeOnce("activity-projector", event.eventId(), () ->
      jdbc.update("insert into platform.activity(kind, name, metadata, status) values ('EVENT', ?, ?, 'PUBLISHED')",
        event.eventType(), event.tenantKey() + " · " + event.aggregateType() + " " + event.aggregateId().substring(0, 8)));
  }
}
