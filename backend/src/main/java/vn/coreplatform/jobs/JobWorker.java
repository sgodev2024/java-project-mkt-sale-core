package vn.coreplatform.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Worker (E7-S02): claim batch theo lease, chạy handler, tự requeue job của worker chết.
 * Tắt trong test (core.jobs.enabled mặc định false) — test gọi runOnce() trực tiếp.
 */
@Component
@ConditionalOnProperty(name = "core.jobs.enabled", havingValue = "true")
public class JobWorker {
  static final Logger log = LoggerFactory.getLogger(JobWorker.class);
  static final ObjectMapper MAPPER = new ObjectMapper();
  private final JobService jobs;
  private final List<JobHandler> handlers;
  private final String workerId;

  public JobWorker(JobService jobs, List<JobHandler> handlers) {
    this.jobs = jobs; this.handlers = handlers;
    String host;
    try { host = java.net.InetAddress.getLocalHost().getHostName(); } catch (Exception e) { host = "localhost"; }
    this.workerId = host + "-worker-" + UUID.randomUUID().toString().substring(0, 8);
  }

  @Scheduled(fixedDelayString = "${core.jobs.poll-interval-ms:5000}", initialDelayString = "${core.jobs.initial-delay-ms:4000}")
  public void workLoop() { runOnce(); }

  public int runOnce() {
    var claimed = jobs.claim(workerId);
    for (var job : claimed) {
      var handler = handlers.stream().filter(h -> h.jobType().equals(job.jobType())).findFirst();
      if (handler.isEmpty()) {
        jobs.fail(job.id(), "no handler registered for " + job.jobType(), false);
        continue;
      }
      try {
        var context = new JobHandler.JobContext(job.id(), workerId, job.tenantKey(),
            MAPPER.readTree(job.payload() == null ? "{}" : job.payload()),
            () -> jobs.heartbeat(job.id(), workerId));
        handler.get().handle(context);
        jobs.complete(job.id());
      } catch (JobHandler.NonRetryableJobException e) {
        log.warn("Job {} failed (non-retryable): {}", job.id(), e.getMessage());
        jobs.fail(job.id(), e.getMessage(), false);
      } catch (Exception e) {
        log.warn("Job {} failed (retryable): {}", job.id(), e.getMessage());
        jobs.fail(job.id(), e.getMessage(), true);
      }
    }
    return claimed.size();
  }
}
