package vn.coreplatform.eventing;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import vn.coreplatform.shared.ApiExceptionHandler.ApiProblem;

/**
 * Outbox (E6-S02/S03/S04/S05):
 * - publish() ghi event trong CÙNG transaction của thao tác nghiệp vụ — commit nghiệp vụ
 *   thì event chắc chắn đã commit, rollback thì không có event mồ côi.
 * - claim() dùng SELECT ... FOR UPDATE SKIP LOCKED: nhiều worker không bao giờ cùng giữ
 *   một event; crash giữa chừng chỉ có thể duplicate, không mất.
 * - consumeOnce() là inbox idempotent: side effect chạy đúng một lần cho (consumer, event).
 * - retry với backoff lũy tiến; vượt max-attempts thì DEAD (DLQ); replay chỉ reset trạng thái,
 *   inbox không bị xóa nên replay không tạo side effect lần hai.
 */
@Service
public class OutboxService {
  static final Logger log = LoggerFactory.getLogger(OutboxService.class);

  private final JdbcTemplate jdbc;
  private final int batchSize;
  private final int maxAttempts;

  public OutboxService(JdbcTemplate jdbc,
                       @Value("${core.outbox.batch-size:20}") int batchSize,
                       @Value("${core.outbox.max-attempts:5}") int maxAttempts) {
    this.jdbc = jdbc; this.batchSize = batchSize; this.maxAttempts = maxAttempts;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void publish(String tenantKey, String eventType, String aggregateType, String aggregateId, JsonNode payload) {
    var id = UUID.randomUUID();
    jdbc.update("""
        insert into async.outbox_event(id, event_id, event_type, schema_version, tenant_key, aggregate_type, aggregate_id, payload, occurred_at, status, attempts, available_at)
        values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, now(), 'PENDING', 0, now())
        """, id, id, eventType, schemaVersionOf(eventType), tenantKey, aggregateType, aggregateId, payload == null ? "{}" : payload.toString());
  }

  public record ClaimedEvent(UUID id, String eventId, String eventType, String tenantKey, String aggregateType, String aggregateId, String payload) {}

  /** Atomically claim một batch; SKIP LOCKED đảm bảo hai worker không trùng event. */
  @Transactional
  public List<ClaimedEvent> claim(String workerId) {
    return jdbc.query("""
        with candidate as (
          select id from async.outbox_event
          where status in ('PENDING','RETRYING') and available_at <= now()
          order by created_at limit ? for update skip locked
        )
        update async.outbox_event o set status='DISPATCHING', locked_by=?, locked_at=now(), attempts=attempts+1
        from candidate where o.id = candidate.id
        returning o.id, o.event_id::text, o.event_type, o.tenant_key, o.aggregate_type, o.aggregate_id, o.payload::text
        """, (r, n) -> new ClaimedEvent(r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6), r.getString(7)), batchSize, workerId);
  }

  public void markDelivered(UUID id) {
    jdbc.update("update async.outbox_event set status='DELIVERED', delivered_at=now(), last_error=null where id=?", id);
  }

  public void markFailed(UUID id, String error) {
    var trimmed = error == null ? "unknown" : error.substring(0, Math.min(error.length(), 390));
    var attempts = jdbc.queryForObject("select attempts from async.outbox_event where id=?", Integer.class, id);
    if (attempts != null && attempts >= maxAttempts) {
      jdbc.update("update async.outbox_event set status='DEAD', last_error=? where id=?", trimmed, id);
    } else {
      // backoff lũy tiến: 2^attempts giây, trần 5 phút
      jdbc.update("update async.outbox_event set status='RETRYING', last_error=?, available_at=now() + least(make_interval(secs => power(2, attempts)), interval '5 minutes') where id=?", trimmed, id);
    }
  }

  /** Inbox idempotent: chạy side effect đúng một lần cho (consumerId, eventId). */
  @Transactional
  public boolean consumeOnce(String consumerId, UUID eventId, Runnable sideEffect) {
    int inserted = jdbc.update("insert into async.inbox_event(consumer_id, event_id) values (?, ?) on conflict do nothing", consumerId, eventId);
    if (inserted == 0) return false;
    sideEffect.run();
    return true;
  }

  /** E6-S05: replay DEAD event — inbox không bị xóa nên side effect không lặp lại. */
  @Transactional
  public void replay(UUID id, String actorEmail) {
    var rows = jdbc.queryForList("select status, event_type from async.outbox_event where id=?", id);
    if (rows.isEmpty()) throw new ApiProblem(HttpStatus.NOT_FOUND, "OUTBOX_EVENT_NOT_FOUND", "Event không tồn tại");
    if (!"DEAD".equals(rows.getFirst().get("status")) && !"DELIVERED".equals(rows.getFirst().get("status")))
      throw new ApiProblem(HttpStatus.CONFLICT, "OUTBOX_EVENT_NOT_REPLAYABLE", "Chỉ event DEAD/DELIVERED mới được replay");
    jdbc.update("update async.outbox_event set status='PENDING', attempts=0, available_at=now(), last_error=null where id=?", id);
    log.info("Outbox event {} replayed by {}", id, actorEmail);
  }

  private String schemaVersionOf(String eventType) {
    var parts = eventType.split("\\.");
    return parts[parts.length - 1];
  }
}
