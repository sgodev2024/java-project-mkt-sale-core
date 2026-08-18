package vn.coreplatform.webhook;

import java.net.InetAddress;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import vn.coreplatform.shared.ApiExceptionHandler.ApiProblem;

/**
 * E11-S03: SSRF guard — webhook chỉ được trỏ tới public hostname, chặn localhost/private IP/link-local/metadata.
 */
@Service
public class WebhookService {
  static final Logger log = LoggerFactory.getLogger(WebhookService.class);
  private final JdbcTemplate jdbc;

  public WebhookService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  public record EndpointItem(UUID id, String url, List<String> eventTypes, String status, Instant lastDeliveryAt, String lastStatus, int failureCount) {}
  public record EndpointCreate(String url, List<String> eventTypes) {}

  private static final Set<String> BLOCKED_HOSTS = Set.of("localhost", "127.0.0.1", "0.0.0.0", "::1", "169.254.169.254", "metadata.google.internal");

  /** Kiểm tra URL trước khi lưu: scheme https, hostname public (không private/loopback/link-local). */
  public void validateUrl(String url) {
    var uri = URI.create(url);
    if (!"https".equals(uri.getScheme()))
      throw new ApiProblem(HttpStatus.BAD_REQUEST, "WEBHOOK_SCHEME", "Webhook URL phải dùng https");
    var host = uri.getHost();
    if (host == null || BLOCKED_HOSTS.contains(host.toLowerCase()))
      throw new ApiProblem(HttpStatus.BAD_REQUEST, "WEBHOOK_HOST_BLOCKED", "Hostname bị chặn: " + host + " (SSRF guard)");
    try {
      var addr = InetAddress.getByName(host);
      if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress() || addr.isAnyLocalAddress())
        throw new ApiProblem(HttpStatus.BAD_REQUEST, "WEBHOOK_HOST_BLOCKED",
            "IP " + addr.getHostAddress() + " là private/loopback — không được dùng làm webhook (SSRF guard)");
    } catch (java.net.UnknownHostException e) {
      throw new ApiProblem(HttpStatus.BAD_REQUEST, "WEBHOOK_HOST_UNRESOLVED", "Không resolve được hostname: " + host);
    }
  }

  public UUID createEndpoint(UUID tenantId, String url, List<String> eventTypes) {
    validateUrl(url);
    var id = UUID.randomUUID();
    var types = eventTypes == null ? new String[0] : eventTypes.toArray(new String[0]);
    jdbc.update("insert into platform.webhook_endpoint(id, tenant_id, url, event_types) values (?,?,?,?::text[])",
        id, tenantId, url, "{" + String.join(",", types) + "}");
    return id;
  }

  public List<EndpointItem> listEndpoints(UUID tenantId) {
    return jdbc.query("""
        select id, url, event_types, status, last_delivery_at, last_status, failure_count
        from platform.webhook_endpoint where tenant_id=? order by created_at desc
        """, (r, n) -> {
      var arr = r.getArray("event_types");
      @SuppressWarnings("unchecked")
      var types = arr == null ? List.<String>of() : Arrays.asList((String[]) arr.getArray());
      return new EndpointItem(r.getObject("id", UUID.class), r.getString("url"), types, r.getString("status"),
          r.getTimestamp("last_delivery_at") == null ? null : r.getTimestamp("last_delivery_at").toInstant(),
          r.getString("last_status"), r.getInt("failure_count"));
    }, tenantId);
  }

  public void disable(UUID tenantId, UUID id) {
    jdbc.update("update platform.webhook_endpoint set status='DISABLED' where id=? and tenant_id=?", id, tenantId);
  }
}
