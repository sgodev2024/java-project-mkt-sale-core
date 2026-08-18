package vn.coreplatform.jobs;

import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduler (E7-S04): leader election qua lease trên async.scheduler_lock — đúng một
 * instance tạo job theo lịch; idempotency key theo slot đảm bảo hai leader (do race)
 * cũng chỉ tạo đúng MỘT job cho mỗi slot logic. Misfire ngoài grace window bị bỏ qua.
 */
@Component
@ConditionalOnProperty(name = "core.jobs.scheduler-enabled", havingValue = "true")
public class JobScheduler {
  static final Logger log = LoggerFactory.getLogger(JobScheduler.class);
  private final JdbcTemplate jdbc;
  private final JobService jobs;
  private final String schedulerId;
  private final int leaderLeaseSeconds;

  public JobScheduler(JdbcTemplate jdbc, JobService jobs, @Value("${core.jobs.leader-lease-seconds:15}") int leaderLeaseSeconds) {
    this.jdbc = jdbc; this.jobs = jobs; this.leaderLeaseSeconds = leaderLeaseSeconds;
    this.schedulerId = "scheduler-" + UUID.randomUUID().toString().substring(0, 8);
  }

  @Scheduled(fixedDelayString = "${core.jobs.scheduler-poll-ms:5000}", initialDelayString = "${core.jobs.scheduler-initial-delay-ms:5000}")
  public void schedulerLoop() { tick(); }

  public int tick() {
    if (!tryAcquireLeadership()) return 0;
    var requeued = jobs.requeueStale();
    if (requeued > 0) log.info("Requeued {} stale jobs from dead workers", requeued);
    return fireDueSchedules();
  }

  private boolean tryAcquireLeadership() {
    var updated = jdbc.update("""
        update async.scheduler_lock set leader_id=?, lease_until=now() + make_interval(secs => ?)
        where id=1 and (leader_id is null or leader_id=? or lease_until < now())
        """, schedulerId, leaderLeaseSeconds, schedulerId);
    return updated > 0;
  }

  private int fireDueSchedules() {
    var fired = 0;
    var schedules = jdbc.query("""
        select id, tenant_key, job_type, payload::text, interval_seconds, misfire_grace_seconds, last_fired_at
        from async.job_schedule where enabled = true
          and (last_fired_at is null or last_fired_at + make_interval(secs => interval_seconds) <= now())
        """, (r, n) -> new Object[]{r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getString(4),
        r.getInt(5), r.getInt(6), r.getTimestamp(7)});
    for (Object[] s : schedules) {
      var id = (UUID) s[0];
      var tenantKey = (String) s[1];
      var jobType = (String) s[2];
      var payload = (String) s[3];
      var intervalSeconds = (Integer) s[4];
      var misfireGrace = (Integer) s[5];
      var lastFired = s[6] == null ? null : ((java.sql.Timestamp) s[6]).toInstant();

      var dueSlot = lastFired == null ? Instant.now() : lastFired.plusSeconds(intervalSeconds);
      while (dueSlot.plusSeconds(intervalSeconds).isBefore(Instant.now())) dueSlot = dueSlot.plusSeconds(intervalSeconds);
      if (Instant.now().isAfter(dueSlot.plusSeconds(misfireGrace))) {
        log.info("Schedule {} misfire beyond grace — skipping to now", id);
        dueSlot = Instant.now();
      }
      var idempotencyKey = "schedule-" + id + "-" + dueSlot.getEpochSecond();
      var created = jobs.enqueue(tenantKey, jobType, payload, idempotencyKey);
      var advanced = jdbc.update("update async.job_schedule set last_fired_at=? where id=? and (last_fired_at is null or last_fired_at < ?)",
          java.sql.Timestamp.from(dueSlot), id, java.sql.Timestamp.from(dueSlot));
      if (created || advanced > 0) fired++;
    }
    return fired;
  }
}
