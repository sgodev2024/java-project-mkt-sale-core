package vn.coreplatform.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/** SPI cho job handler (E7-S01): mọi job thuộc một tenant — handler nhận tenant qua context. */
public interface JobHandler {
  String jobType();
  void handle(JobContext context);

/** Context của một lần chạy job; heartbeat gia hạn lease để worker khác không giành mất. */
  record JobContext(UUID jobId, String workerId, String tenantKey, JsonNode payload, Runnable heartbeat) {}

/** Lỗi không retry được (dữ liệu sai, cấu hình sai...) — vào DEAD ngay, không retry vô hạn. */
  class NonRetryableJobException extends RuntimeException {
    public NonRetryableJobException(String message) { super(message); }
    public NonRetryableJobException(String message, Throwable cause) { super(message, cause); }
  }
}
