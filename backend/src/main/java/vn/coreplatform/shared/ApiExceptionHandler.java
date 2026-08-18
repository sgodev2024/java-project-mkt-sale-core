package vn.coreplatform.shared;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger; import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiExceptionHandler {
  static final Logger log=LoggerFactory.getLogger(ApiExceptionHandler.class);
  @ExceptionHandler(ApiProblem.class)
  ResponseEntity<ProblemDetail> handle(ApiProblem error, HttpServletRequest request) {
    var problem = ProblemDetail.forStatusAndDetail(error.status, error.getMessage());
    problem.setTitle(error.code); problem.setType(URI.create("https://core.local/problems/" + error.code));
    problem.setProperty("code", error.code); problem.setProperty("timestamp", Instant.now());
    problem.setProperty("path", request.getRequestURI()); problem.setProperty("correlationId", CorrelationIdFilter.current());
    return ResponseEntity.status(error.status).body(problem);
  }
  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException error) {
    var p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Dữ liệu đầu vào không hợp lệ"); p.setTitle("VALIDATION_FAILED");
    p.setProperty("errors", error.getBindingResult().getFieldErrors().stream().map(e -> Map.of("field", e.getField(), "message", e.getDefaultMessage())).toList());
    p.setProperty("correlationId", CorrelationIdFilter.current());
    return ResponseEntity.badRequest().body(p);
  }
  @ExceptionHandler(Exception.class)
  ResponseEntity<ProblemDetail> unexpected(Exception error, HttpServletRequest request) {
    log.error("Unhandled error correlationId={} uri={} type={}", CorrelationIdFilter.current(), request.getRequestURI(), error.getClass().getName(), error);
    var p = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Lỗi hệ thống, vui lòng thử lại sau"); p.setTitle("INTERNAL_ERROR");
    p.setType(URI.create("https://core.local/problems/INTERNAL_ERROR"));
    p.setProperty("code", "INTERNAL_ERROR"); p.setProperty("timestamp", Instant.now());
    p.setProperty("path", request.getRequestURI()); p.setProperty("correlationId", CorrelationIdFilter.current());
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(p);
  }
  public static class ApiProblem extends RuntimeException {
    final HttpStatus status; final String code;
    public ApiProblem(HttpStatus status, String code, String message) { super(message); this.status=status; this.code=code; }
  }
}
