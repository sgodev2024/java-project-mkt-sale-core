package vn.sgodata.revenueintelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.coreplatform.audit.AuditService;
import vn.coreplatform.eventing.OutboxService;
import vn.coreplatform.shared.ApiExceptionHandler.ApiProblem;

@Service
public class RevenueImportService {
  enum Dataset {
    CUSTOMERS("customers"), ORDERS("orders"), AD_SPEND("ad-spend"), TOUCHPOINTS("touchpoints");
    final String path;
    Dataset(String path) { this.path = path; }
    static Dataset fromPath(String value) {
      for (var candidate : values()) if (candidate.path.equalsIgnoreCase(value)) return candidate;
      throw new ApiProblem(HttpStatus.BAD_REQUEST, "DATASET_UNSUPPORTED", "Dataset không được hỗ trợ: " + value);
    }
  }

  public record ImportResult(UUID batchId, String dataset, String status, int totalRows, int acceptedRows, int rejectedRows, boolean duplicate) {}
  public record ImportBatch(UUID id, String dataset, String sourceName, String status, int totalRows, int acceptedRows, int rejectedRows, java.time.Instant startedAt, java.time.Instant finishedAt) {}
  public record ImportError(int rowNumber, String code, String message, Map<String, Object> rawPayload) {}

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final AuditService audits;
  private final OutboxService outbox;
  private final CsvTableParser parser = new CsvTableParser();

  public RevenueImportService(JdbcTemplate jdbc, ObjectMapper json, AuditService audits, OutboxService outbox) {
    this.jdbc = jdbc; this.json = json; this.audits = audits; this.outbox = outbox;
  }

  @Transactional
  public ImportResult importCsv(Dataset dataset, String sourceName, byte[] content, UUID tenantId, UUID actorId, String actorEmail, String tenantKey) {
    final CsvTableParser.Table table;
    try { table = parser.parse(content); }
    catch (IllegalArgumentException invalid) {
      throw new ApiProblem(HttpStatus.BAD_REQUEST, "CSV_INVALID", "CSV không hợp lệ: " + invalid.getMessage());
    }
    var checksum = sha256(content);
    var existing = jdbc.query("""
        select id,status,total_rows,accepted_rows,rejected_rows from revenue_intelligence.import_batch
        where tenant_id=? and dataset_type=? and checksum_sha256=?
        """, (r, n) -> new ImportResult(r.getObject(1, UUID.class), dataset.name(), r.getString(2), r.getInt(3), r.getInt(4), r.getInt(5), true),
        tenantId, dataset.name(), checksum);
    if (!existing.isEmpty()) return existing.getFirst();

    UUID batchId = jdbc.queryForObject("""
        insert into revenue_intelligence.import_batch(tenant_id,dataset_type,source_name,checksum_sha256,created_by)
        values(?,?,?,?,?) returning id
        """, UUID.class, tenantId, dataset.name(), safeFileName(sourceName), checksum, actorId);
    int accepted = 0, rejected = 0;
    for (var row : table.rows()) {
      try {
        switch (dataset) {
          case CUSTOMERS -> importCustomer(tenantId, row.values());
          case ORDERS -> importOrder(tenantId, batchId, actorEmail, row.values());
          case AD_SPEND -> importSpend(tenantId, batchId, row.values());
          case TOUCHPOINTS -> importTouchpoint(tenantId, batchId, row.values());
        }
        accepted++;
      } catch (IllegalArgumentException invalidRow) {
        rejected++;
        recordError(tenantId, batchId, row, invalidRow);
      }
    }
    String status = rejected == 0 ? "COMPLETED" : "COMPLETED_WITH_ERRORS";
    jdbc.update("""
        update revenue_intelligence.import_batch set status=?,total_rows=?,accepted_rows=?,rejected_rows=?,finished_at=now()
        where id=? and tenant_id=?
        """, status, accepted + rejected, accepted, rejected, batchId, tenantId);
    var details = json.createObjectNode().put("dataset", dataset.name()).put("accepted", accepted).put("rejected", rejected).toString();
    audits.record(tenantKey, actorId, actorEmail, "REVENUE_IMPORT_COMPLETED", "REVENUE_IMPORT", batchId.toString(), status, details);
    outbox.publish(tenantKey, "revenue-import.completed.v1", "revenue-import", batchId.toString(),
        json.createObjectNode().put("batchId", batchId.toString()).put("dataset", dataset.name()).put("accepted", accepted).put("rejected", rejected));
    return new ImportResult(batchId, dataset.name(), status, accepted + rejected, accepted, rejected, false);
  }

  public List<ImportBatch> batches(UUID tenantId) {
    return jdbc.query("""
        select id,dataset_type,source_name,status,total_rows,accepted_rows,rejected_rows,started_at,finished_at
        from revenue_intelligence.import_batch where tenant_id=? order by started_at desc limit 50
        """, (r, n) -> new ImportBatch(r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getString(4),
        r.getInt(5), r.getInt(6), r.getInt(7), r.getTimestamp(8).toInstant(), r.getTimestamp(9) == null ? null : r.getTimestamp(9).toInstant()), tenantId);
  }

  public List<ImportError> errors(UUID tenantId, UUID batchId) {
    return jdbc.query("""
        select row_number,error_code,message,raw_payload::text from revenue_intelligence.import_error
        where tenant_id=? and batch_id=? order by row_number limit 1000
        """, (r, n) -> new ImportError(r.getInt(1), r.getString(2), r.getString(3), readMap(r.getString(4))), tenantId, batchId);
  }

  private void importCustomer(UUID tenantId, Map<String, String> row) {
    String source = upper(required(row, "source_system"));
    String externalId = required(row, "external_id", "external_customer_id");
    String name = optional(row, "full_name", "display_name");
    String email = normalizeEmail(optional(row, "email"));
    String phone = normalizePhone(optional(row, "phone"));
    String emailHash = email.isBlank() ? null : sha256(email.getBytes(StandardCharsets.UTF_8));
    String phoneHash = phone.isBlank() ? null : sha256(phone.getBytes(StandardCharsets.UTF_8));
    boolean historyComplete = bool(optional(row, "history_complete"));

    UUID linked = findCustomerBySource(tenantId, source, externalId);
    UUID byEmail = findCustomerByIdentity(tenantId, "EMAIL", emailHash);
    UUID byPhone = findCustomerByIdentity(tenantId, "PHONE", phoneHash);
    if (byEmail != null && byPhone != null && !byEmail.equals(byPhone)) throw invalid("IDENTITY_CONFLICT", "Email và số điện thoại thuộc hai khách hàng khác nhau");
    UUID identityMatch = byEmail != null ? byEmail : byPhone;
    if (linked != null && identityMatch != null && !linked.equals(identityMatch)) throw invalid("IDENTITY_CONFLICT", "Mã nguồn và định danh thuộc hai khách hàng khác nhau");
    UUID customerId = linked != null ? linked : identityMatch;
    if (customerId == null) {
      customerId = UUID.randomUUID();
      jdbc.update("""
          insert into revenue_intelligence.customer(id,tenant_id,source_system,external_id,full_name,email_hash,email_masked,phone_hash,phone_masked,history_complete)
          values(?,?,?,?,?,?,?,?,?,?)
          """, customerId, tenantId, source, externalId, name, emailHash, maskEmail(email), phoneHash, maskPhone(phone), historyComplete);
    } else {
      jdbc.update("""
          update revenue_intelligence.customer set full_name=case when ?='' then full_name else ? end,
            email_hash=coalesce(?,email_hash),email_masked=coalesce(?,email_masked),
            phone_hash=coalesce(?,phone_hash),phone_masked=coalesce(?,phone_masked),
            history_complete=history_complete or ?,updated_at=now()
          where id=? and tenant_id=?
          """, name, name, emailHash, emailHash == null ? null : maskEmail(email), phoneHash, phoneHash == null ? null : maskPhone(phone), historyComplete, customerId, tenantId);
    }
    jdbc.update("""
        insert into revenue_intelligence.customer_source_link(tenant_id,customer_id,source_system,external_id)
        values(?,?,?,?) on conflict(tenant_id,source_system,external_id) do nothing
        """, tenantId, customerId, source, externalId);
    addIdentity(tenantId, customerId, source, "EMAIL", emailHash);
    addIdentity(tenantId, customerId, source, "PHONE", phoneHash);
    jdbc.update("""
        update revenue_intelligence.sales_order set customer_id=?,updated_at=now()
        where tenant_id=? and customer_id is null and customer_external_id=? and (customer_source is null or customer_source=? )
        """, customerId, tenantId, externalId, source);
    recalculateLifecycle(tenantId, customerId);
  }

  private void importOrder(UUID tenantId, UUID batchId, String actorEmail, Map<String, String> row) {
    String source = upper(required(row, "source_system"));
    String externalId = required(row, "external_id", "external_order_id");
    String customerExternal = optional(row, "customer_external_id", "external_customer_id");
    String customerSource = upper(optional(row, "customer_source"));
    UUID customerId = customerExternal.isBlank() ? null : findCustomer(tenantId, customerSource, customerExternal);
    var orderedAt = timestamp(required(row, "ordered_at"));
    var gross = money(required(row, "gross_amount", "gross_revenue"), "gross_amount");
    var discount = money(optional(row, "discount_amount"), "discount_amount");
    var returned = money(optional(row, "returned_amount", "refund_amount"), "returned_amount");
    String rawStatus = upper(optional(row, "status"));
    String status = normalizeStatus(rawStatus, returned);
    var cancelled = money(optional(row, "cancelled_amount"), "cancelled_amount");
    if ("CANCELLED".equals(status) && cancelled.signum() == 0) cancelled = gross.subtract(discount).subtract(returned).max(BigDecimal.ZERO);
    var shipping = money(optional(row, "shipping_amount"), "shipping_amount");
    var tax = money(optional(row, "tax_amount"), "tax_amount");
    if (gross.subtract(discount).subtract(returned).subtract(cancelled).signum() < 0)
      throw invalid("NEGATIVE_NET_REVENUE", "Tổng giảm trừ lớn hơn doanh thu gộp");
    String sourceChannel = channelCode(optional(row, "source_channel", "order_channel"));
    String businessModel = classifyBusinessModel(optional(row, "business_model"), source, optional(row, "order_channel"));
    boolean historyComplete = customerId != null && Boolean.TRUE.equals(jdbc.queryForObject(
        "select history_complete from revenue_intelligence.customer where id=? and tenant_id=?", Boolean.class, customerId, tenantId));
    int priorOrders = customerId == null ? 0 : jdbc.queryForObject("""
        select count(*) from revenue_intelligence.sales_order where tenant_id=? and customer_id=? and status<>'CANCELLED' and ordered_at<?
        """, Integer.class, tenantId, customerId, orderedAt);
    String lifecycle = classifyLifecycle(priorOrders, historyComplete);
    UUID orderId = jdbc.queryForObject("""
        insert into revenue_intelligence.sales_order(tenant_id,source_system,external_id,customer_id,customer_source,customer_external_id,ordered_at,
          gross_amount,discount_amount,returned_amount,cancelled_amount,shipping_amount,tax_amount,source_channel,business_model,customer_lifecycle,status,import_batch_id)
        values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        on conflict(tenant_id,source_system,external_id) do update set customer_id=excluded.customer_id,customer_source=excluded.customer_source,
          customer_external_id=excluded.customer_external_id,ordered_at=excluded.ordered_at,gross_amount=excluded.gross_amount,
          discount_amount=excluded.discount_amount,returned_amount=excluded.returned_amount,cancelled_amount=excluded.cancelled_amount,
          shipping_amount=excluded.shipping_amount,tax_amount=excluded.tax_amount,source_channel=excluded.source_channel,
          business_model=excluded.business_model,customer_lifecycle=excluded.customer_lifecycle,status=excluded.status,
          import_batch_id=excluded.import_batch_id,updated_at=now()
        returning id
        """, UUID.class, tenantId, source, externalId, customerId, blankToNull(customerSource), blankToNull(customerExternal), orderedAt,
        gross, discount, returned, cancelled, shipping, tax, blankToNull(sourceChannel), businessModel, lifecycle, status, batchId);
    writeOrderRevision(tenantId, orderId, actorEmail);
    if (customerId != null) recalculateLifecycle(tenantId, customerId);
  }

  private void importSpend(UUID tenantId, UUID batchId, Map<String, String> row) {
    String source = upper(required(row, "source_system"));
    LocalDate date;
    try { date = LocalDate.parse(required(row, "spend_date")); }
    catch (Exception e) { throw invalid("DATE_INVALID", "spend_date phải theo ISO yyyy-MM-dd"); }
    String channel = channelCode(required(row, "channel"));
    UUID channelId = ensureChannel(tenantId, channel);
    String campaignExternal = optional(row, "campaign_external_id", "campaign");
    UUID campaignId = ensureCampaign(tenantId, channelId, campaignExternal, optional(row, "campaign_name"));
    String externalId = optional(row, "external_id");
    if (externalId.isBlank()) externalId = source + "|" + date + "|" + channel + "|" + campaignExternal;
    var amount = money(required(row, "amount"), "amount");
    String currency = upper(required(row, "currency"));
    if (!currency.matches("[A-Z]{3}")) throw invalid("CURRENCY_INVALID", "currency phải là mã ISO 4217 gồm 3 ký tự");
    jdbc.update("""
        insert into revenue_intelligence.ad_spend(tenant_id,source_system,external_id,spend_date,channel_id,campaign_id,amount,currency,import_batch_id)
        values(?,?,?,?,?,?,?,?,?)
        on conflict(tenant_id,source_system,external_id) do update set spend_date=excluded.spend_date,channel_id=excluded.channel_id,
          campaign_id=excluded.campaign_id,amount=excluded.amount,currency=excluded.currency,import_batch_id=excluded.import_batch_id
        """, tenantId, source, externalId, date, channelId, campaignId, amount, currency, batchId);
  }

  private void importTouchpoint(UUID tenantId, UUID batchId, Map<String, String> row) {
    String source = upper(required(row, "source_system"));
    String externalId = required(row, "external_id", "external_touchpoint_id");
    String customerExternal = optional(row, "customer_external_id", "external_customer_id");
    String customerSource = upper(optional(row, "customer_source"));
    UUID customerId = customerExternal.isBlank() ? null : findCustomer(tenantId, customerSource, customerExternal);
    var occurredAt = timestamp(required(row, "occurred_at"));
    String channel = channelCode(required(row, "channel"));
    UUID channelId = ensureChannel(tenantId, channel);
    String campaignExternal = optional(row, "campaign_external_id", "campaign");
    UUID campaignId = ensureCampaign(tenantId, channelId, campaignExternal, optional(row, "campaign_name", "utm_campaign"));
    String medium = optional(row, "source_medium");
    if (medium.isBlank()) medium = String.join("/", optional(row, "utm_source"), optional(row, "utm_medium")).replaceAll("^/|/$", "");
    String eventType = upper(optional(row, "event_type"));
    if (eventType.isBlank()) eventType = "VISIT";
    jdbc.update("""
        insert into revenue_intelligence.touchpoint(tenant_id,source_system,external_id,customer_id,occurred_at,channel_id,campaign_id,source_medium,event_type,import_batch_id)
        values(?,?,?,?,?,?,?,?,?,?)
        on conflict(tenant_id,source_system,external_id) do update set customer_id=excluded.customer_id,occurred_at=excluded.occurred_at,
          channel_id=excluded.channel_id,campaign_id=excluded.campaign_id,source_medium=excluded.source_medium,event_type=excluded.event_type,
          import_batch_id=excluded.import_batch_id
        """, tenantId, source, externalId, customerId, occurredAt, channelId, campaignId, blankToNull(medium), eventType, batchId);
  }

  static String classifyLifecycle(int priorValidOrders, boolean historyComplete) {
    if (priorValidOrders > 0) return "RETURNING";
    return historyComplete ? "NEW" : "UNKNOWN";
  }

  static String classifyBusinessModel(String declared, String source, String orderChannel) {
    String value = upper(declared);
    if ("WHOLESALE".equals(value) || "RETAIL".equals(value)) return value;
    String evidence = upper(source + " " + orderChannel);
    if (evidence.contains("WHOLESALE") || evidence.contains("B2B") || evidence.contains("DISTRIBUTOR")) return "WHOLESALE";
    if (!evidence.isBlank()) return "RETAIL";
    return "UNKNOWN";
  }

  private void recalculateLifecycle(UUID tenantId, UUID customerId) {
    jdbc.update("""
        with ranked as (
          select o.id,row_number() over(order by o.ordered_at,o.id) as position,c.history_complete
          from revenue_intelligence.sales_order o join revenue_intelligence.customer c on c.id=o.customer_id and c.tenant_id=o.tenant_id
          where o.tenant_id=? and o.customer_id=? and o.status<>'CANCELLED'
        )
        update revenue_intelligence.sales_order o set customer_lifecycle=case when ranked.position>1 then 'RETURNING' when ranked.history_complete then 'NEW' else 'UNKNOWN' end
        from ranked where o.id=ranked.id
        """, tenantId, customerId);
    jdbc.update("""
        update revenue_intelligence.customer c set
          first_order_at=s.first_at,last_order_at=s.last_at,valid_order_count=s.order_count,
          lifecycle=case when s.order_count>1 then 'RETURNING' when s.order_count=1 and c.history_complete then 'NEW' else 'UNKNOWN' end,
          updated_at=now()
        from (select min(ordered_at) first_at,max(ordered_at) last_at,count(*)::int order_count
          from revenue_intelligence.sales_order where tenant_id=? and customer_id=? and status<>'CANCELLED') s
        where c.id=? and c.tenant_id=?
        """, tenantId, customerId, customerId, tenantId);
  }

  private void writeOrderRevision(UUID tenantId, UUID orderId, String actorEmail) {
    var snapshot = jdbc.queryForMap("""
        select source_system,external_id,customer_id,ordered_at,gross_amount,discount_amount,returned_amount,cancelled_amount,
          shipping_amount,tax_amount,net_revenue,source_channel,business_model,customer_lifecycle,status
        from revenue_intelligence.sales_order where tenant_id=? and id=?
        """, tenantId, orderId);
    int revision = jdbc.queryForObject("select coalesce(max(revision),0)+1 from revenue_intelligence.order_revision where tenant_id=? and order_id=?", Integer.class, tenantId, orderId);
    jdbc.update("insert into revenue_intelligence.order_revision(tenant_id,order_id,revision,action,actor,snapshot) values(?,?,?,?,?,?::jsonb)",
        tenantId, orderId, revision, revision == 1 ? "CREATED" : "UPDATED", actorEmail, writeJson(snapshot));
  }

  private UUID findCustomer(UUID tenantId, String customerSource, String externalId) {
    if (!customerSource.isBlank()) return findCustomerBySource(tenantId, customerSource, externalId);
    var matches = jdbc.query("""
        select distinct customer_id from revenue_intelligence.customer_source_link where tenant_id=? and external_id=? limit 2
        """, (r, n) -> r.getObject(1, UUID.class), tenantId, externalId);
    if (matches.size() > 1) throw invalid("CUSTOMER_REFERENCE_AMBIGUOUS", "external_customer_id trùng giữa nhiều nguồn; cần customer_source");
    return matches.isEmpty() ? null : matches.getFirst();
  }

  private UUID findCustomerBySource(UUID tenantId, String source, String externalId) {
    try { return jdbc.queryForObject("select customer_id from revenue_intelligence.customer_source_link where tenant_id=? and source_system=? and external_id=?", UUID.class, tenantId, source, externalId); }
    catch (EmptyResultDataAccessException missing) { return null; }
  }

  private UUID findCustomerByIdentity(UUID tenantId, String type, String hash) {
    if (hash == null) return null;
    try { return jdbc.queryForObject("select customer_id from revenue_intelligence.customer_identity where tenant_id=? and identity_type=? and identity_hash=?", UUID.class, tenantId, type, hash); }
    catch (EmptyResultDataAccessException missing) { return null; }
  }

  private void addIdentity(UUID tenantId, UUID customerId, String source, String type, String hash) {
    if (hash == null) return;
    jdbc.update("""
        insert into revenue_intelligence.customer_identity(tenant_id,customer_id,identity_type,identity_hash,source_system)
        values(?,?,?,?,?) on conflict(tenant_id,identity_type,identity_hash) do nothing
        """, tenantId, customerId, type, hash, source);
  }

  private UUID ensureChannel(UUID tenantId, String code) {
    if (code.isBlank()) code = "DIRECT_UNKNOWN";
    String type = code.startsWith("PAID") ? "PAID" : code.startsWith("DIRECT") ? "DIRECT" : code.startsWith("ORGANIC") ? "ORGANIC" : code.startsWith("REFERRAL") ? "REFERRAL" : "UNKNOWN";
    return jdbc.queryForObject("""
        insert into revenue_intelligence.channel(tenant_id,code,name,channel_type) values(?,?,?,?)
        on conflict(tenant_id,code) do update set name=excluded.name returning id
        """, UUID.class, tenantId, code, code.replace('_', ' '), type);
  }

  private UUID ensureCampaign(UUID tenantId, UUID channelId, String externalId, String name) {
    if (externalId == null || externalId.isBlank()) return null;
    return jdbc.queryForObject("""
        insert into revenue_intelligence.campaign(tenant_id,channel_id,external_id,name) values(?,?,?,?)
        on conflict(tenant_id,channel_id,external_id) do update set name=excluded.name returning id
        """, UUID.class, tenantId, channelId, externalId, name == null || name.isBlank() ? externalId : name);
  }

  private void recordError(UUID tenantId, UUID batchId, CsvTableParser.Row row, IllegalArgumentException error) {
    String message = error.getMessage() == null ? "ROW_INVALID" : error.getMessage();
    String code = message.contains(":") ? message.substring(0, message.indexOf(':')) : message;
    code = code.replaceAll("[^A-Z0-9_]", "_").substring(0, Math.min(code.length(), 80));
    var masked = new LinkedHashMap<String, String>(row.values());
    if (masked.containsKey("email")) masked.put("email", maskEmail(normalizeEmail(masked.get("email"))));
    if (masked.containsKey("phone")) masked.put("phone", maskPhone(normalizePhone(masked.get("phone"))));
    jdbc.update("insert into revenue_intelligence.import_error(tenant_id,batch_id,row_number,error_code,message,raw_payload) values(?,?,?,?,?,?::jsonb)",
        tenantId, batchId, row.number(), code, message.substring(0, Math.min(message.length(), 500)), writeJson(masked));
  }

  private static String required(Map<String, String> row, String... keys) {
    String value = optional(row, keys);
    if (value.isBlank()) throw invalid("FIELD_REQUIRED", String.join("/", keys) + " là bắt buộc");
    return value;
  }
  private static String optional(Map<String, String> row, String... keys) {
    for (var key : keys) { var value = row.get(key); if (value != null && !value.trim().isEmpty()) return value.trim(); }
    return "";
  }
  private static BigDecimal money(String value, String field) {
    if (value == null || value.isBlank()) return BigDecimal.ZERO;
    try { var amount = new BigDecimal(value.trim()); if (amount.signum() < 0) throw new NumberFormatException(); return amount; }
    catch (NumberFormatException invalid) { throw invalid("AMOUNT_INVALID", field + " phải là số không âm"); }
  }
  private static Timestamp timestamp(String value) {
    try { return Timestamp.from(OffsetDateTime.parse(value).toInstant()); }
    catch (Exception invalid) { throw invalid("TIMESTAMP_INVALID", "Thời gian phải theo ISO-8601 và có timezone"); }
  }
  private static boolean bool(String value) { return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value); }
  private static String normalizeStatus(String status, BigDecimal returned) {
    if (status.isBlank() || "PAID".equals(status)) return returned.signum() > 0 ? "PARTIALLY_RETURNED" : "COMPLETED";
    if (List.of("COMPLETED","RETURNED","PARTIALLY_RETURNED","CANCELLED").contains(status)) return status;
    throw invalid("STATUS_INVALID", "Trạng thái đơn hàng không được hỗ trợ: " + status);
  }
  private static String normalizeEmail(String value) {
    String email = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    if (!email.isBlank() && !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) throw invalid("EMAIL_INVALID", "Email không hợp lệ");
    return email;
  }
  private static String normalizePhone(String value) {
    String phone = value == null ? "" : value.replaceAll("[^0-9]", "");
    if (!phone.isBlank() && (phone.length() < 8 || phone.length() > 15)) throw invalid("PHONE_INVALID", "Số điện thoại phải có 8-15 chữ số");
    if (phone.startsWith("0")) phone = "84" + phone.substring(1);
    return phone;
  }
  private static String maskEmail(String email) {
    if (email == null || email.isBlank()) return null;
    int at = email.indexOf('@'); return email.substring(0, 1) + "***" + email.substring(at);
  }
  private static String maskPhone(String phone) {
    if (phone == null || phone.isBlank()) return null;
    return "***" + phone.substring(Math.max(0, phone.length() - 4));
  }
  private static String channelCode(String value) {
    String code = upper(value).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_|_$", "");
    return code.isBlank() ? "DIRECT_UNKNOWN" : code;
  }
  private static String upper(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
  private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
  private static String safeFileName(String value) {
    if (value == null || value.isBlank()) return "upload.csv";
    return value.replaceAll("[\\r\\n\\\\/]", "_").substring(0, Math.min(value.length(), 255));
  }
  private static IllegalArgumentException invalid(String code, String message) { return new IllegalArgumentException(code + ": " + message); }
  private static String sha256(byte[] value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
    catch (Exception impossible) { throw new IllegalStateException(impossible); }
  }
  private String writeJson(Object value) { try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); } }
  @SuppressWarnings("unchecked") private Map<String, Object> readMap(String value) { try { return json.readValue(value, Map.class); } catch (Exception e) { return Map.of(); } }
}
