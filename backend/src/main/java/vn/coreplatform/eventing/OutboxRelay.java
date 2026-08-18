package vn.coreplatform.eventing;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Relay worker (E6-S03): claim batch theo lease rồi dispatch tới các handler đã đăng ký.
 * Handler được wrap consumeOnce nên duplicate delivery không tạo side effect lần hai.
 * Tắt trong test (core.outbox.enabled=false mặc định) để assertion deterministic;
 * production/docker bật CORE_OUTBOX_ENABLED=true.
 */
@Component
@ConditionalOnProperty(name = "core.outbox.enabled", havingValue = "true")
public class OutboxRelay {
  static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
  static final ObjectMapper MAPPER = new ObjectMapper();
  private final OutboxService outbox;
  private final List<IntegrationEventHandler> handlers;
  private final String workerId;

  public OutboxRelay(OutboxService outbox, List<IntegrationEventHandler> handlers) {
    this.outbox = outbox; this.handlers = handlers;
    String host;
    try { host = java.net.InetAddress.getLocalHost().getHostName(); } catch (Exception e) { host = "localhost"; }
    this.workerId = host + "-" + UUID.randomUUID().toString().substring(0, 8);
  }

  @Scheduled(fixedDelayString = "${core.outbox.poll-interval-ms:5000}", initialDelayString = "${core.outbox.initial-delay-ms:3000}")
  public void dispatchLoop() {
    dispatchOnce();
  }

  public int dispatchOnce() {
    var claimed = outbox.claim(workerId);
    for (var event : claimed) {
      try {
        dispatch(event);
        outbox.markDelivered(event.id());
      } catch (Exception e) {
        log.warn("Outbox dispatch failed for {} ({}): {}", event.id(), event.eventType(), e.getMessage());
        outbox.markFailed(event.id(), e.getMessage());
      }
    }
    return claimed.size();
  }

  private void dispatch(OutboxService.ClaimedEvent event) throws Exception {
    var envelope = new IntegrationEvent(
        UUID.fromString(event.eventId()), event.eventType(), schemaVersion(event.eventType()),
        event.tenantKey(), event.aggregateType(), event.aggregateId(), Instant.now(),
        MAPPER.readTree(event.payload() == null ? "{}" : event.payload()));
    var matched = 0;
    for (var handler : handlers) {
      if (!handler.eventType().equals(event.eventType())) continue;
      matched++;
      handler.handle(envelope);
    }
    if (matched == 0) log.info("No handler for {} — delivered as no-op", event.eventType());
  }

  private String schemaVersion(String eventType) {
    var parts = eventType.split("\\.");
    return parts[parts.length - 1];
  }
}
