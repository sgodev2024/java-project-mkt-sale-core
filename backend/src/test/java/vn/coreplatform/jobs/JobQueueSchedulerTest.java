package vn.coreplatform.jobs;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import vn.coreplatform.AbstractApiTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * E7: job queue + scheduler. Context riêng bật worker/scheduler bean nhưng poll interval rất dài
 * (scheduled loop không xen ngang); test gọi claim/runOnce/tick trực tiếp cho deterministic.
 */
@TestPropertySource(properties = {
    "core.jobs.enabled=true",
    "core.jobs.scheduler-enabled=true",
    "core.jobs.batch-size=5",
    "core.jobs.poll-interval-ms=600000",
    "core.jobs.initial-delay-ms=600000",
    "core.jobs.scheduler-poll-ms=600000",
    "core.jobs.scheduler-initial-delay-ms=600000"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JobQueueSchedulerTest extends AbstractApiTest {
  @Autowired JobService jobs;
  @Autowired JobWorker worker;
  @Autowired JobScheduler scheduler;

  private void drain() { while (worker.runOnce() > 0) { /* hết job đang đến hạn */ } }

  private UUID enqueueTracked(String jobType, String tenantKey) {
    var key = "e7-" + suffix();
    jobs.enqueue(tenantKey, jobType, "{\"k\":\"" + key + "\"}", key);
    return jdbc.queryForObject("select id from async.job where idempotency_key=?", UUID.class, key);
  }

  @Test @Order(1)
  void s01_enqueueWithoutTenantFailsClosed() {
    var thrown = org.assertj.core.api.Assertions.catchThrowable(() -> jobs.enqueue(null, "e7.sample", "{}", null));
    assertThat(thrown).isInstanceOf(vn.coreplatform.shared.ApiExceptionHandler.ApiProblem.class)
        .hasMessageContaining("tenant");
    var id = enqueueTracked("e7.sample", "default");
    assertThat(jdbc.queryForObject("select tenant_key from async.job where id=?", String.class, id)).isEqualTo("default");
  }

  @Test @Order(2)
  void s02_heartbeatProtectsLeaseAndDeadWorkerIsReclaimed() {
    drain();
    var id = enqueueTracked("e7.sample", "default");
    var claimed = jobs.claim("worker-A");
    assertThat(claimed).anySatisfy(j -> assertThat(j.id()).isEqualTo(id));

    // owner gia hạn được, worker khác KHÔNG giành được lease còn sống
    assertThat(jobs.heartbeat(id, "worker-A")).isTrue();
    assertThat(jobs.heartbeat(id, "worker-B")).isFalse();
    assertThat(jobs.requeueStale()).as("lease còn hạn không bị thu hồi").isZero();

    // worker chết: hết lease -> reclaim an toàn -> worker khác chạy xong
    jdbc.update("update async.job set lease_until = now() - interval '1 second' where id=?", id);
    assertThat(jobs.requeueStale()).isEqualTo(1);
    drain();
    assertThat(jdbc.queryForObject("select status from async.job where id=?", String.class, id)).isEqualTo("COMPLETED");
  }

  @Test @Order(3)
  void s03_nonRetryableDiesImmediatelyAndRetryableBacksOffThenDies() {
    drain();
    var nonRetryable = enqueueTracked("e7.nonretryable", "default");
    worker.runOnce();
    assertThat(jdbc.queryForObject("select status||'/'||attempts from async.job where id=?", String.class, nonRetryable))
        .as("lỗi không retry được phải DEAD ngay sau 1 lần").isEqualTo("DEAD/1");

    var retryable = enqueueTracked("e7.retryable", "default");
    worker.runOnce();
    assertThat(jdbc.queryForObject("select status from async.job where id=?", String.class, retryable)).isEqualTo("RETRYING");
    var availableAt = jdbc.queryForObject("select available_at > now() from async.job where id=?", Boolean.class, retryable);
    assertThat(availableAt).as("backoff phải đẩy available_at tới tương lai").isTrue();

    for (int i = 0; i < 6; i++) {
      jdbc.update("update async.job set available_at = now() where id=?", retryable);
      worker.runOnce();
    }
    assertThat(jdbc.queryForObject("select status from async.job where id=?", String.class, retryable))
        .as("vượt max-attempts phải vào DEAD (không retry vô hạn)").isEqualTo("DEAD");

    // cancel job chưa chạy
    var cancellable = enqueueTracked("e7.sample", "default");
    jobs.cancel(cancellable);
    assertThat(jdbc.queryForObject("select status from async.job where id=?", String.class, cancellable)).isEqualTo("CANCELLED");
    drain();
    assertThat(jdbc.queryForObject("select status from async.job where id=?", String.class, cancellable))
        .as("CANCELLED không được worker nhặt lại").isEqualTo("CANCELLED");
  }

  @Test @Order(4)
  void s04_twoSchedulersCreateExactlyOneJobInstancePerSlot() throws Exception {
    drain();
    // reset leadership để cả hai đợt tick cùng tranh nhau
    jdbc.update("update async.scheduler_lock set leader_id=null, lease_until=null");

    var admin = adminToken();
    var jobType = "e7.sample"; // loại có handler test — schedule chỉ cần unique theo id
    var schedule = json.readTree(mvc.perform(post("/api/v1/control-plane/job-schedules").with(bearer(admin)).contentType(APPLICATION_JSON)
            .content("{\"tenantKey\":\"default\",\"jobType\":\"%s\",\"intervalSeconds\":3600,\"misfireGraceSeconds\":60,\"payload\":\"{\\\"k\\\":\\\"v\\\"}\"}".formatted(jobType)))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    var scheduleId = UUID.fromString(schedule.get("id").asText());
    var instanceFilter = "select count(*) from async.job where idempotency_key like 'schedule-' || '" + scheduleId + "' || '%'";

    // hai scheduler (hai instance) cùng tick đồng thời
    var executor = Executors.newFixedThreadPool(2);
    try {
      var futures = executor.invokeAll(List.of((Callable<Integer>) scheduler::tick, (Callable<Integer>) scheduler::tick));
      futures.forEach(f -> { try { f.get(30, TimeUnit.SECONDS); } catch (Exception e) { throw new IllegalStateException(e); } });
    } finally { executor.shutdownNow(); }

    assertThat(jdbc.queryForObject(instanceFilter, Integer.class))
        .as("hai scheduler phải tạo đúng MỘT job instance cho slot").isEqualTo(1);

    // tick thêm lần nữa khi slot chưa đến hạn -> vẫn 1
    scheduler.tick();
    assertThat(jdbc.queryForObject(instanceFilter, Integer.class)).isEqualTo(1);
    var lastFired = jdbc.queryForObject("select last_fired_at from async.job_schedule where id=?", java.sql.Timestamp.class, scheduleId);
    assertThat(lastFired).isNotNull();

    // job instance chạy được bằng worker thật
    drain();
    var instanceStatus = jdbc.queryForObject("select status from async.job where idempotency_key like 'schedule-' || ? || '%'", String.class, scheduleId.toString());
    assertThat(instanceStatus).isEqualTo("COMPLETED");
  }

  @Test @Order(5)
  void auditCheckpointJobRunsForItsTenant() {
    drain();
    var id = enqueueTracked("audit.checkpoint", "default");
    drain();
    assertThat(jdbc.queryForObject("select status from async.job where id=?", String.class, id))
        .as("job audit.checkpoint phải hoàn tất với chuỗi audit nguyên vẹn").isEqualTo("COMPLETED");
  }

  /** Handlers test — đăng ký qua @TestConfiguration vì component scan không thấy inner class. */
  @org.springframework.boot.test.context.TestConfiguration
  static class TestHandlers {
    @org.springframework.context.annotation.Bean JobHandler sample() { return new JobHandler() {
      @Override public String jobType() { return "e7.sample"; }
      @Override public void handle(JobContext ctx) {
        if (ctx.tenantKey() == null || ctx.tenantKey().isBlank()) throw new NonRetryableJobException("job thiếu tenant");
        ctx.heartbeat().run();
      }
    }; }
    @org.springframework.context.annotation.Bean JobHandler nonRetryable() { return new JobHandler() {
      @Override public String jobType() { return "e7.nonretryable"; }
      @Override public void handle(JobContext ctx) { throw new NonRetryableJobException("dữ liệu sai - không retry"); }
    }; }
    @org.springframework.context.annotation.Bean JobHandler retryable() { return new JobHandler() {
      @Override public String jobType() { return "e7.retryable"; }
      @Override public void handle(JobContext ctx) { throw new RuntimeException("lỗi tạm thời"); }
    }; }
  }
}
