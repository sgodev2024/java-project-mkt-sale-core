package vn.coreplatform.audit;

import org.springframework.stereotype.Component;
import vn.coreplatform.jobs.JobHandler;

/**
 * Job thật đầu tiên (E7): checkpoint chuỗi hash audit của tenant định kỳ.
 * Tenant lấy từ job context — job không tenant không tồn tại (enqueue fail-closed).
 */
@Component
public class AuditCheckpointHandler implements JobHandler {
  private final AuditService audits;
  public AuditCheckpointHandler(AuditService audits) { this.audits = audits; }

  @Override public String jobType() { return "audit.checkpoint"; }

  @Override public void handle(JobContext context) {
    try {
      var sequence = audits.checkpoint(context.tenantKey());
      context.heartbeat().run();
      org.slf4j.LoggerFactory.getLogger(AuditCheckpointHandler.class)
          .info("Audit checkpoint cho tenant {} tại sequence {}", context.tenantKey(), sequence);
    } catch (vn.coreplatform.shared.ApiExceptionHandler.ApiProblem e) {
      // chuỗi hỏng là lỗi dữ liệu — không nên retry vô hạn
      throw new NonRetryableJobException("audit chain broken: " + e.getMessage(), e);
    }
  }
}
