package vn.coreplatform.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import vn.coreplatform.shared.ApiExceptionHandler.ApiProblem;
import vn.coreplatform.shared.CorrelationIdFilter;

/**
 * Audit trung tâm (E5). Mọi event nghiệp vụ ghi qua record() trong CÙNG transaction của thao
 * tác gốc — audit fail thì thao tác rollback (E5-S01); payload được mask secret trước khi lưu
 * (E5-S02); event nối thành chuỗi hash per-tenant: payload_hash = sha256(canonical),
 * prev_hash = hash của event trước (E5-S03); retention chỉ xóa batch đã checkpoint và tôn trọng
 * legal hold, thực thi bằng hàm SECURITY DEFINER trong database (E5-S04).
 */
@Service
public class AuditService {
  static final Logger log = LoggerFactory.getLogger(AuditService.class);
  static final String GENESIS = "0".repeat(64);
  static final String SYSTEM_TENANT = "__system__";
  static final Set<String> SENSITIVE_KEYS = Set.of("password", "token", "accesstoken", "refreshtoken", "apikey", "secret", "authorization", "code", "apikeyhash");
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;

  public AuditService(JdbcTemplate jdbc, TransactionTemplate tx) { this.jdbc = jdbc; this.tx = tx; }

  public void record(String tenantKey, UUID actorId, String actorEmail, String action, String resourceType, String resourceId, String result, String detailsJson) {
    var tenant = tenantKey == null || tenantKey.isBlank() ? SYSTEM_TENANT : tenantKey;
    var maskedDetails = canonicalDetails(mask(detailsJson));
    var occurredAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    tx.executeWithoutResult(status -> {
      jdbc.update("insert into audit.chain_state(tenant_key) values(?) on conflict (tenant_key) do nothing", tenant);
      var state = jdbc.queryForList("select last_sequence, last_hash from audit.chain_state where tenant_key=? for update", tenant).getFirst();
      long sequence = ((Number) state.get("last_sequence")).longValue() + 1;
      String prevHash = String.valueOf(state.get("last_hash"));
      var id = UUID.randomUUID();
      var correlationId = CorrelationIdFilter.current();
      var canonical = canonical(id, tenant, actorEmail, action, resourceType, resourceId, result, correlationId, occurredAt, maskedDetails);
      var payloadHash = sha256(canonical);
      jdbc.update("""
          insert into audit.event(id, actor_id, actor_email, tenant_key, action, resource_type, resource_id, result, correlation_id, details, occurred_at, sequence_no, payload_hash, prev_hash)
          values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
          """, id, actorId, actorEmail, tenant, action, resourceType, resourceId, result, correlationId, maskedDetails, Timestamp.from(occurredAt), sequence, payloadHash, prevHash);
      jdbc.update("update audit.chain_state set last_sequence=?, last_hash=?, updated_at=now() where tenant_key=?", sequence, payloadHash, tenant);
    });
  }

  public record Verification(boolean verified, long checked, Long brokenAtSequence, String reason) {}

  /** E5-S03: dò lại chuỗi từ checkpoint gần nhất; phát hiện sửa nội dung, xóa dòng, và đứt sequence. */
  public Verification verify(String tenantKey) {
    var tenant = tenantKey == null || tenantKey.isBlank() ? SYSTEM_TENANT : tenantKey;
    String expectedPrev = GENESIS;
    long expectedSequence = 1;
    var checkpoint = jdbc.queryForList("select verified_sequence, chain_hash from audit.checkpoint where tenant_key=?", tenant);
    if (!checkpoint.isEmpty()) {
      expectedSequence = ((Number) checkpoint.getFirst().get("verified_sequence")).longValue() + 1;
      expectedPrev = String.valueOf(checkpoint.getFirst().get("chain_hash"));
    }
    var rows = jdbc.query("""
        select id, actor_email, action, resource_type, resource_id, result, correlation_id, occurred_at, details, sequence_no, payload_hash, prev_hash
        from audit.event where tenant_key=? and sequence_no is not null and sequence_no >= ? order by sequence_no
        """, (rs, n) -> new Object[]{
            rs.getObject("id", UUID.class), rs.getString("actor_email"), rs.getString("action"), rs.getString("resource_type"),
            rs.getString("resource_id"), rs.getString("result"), rs.getObject("correlation_id", UUID.class),
            rs.getTimestamp("occurred_at").toInstant(), rs.getString("details"), rs.getLong("sequence_no"),
            rs.getString("payload_hash"), rs.getString("prev_hash")}, tenant, expectedSequence);
    long previous = expectedSequence - 1;
    for (var row : rows) {
      long sequence = (Long) row[9];
      if (sequence != previous + 1) return new Verification(false, rows.size(), sequence, "SEQUENCE_GAP: kỳ vọng " + (previous + 1) + " nhưng gặp " + sequence);
      if (!String.valueOf(row[11]).equals(expectedPrev)) return new Verification(false, rows.size(), sequence, "BROKEN_LINK: prev_hash không nối với event trước (hoặc checkpoint)");
      // jsonb của PG tự serialize lại (thêm space...) nên phải qua lại cùng canonicalizer trước khi băm
      var recomputed = sha256(canonical((UUID) row[0], tenant, (String) row[1], (String) row[2], (String) row[3], (String) row[4], (String) row[5], (UUID) row[6], ((Instant) row[7]).truncatedTo(ChronoUnit.MICROS), canonicalDetails((String) row[8])));
      if (!recomputed.equals(String.valueOf(row[10]))) return new Verification(false, rows.size(), sequence, "TAMPERED: payload_hash không khớp nội dung (đã bị sửa?)");
      expectedPrev = recomputed;
      previous = sequence;
    }
    return new Verification(true, rows.size(), null, "OK");
  }

  /** E5-S04: checkpoint chỉ thành công khi chuỗi verify sạch; lưu mốc + hash cuối. */
  public long checkpoint(String tenantKey) {
    var tenant = tenantKey == null || tenantKey.isBlank() ? SYSTEM_TENANT : tenantKey;
    var verification = verify(tenant);
    if (!verification.verified()) throw new ApiProblem(HttpStatus.CONFLICT, "AUDIT_CHAIN_BROKEN", "Không checkpoint được: chuỗi audit hỏng ở sequence " + verification.brokenAtSequence() + " — " + verification.reason());
    var state = jdbc.queryForList("select last_sequence, last_hash from audit.chain_state where tenant_key=?", tenant);
    if (state.isEmpty()) return 0;
    var sequence = ((Number) state.getFirst().get("last_sequence")).longValue();
    var hash = String.valueOf(state.getFirst().get("last_hash"));
    jdbc.update("insert into audit.checkpoint(tenant_key, verified_sequence, chain_hash) values(?,?,?) on conflict (tenant_key) do update set verified_sequence=excluded.verified_sequence, chain_hash=excluded.chain_hash, created_at=now()", tenant, sequence, hash);
    return sequence;
  }

  public long purge(String tenantKey, int olderThanDays) {
    var tenant = tenantKey == null || tenantKey.isBlank() ? SYSTEM_TENANT : tenantKey;
    try {
      Long deleted = jdbc.queryForObject("select audit.purge_old(?, ?::timestamptz)", Long.class, tenant, Timestamp.from(Instant.now().minus(olderThanDays, ChronoUnit.DAYS)));
      return deleted == null ? 0 : deleted;
    } catch (Exception e) {
      if (String.valueOf(e.getMessage()).contains("LEGAL_HOLD_ACTIVE") || String.valueOf(e.getCause()).contains("LEGAL_HOLD_ACTIVE"))
        throw new ApiProblem(HttpStatus.CONFLICT, "LEGAL_HOLD_ACTIVE", "Tenant đang trong thời hạn giữ dữ liệu, không được purge");
      throw e;
    }
  }

  public void setLegalHold(String tenantKey, String reason, String heldBy) {
    jdbc.update("insert into audit.legal_hold(tenant_key, reason, held_by) values(?,?,?) on conflict (tenant_key) do update set reason=excluded.reason, held_by=excluded.held_by", tenantKey, reason, heldBy);
  }

  public void releaseLegalHold(String tenantKey) {
    jdbc.update("delete from audit.legal_hold where tenant_key=?", tenantKey);
  }

  // ---- canonical + masking ----

  static String canonical(UUID id, String tenant, String actorEmail, String action, String resourceType, String resourceId, String result, UUID correlationId, Instant occurredAt, String canonicalDetails) {
    var joiner = new StringJoiner("|");
    for (var value : new Object[]{id, tenant, actorEmail, action, resourceType, resourceId, result, correlationId, occurredAt, canonicalDetails})
      joiner.add(value == null ? "" : value.toString());
    return joiner.toString();
  }

  static String canonicalDetails(String detailsJson) {
    if (detailsJson == null || detailsJson.isBlank()) return "{}";
    try {
      var sorted = MAPPER.convertValue(MAPPER.readTree(detailsJson), java.util.TreeMap.class);
      return MAPPER.writeValueAsString(sorted);
    } catch (Exception e) {
      return "{\"raw\":\"" + detailsJson.replace("\"", "'") + "\"}";
    }
  }

  /** E5-S02: che mọi trường nhạy cảm trước khi đưa vào audit. */
  static String mask(String detailsJson) {
    if (detailsJson == null || detailsJson.isBlank()) return null;
    try {
      return MAPPER.writeValueAsString(maskNode(MAPPER.readTree(detailsJson)));
    } catch (Exception e) {
      return null;
    }
  }

  private static JsonNode maskNode(JsonNode node) {
    if (node.isObject()) {
      var output = MAPPER.createObjectNode();
      node.fields().forEachRemaining(field -> {
        var key = field.getKey().toLowerCase(Locale.ROOT).replace("_", "");
        output.set(field.getKey(), SENSITIVE_KEYS.contains(key) ? MAPPER.getNodeFactory().textNode("***") : maskNode(field.getValue()));
      });
      return output;
    }
    if (node.isArray()) {
      var output = MAPPER.createArrayNode();
      node.forEach(child -> output.add(maskNode(child)));
      return output;
    }
    return node;
  }

  static String sha256(String value) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) { throw new IllegalStateException(e); }
  }
}
