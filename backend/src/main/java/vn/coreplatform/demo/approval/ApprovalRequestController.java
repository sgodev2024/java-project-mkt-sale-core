package vn.coreplatform.demo.approval;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import vn.coreplatform.audit.AuditService;
import vn.coreplatform.eventing.OutboxService;
import vn.coreplatform.permission.PermissionService;
import vn.coreplatform.permission.RequirePermission;
import static vn.coreplatform.shared.ApiExceptionHandler.ApiProblem;

/**
 * E10: code-first typed aggregate (ApprovalRequest) — chứng minh Domain Model (Plane 1)
 * hoạt động độc lập với Dynamic Resource (Plane 2), KHÔNG đi qua Generic CRUD.
 * Domain invariants: state machine DRAFT→SUBMITTED→APPROVED/REJECTED, CANCELLED chỉ từ DRAFT/SUBMITTED.
 */
@Profile({"demo", "test"})
@RestController @RequestMapping("/api/v1/approvals")
public class ApprovalRequestController {
  private final JdbcTemplate jdbc;
  private final PermissionService permissions;
  private final AuditService audits;
  private final OutboxService outbox;

  public ApprovalRequestController(JdbcTemplate jdbc, PermissionService permissions, AuditService audits, OutboxService outbox) {
    this.jdbc = jdbc; this.permissions = permissions; this.audits = audits; this.outbox = outbox;
  }

  public record ApprovalRequest(UUID id, String title, String description, String status, String priority,
      BigDecimal amount, UUID requestedBy, UUID decidedBy, Instant decidedAt, String decisionNote,
      JsonNode customAttributes, int version, Instant createdAt, Instant updatedAt) {}
  public record CreateRequest(@NotBlank @Size(max=240) String title, @Size(max=2000) String description,
      @Pattern(regexp="LOW|MEDIUM|HIGH|URGENT") String priority, BigDecimal amount, JsonNode customAttributes) {}
  public record SubmitRequest(String note) {}
  public record DecisionRequest(@NotBlank String note) {}
  public record CancelRequest(String reason) {}
  public record CustomFieldRequest(JsonNode customAttributes) {}

  @RequirePermission(resource = "APPROVAL_REQUEST", action = "READ")
  @GetMapping
  List<ApprovalRequest> list(@RequestParam(defaultValue="") String status, @RequestParam(defaultValue="") String q, Authentication auth) {
    permissions.require(auth, "APPROVAL_REQUEST", "READ", null);
    var tenantId = permissions.tenant(auth);
    var safeStatus = status == null ? "" : status.replaceAll("[^A-Z_]", "");
    String search = "%" + (q == null ? "" : q.toLowerCase(Locale.ROOT)) + "%";
    return jdbc.query("""
        select * from domain.approval_request where tenant_id = ?
          and (? = '' or status = ?) and (? = '' or lower(title) like ? or lower(description) like ?)
        order by updated_at desc limit 100
        """, (r, n) -> map(r), tenantId, safeStatus, safeStatus, q == null ? "" : q, search, search);
  }

  @GetMapping("/{id}")
  ApprovalRequest get(@PathVariable UUID id, Authentication auth) {
    permissions.require(auth, "APPROVAL_REQUEST", "READ", null);
    return jdbc.queryForObject("select * from domain.approval_request where id = ? and tenant_id = ?",
        (r, n) -> map(r), id, permissions.tenant(auth));
  }

  @PostMapping @ResponseStatus(HttpStatus.CREATED) @Transactional
  ApprovalRequest create(@Valid @RequestBody CreateRequest request, Authentication auth) {
    permissions.require(auth, "APPROVAL_REQUEST", "CREATE", permissions.account(auth));
    var id = UUID.randomUUID();
    var tenantId = permissions.tenant(auth);
    jdbc.update("""
        insert into domain.approval_request(id, tenant_id, title, description, priority, amount, requested_by, custom_attributes)
        values (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
        """, id, tenantId, request.title().trim(), request.description() == null ? "" : request.description(),
        request.priority() == null ? "MEDIUM" : request.priority(), request.amount(), permissions.account(auth),
        sanitizeCustom(request.customAttributes()));
    audits.record(permissions.tenantKey(auth), permissions.account(auth), auth.getName(), "APPROVAL_CREATED", "APPROVAL_REQUEST", id.toString(), "SUCCESS", null);
    outbox.publish(permissions.tenantKey(auth), "approval-request.created.v1", "approval-request", id.toString(),
        payload(id, request.title(), "DRAFT"));
    return get(id, auth);
  }

  /** E10-S02: domain invariant — chỉ DRAFT mới submit được, và phải có title. */
  @PostMapping("/{id}/submit") @Transactional
  ApprovalRequest submit(@PathVariable UUID id, @RequestBody(required=false) SubmitRequest request, Authentication auth) {
    permissions.require(auth, "APPROVAL_REQUEST", "UPDATE", requesterOf(id, auth));
    var current = get(id, auth);
    if (!"DRAFT".equals(current.status()))
      throw new ApiProblem(HttpStatus.CONFLICT, "INVALID_TRANSITION",
          "Chỉ DRAFT mới submit được — trạng thái hiện tại: " + current.status());
    jdbc.update("update domain.approval_request set status='SUBMITTED', version=version+1, updated_at=now() where id=? and version=?", id, current.version());
    audits.record(permissions.tenantKey(auth), permissions.account(auth), auth.getName(), "APPROVAL_SUBMITTED", "APPROVAL_REQUEST", id.toString(), "SUCCESS", null);
    outbox.publish(permissions.tenantKey(auth), "approval-request.submitted.v1", "approval-request", id.toString(),
        payload(id, current.title(), "SUBMITTED"));
    return get(id, auth);
  }

  @PostMapping("/{id}/approve") @Transactional
  ApprovalRequest approve(@PathVariable UUID id, @Valid @RequestBody DecisionRequest request, Authentication auth) {
    permissions.require(auth, "APPROVAL_REQUEST", "DECIDE", null);
    var current = get(id, auth);
    if (!"SUBMITTED".equals(current.status()))
      throw new ApiProblem(HttpStatus.CONFLICT, "INVALID_TRANSITION",
          "Chỉ SUBMITTED mới approve được — trạng thái hiện tại: " + current.status());
    jdbc.update("update domain.approval_request set status='APPROVED', decided_by=?, decided_at=now(), decision_note=?, version=version+1, updated_at=now() where id=?",
        permissions.account(auth), request.note(), id);
    audits.record(permissions.tenantKey(auth), permissions.account(auth), auth.getName(), "APPROVAL_APPROVED", "APPROVAL_REQUEST", id.toString(), "SUCCESS", "{\"note\":\"" + request.note().replace("\"","'") + "\"}");
    outbox.publish(permissions.tenantKey(auth), "approval-request.approved.v1", "approval-request", id.toString(),
        payload(id, current.title(), "APPROVED"));
    return get(id, auth);
  }

  @PostMapping("/{id}/reject") @Transactional
  ApprovalRequest reject(@PathVariable UUID id, @Valid @RequestBody DecisionRequest request, Authentication auth) {
    permissions.require(auth, "APPROVAL_REQUEST", "DECIDE", null);
    var current = get(id, auth);
    if (!"SUBMITTED".equals(current.status()))
      throw new ApiProblem(HttpStatus.CONFLICT, "INVALID_TRANSITION",
          "Chỉ SUBMITTED mới reject được — trạng thái hiện tại: " + current.status());
    jdbc.update("update domain.approval_request set status='REJECTED', decided_by=?, decided_at=now(), decision_note=?, version=version+1, updated_at=now() where id=?",
        permissions.account(auth), request.note(), id);
    audits.record(permissions.tenantKey(auth), permissions.account(auth), auth.getName(), "APPROVAL_REJECTED", "APPROVAL_REQUEST", id.toString(), "SUCCESS", "{\"note\":\"" + request.note().replace("\"","'") + "\"}");
    outbox.publish(permissions.tenantKey(auth), "approval-request.rejected.v1", "approval-request", id.toString(),
        payload(id, current.title(), "REJECTED"));
    return get(id, auth);
  }

  @PostMapping("/{id}/cancel") @Transactional
  ApprovalRequest cancel(@PathVariable UUID id, @RequestBody(required=false) CancelRequest request, Authentication auth) {
    permissions.require(auth, "APPROVAL_REQUEST", "DELETE", requesterOf(id, auth));
    var current = get(id, auth);
    if (!"DRAFT".equals(current.status()) && !"SUBMITTED".equals(current.status()))
      throw new ApiProblem(HttpStatus.CONFLICT, "INVALID_TRANSITION",
          "Chỉ DRAFT/SUBMITTED mới cancel được — đã " + current.status());
    jdbc.update("update domain.approval_request set status='CANCELLED', decision_note=?, version=version+1, updated_at=now() where id=?",
        request == null ? null : request.reason(), id);
    audits.record(permissions.tenantKey(auth), permissions.account(auth), auth.getName(), "APPROVAL_CANCELLED", "APPROVAL_REQUEST", id.toString(), "SUCCESS", null);
    outbox.publish(permissions.tenantKey(auth), "approval-request.cancelled.v1", "approval-request", id.toString(),
        payload(id, current.title(), "CANCELLED"));
    return get(id, auth);
  }

  /** E10-S03: custom field trên code-first aggregate — typed fields không bị đè. */
  @PutMapping("/{id}/custom-fields") @Transactional
  ApprovalRequest updateCustomFields(@PathVariable UUID id, @RequestBody CustomFieldRequest request, Authentication auth) {
    permissions.require(auth, "APPROVAL_REQUEST", "UPDATE", requesterOf(id, auth));
    var current = get(id, auth);
    if ("CANCELLED".equals(current.status()) || "APPROVED".equals(current.status()) || "REJECTED".equals(current.status()))
      throw new ApiProblem(HttpStatus.CONFLICT, "NOT_EDITABLE", "Approval đã kết thúc — không sửa custom fields được");
    jdbc.update("update domain.approval_request set custom_attributes=?::jsonb, version=version+1, updated_at=now() where id=?",
        sanitizeCustom(request.customAttributes()), id);
    audits.record(permissions.tenantKey(auth), permissions.account(auth), auth.getName(), "APPROVAL_CUSTOM_FIELDS_UPDATED", "APPROVAL_REQUEST", id.toString(), "SUCCESS", null);
    return get(id, auth);
  }

  private UUID requesterOf(UUID id, Authentication auth) {
    return jdbc.queryForObject("select requested_by from domain.approval_request where id=? and tenant_id=?", UUID.class, id, permissions.tenant(auth));
  }
  private com.fasterxml.jackson.databind.node.ObjectNode payload(UUID id, String title, String status) {
    return new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
        .put("approvalId", id.toString()).put("title", title).put("status", status);
  }
  /** Typed fields (title, description, status, priority, amount, ...) không bao giờ nằm trong custom. */
  static String sanitizeCustom(JsonNode custom) {
    if (custom == null || !custom.isObject() || custom.isEmpty()) return "{}";
    var typed = java.util.Set.of("id", "title", "description", "status", "priority", "amount", "version", "requestedby", "decidedby", "decidedat", "decisionnote", "createdat", "updatedat");
    var out = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
    custom.fields().forEachRemaining(e -> {
      var k = e.getKey().toLowerCase(Locale.ROOT).replace("_", "");
      if (!typed.contains(k) && k.matches("[a-z][a-z0-9_]{1,79}")) out.set(e.getKey(), e.getValue());
    });
    return out.toString();
  }
  private ApprovalRequest map(java.sql.ResultSet r) throws java.sql.SQLException {
    return new ApprovalRequest(r.getObject("id", UUID.class), r.getString("title"), r.getString("description"),
        r.getString("status"), r.getString("priority"), r.getBigDecimal("amount"), r.getObject("requested_by", UUID.class),
        r.getObject("decided_by", UUID.class), r.getTimestamp("decided_at") == null ? null : r.getTimestamp("decided_at").toInstant(),
        r.getString("decision_note"), readJson(r.getString("custom_attributes")), r.getInt("version"),
        r.getTimestamp("created_at").toInstant(), r.getTimestamp("updated_at").toInstant());
  }
  private JsonNode readJson(String value) { try { return new com.fasterxml.jackson.databind.ObjectMapper().readTree(value); } catch (Exception e) { return null; } }
}
