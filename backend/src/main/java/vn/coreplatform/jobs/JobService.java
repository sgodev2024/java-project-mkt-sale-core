package vn.coreplatform.jobs;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.coreplatform.shared.ApiExceptionHandler.ApiProblem;

/**
 * Job queue (E7):
 * - enqueue fail-closed khi thiếu tenant (E7-S01) và idempotent theo key (dùng cho scheduler).
 * - claim bằng SELECT ... FOR UPDATE SKIP LOCKED + lease có heartbeat — worker chết thì lease
 *   hết hạn và job được reclaim an toàn (E7-S02).
 * - lỗi được phân loại: NonRetryable -> DEAD ngay; retryable -> backoff lũy tiến + jitter,
 *   vượt max-attempts -> DEAD (E7-S03). Cancel chỉ áp dụng cho job chưa chạy.
 */
@Service
public class JobService {
  static final Logger log = LoggerFactory.getLogger(JobService.class);
  private final JdbcTemplate jdbc;
  private final int batchSize;
  private final int maxAttempts;
  private final int leaseSeconds;

  public JobService(JdbcTemplate jdbc,
                    @Value("${core.jobs.batch-size:10}") int batchSize,
                    @Value("${core.jobs.max-attempts:5}") int maxAttempts,
                    @Value("${core.jobs.worker-lease-seconds:30}") int leaseSeconds) {
    this.jdbc = jdbc; this.batchSize = batchSize; this.maxAttempts = maxAttempts; this.leaseSeconds = leaseSeconds;
  }

  @Transactional
  public boolean enqueue(String tenantKey, String jobType, String payloadJson, String idempotencyKey) {
    if (tenantKey == null || tenantKey.isBlank())
      throw new ApiProblem(HttpStatus.BAD_REQUEST, "JOB_TENANT_REQUIRED", "Job phải thuộc một tenant (fail-closed)");
    int inserted = jdbc.update("""
        insert into async.job(job_type, tenant_key, payload, idempotency_key, status, attempts, available_at)
        values (?, ?, ?::jsonb, ?, 'PENDING', 0, now()) on conflict do nothing
        """, jobType, tenantKey, payloadJson == null || payloadJson.isBlank() ? "{}" : payloadJson, idempotencyKey);
    return inserted > 0;
  }

  public record ClaimedJob(UUID id, String jobType, String tenantKey, String payload) {}

  @Transactional
  public List<ClaimedJob> claim(String workerId) {
    return jdbc.query("""
        with candidate as (
          select id from async.job
          where status in ('PENDING','RETRYING') and available_at <= now()
          order by created_at limit ? for update skip locked
        )
        update async.job j set status='RUNNING', leased_by=?, leased_at=now(), lease_until=now() + make_interval(secs => ?),
            heartbeat_at=now(), attempts=attempts+1, cancelled_at=null
        from candidate where j.id = candidate.id
        returning j.id, j.job_type, j.tenant_key, j.payload::text
        """, (r, n) -> new ClaimedJob(r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getString(4)), batchSize, workerId, leaseSeconds);
  }

  /** E7-S02: gia hạn lease — chỉ owner hiện tại gia hạn được. */
  public boolean heartbeat(UUID jobId, String workerId) {
    return jdbc.update("update async.job set heartbeat_at=now(), lease_until=now() + make_interval(secs => ?) where id=? and leased_by=? and status='RUNNING'",
        leaseSeconds, jobId, workerId) > 0;
  }

  public void complete(UUID jobId) {
    jdbc.update("update async.job set status='COMPLETED', leased_by=null, lease_until=null, last_error=null where id=?", jobId);
  }

  /** E7-S02: worker chết để lease hết hạn — job được trả về hàng đợi an toàn. */
  public int requeueStale() {
    return jdbc.update("update async.job set status='RETRYING', leased_by=null, lease_until=null, last_error='lease expired', available_at=now() where status='RUNNING' and lease_until < now()");
  }

  /** E7-S03: retryable=false -> DEAD ngay (không retry vô hạn); true -> backoff lũy tiến + jitter. */
  public void fail(UUID jobId, String error, boolean retryable) {
    var trimmed = error == null ? "unknown" : error.substring(0, Math.min(error.length(), 390));
    if (!retryable) {
      jdbc.update("update async.job set status='DEAD', last_error=?, leased_by=null, lease_until=null where id=?", trimmed, jobId);
      return;
    }
    var attempts = jdbc.queryForObject("select attempts from async.job where id=?", Integer.class, jobId);
    if (attempts != null && attempts >= maxAttempts) {
      jdbc.update("update async.job set status='DEAD', last_error=?, leased_by=null, lease_until=null where id=?", trimmed, jobId);
    } else {
      // backoff 2^attempts + jitter 0..2 giây, trần 5 phút
      jdbc.update("""
          update async.job set status='RETRYING', last_error=?, leased_by=null, lease_until=null,
            available_at=now() + least(make_interval(secs => power(2, ?)), interval '5 minutes') + make_interval(secs => random()*2)
          where id=?
          """, trimmed, attempts == null ? 1 : attempts, jobId);
    }
  }

  public void cancel(UUID jobId) {
    var changed = jdbc.update("update async.job set status='CANCELLED', cancelled_at=now(), leased_by=null, lease_until=null where id=? and status in ('PENDING','RETRYING')", jobId);
    if (changed == 0) {
      var status = jdbc.queryForList("select status from async.job where id=?", String.class, jobId);
      if (status.isEmpty()) throw new ApiProblem(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", "Job không tồn tại");
      throw new ApiProblem(HttpStatus.CONFLICT, "JOB_NOT_CANCELLABLE", "Job đang RUNNING/đã kết thúc — không hủy được");
    }
  }

  public void requeue(UUID jobId) {
    var changed = jdbc.update("update async.job set status='PENDING', attempts=0, available_at=now(), last_error=null, leased_by=null, lease_until=null where id=? and status in ('DEAD','RETRYING','CANCELLED')", jobId);
    if (changed == 0) {
      var status = jdbc.queryForList("select status from async.job where id=?", String.class, jobId);
      if (status.isEmpty()) throw new ApiProblem(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", "Job không tồn tại");
      throw new ApiProblem(HttpStatus.CONFLICT, "JOB_NOT_REQUEUABLE", "Chỉ job DEAD/RETRYING/CANCELLED mới requeue được");
    }
  }
}
