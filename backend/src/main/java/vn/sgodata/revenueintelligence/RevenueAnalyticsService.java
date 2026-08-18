package vn.sgodata.revenueintelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.coreplatform.audit.AuditService;
import vn.coreplatform.eventing.OutboxService;
import vn.coreplatform.shared.ApiExceptionHandler.ApiProblem;

@Service
public class RevenueAnalyticsService {
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

  public record RebuildResult(LocalDate from, LocalDate to, int ordersProcessed, int resultsWritten) {}
  public record Reconciliation(LocalDate from, LocalDate to, BigDecimal grossRevenue, BigDecimal discounts,
      BigDecimal returns, BigDecimal cancellations, BigDecimal shipping, BigDecimal tax, BigDecimal computedNetRevenue,
      BigDecimal storedNetRevenue, BigDecimal variance) {}
  public record Kpis(BigDecimal adSpend, BigDecimal netRevenue, BigDecimal paidAttributedRevenue,
      BigDecimal roas, BigDecimal mer, long orders, long customers, BigDecimal repeatRate) {}
  public record Breakdown(String key, String label, BigDecimal revenue, BigDecimal spend, long orders) {}
  public record DataQuality(long totalOrders, long matchedOrders, BigDecimal matchCoverage, boolean warning, String message) {}
  public record Dashboard(LocalDate from, LocalDate to, Kpis kpis, List<Breakdown> channels,
      List<Breakdown> lifecycle, List<Breakdown> businessModels, DataQuality dataQuality) {}
  public record CustomerSummary(UUID id, String sourceSystem, String externalId, String fullName, String emailMasked,
      String phoneMasked, boolean historyComplete, String lifecycle, int validOrderCount,
      java.time.Instant firstOrderAt, java.time.Instant lastOrderAt) {}

  private record OrderForAttribution(UUID id, UUID customerId, Timestamp orderedAt, BigDecimal netRevenue, String sourceChannel) {}
  private record Touch(UUID id, UUID channelId) {}

  private final JdbcTemplate jdbc;
  private final AuditService audits;
  private final OutboxService outbox;
  private final ObjectMapper json;

  public RevenueAnalyticsService(JdbcTemplate jdbc, AuditService audits, OutboxService outbox, ObjectMapper json) {
    this.jdbc = jdbc; this.audits = audits; this.outbox = outbox; this.json = json;
  }

  @Transactional
  public RebuildResult rebuild(UUID tenantId, LocalDate from, LocalDate to, UUID actorId, String actorEmail, String tenantKey) {
    validatePeriod(from, to);
    var start = start(from); var end = endExclusive(to);
    jdbc.update("""
        delete from revenue_intelligence.attribution_result a using revenue_intelligence.sales_order o
        where a.tenant_id=? and a.order_id=o.id and o.tenant_id=? and o.ordered_at>=? and o.ordered_at<?
        """, tenantId, tenantId, start, end);
    var orders = jdbc.query("""
        select id,customer_id,ordered_at,net_revenue,source_channel from revenue_intelligence.sales_order
        where tenant_id=? and ordered_at>=? and ordered_at<? and status<>'CANCELLED'
        order by ordered_at,id
        """, (r, n) -> new OrderForAttribution(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getTimestamp(3),
        r.getBigDecimal(4), r.getString(5)), tenantId, start, end);
    int written = 0;
    for (var order : orders) {
      Touch first = firstTouch(tenantId, order);
      Touch lastNonDirect = lastNonDirect(tenantId, order);
      UUID fallback = ensureChannel(tenantId, order.sourceChannel());
      written += insertAttribution(tenantId, order, "FIRST_TOUCH", first == null ? fallback : first.channelId(), first == null ? null : first.id());
      written += insertAttribution(tenantId, order, "LAST_NON_DIRECT", lastNonDirect == null ? fallback : lastNonDirect.channelId(), lastNonDirect == null ? null : lastNonDirect.id());
    }
    audits.record(tenantKey, actorId, actorEmail, "REVENUE_ATTRIBUTION_REBUILT", "REVENUE_ATTRIBUTION", from + ":" + to, "SUCCESS",
        json.createObjectNode().put("orders", orders.size()).put("results", written).toString());
    outbox.publish(tenantKey, "revenue-attribution.rebuilt.v1", "revenue-attribution", from + ":" + to,
        json.createObjectNode().put("from", from.toString()).put("to", to.toString()).put("orders", orders.size()));
    return new RebuildResult(from, to, orders.size(), written);
  }

  public Reconciliation reconciliation(UUID tenantId, LocalDate from, LocalDate to) {
    validatePeriod(from, to);
    var row = jdbc.queryForMap("""
        select coalesce(sum(gross_amount),0) gross,coalesce(sum(discount_amount),0) discounts,
          coalesce(sum(returned_amount),0) returns,coalesce(sum(cancelled_amount),0) cancellations,
          coalesce(sum(shipping_amount),0) shipping,coalesce(sum(tax_amount),0) tax,
          coalesce(sum(gross_amount-discount_amount-returned_amount-cancelled_amount),0) computed,
          coalesce(sum(net_revenue),0) stored
        from revenue_intelligence.sales_order where tenant_id=? and ordered_at>=? and ordered_at<?
        """, tenantId, start(from), endExclusive(to));
    var computed = decimal(row, "computed");
    var stored = decimal(row, "stored");
    return new Reconciliation(from, to, decimal(row, "gross"), decimal(row, "discounts"), decimal(row, "returns"),
        decimal(row, "cancellations"), decimal(row, "shipping"), decimal(row, "tax"), computed, stored, stored.subtract(computed));
  }

  public Dashboard dashboard(UUID tenantId, LocalDate from, LocalDate to) {
    validatePeriod(from, to);
    var start = start(from); var end = endExclusive(to);
    var totals = jdbc.queryForMap("""
        select count(*) orders,count(distinct customer_id) customers,
          count(*) filter(where customer_id is not null) matched_orders,
          coalesce(sum(net_revenue),0) net_revenue
        from revenue_intelligence.sales_order
        where tenant_id=? and ordered_at>=? and ordered_at<? and status<>'CANCELLED'
        """, tenantId, start, end);
    var spendRow = jdbc.queryForMap("select coalesce(sum(amount),0) spend from revenue_intelligence.ad_spend where tenant_id=? and spend_date>=? and spend_date<=?", tenantId, from, to);
    var paidRow = jdbc.queryForMap("""
        select coalesce(sum(a.attributed_revenue),0) paid_revenue
        from revenue_intelligence.attribution_result a
        join revenue_intelligence.sales_order o on o.id=a.order_id and o.tenant_id=a.tenant_id
        join revenue_intelligence.channel c on c.id=a.channel_id and c.tenant_id=a.tenant_id
        where a.tenant_id=? and a.model='LAST_NON_DIRECT' and c.channel_type='PAID' and o.ordered_at>=? and o.ordered_at<?
        """, tenantId, start, end);
    long orders = number(totals, "orders"), customers = number(totals, "customers"), matched = number(totals, "matched_orders");
    var spend = decimal(spendRow, "spend");
    var revenue = decimal(totals, "net_revenue");
    var paid = decimal(paidRow, "paid_revenue");
    long repeatCustomers = jdbc.queryForObject("""
        select count(*) from (select customer_id from revenue_intelligence.sales_order
          where tenant_id=? and ordered_at>=? and ordered_at<? and status<>'CANCELLED' and customer_id is not null
          group by customer_id having count(*)>1) repeated
        """, Long.class, tenantId, start, end);
    var coverage = ratio(matched, orders);
    var repeatRate = ratio(repeatCustomers, customers);
    var kpis = new Kpis(spend, revenue, paid, divide(paid, spend), divide(revenue, spend), orders, customers, repeatRate);
    var quality = new DataQuality(orders, matched, coverage, coverage.compareTo(new BigDecimal("95.00")) < 0,
        coverage.compareTo(new BigDecimal("95.00")) < 0 ? "Tỷ lệ ghép đơn hàng với khách hàng dưới ngưỡng 95%." : "Dữ liệu đạt ngưỡng ghép khách hàng tối thiểu.");
    return new Dashboard(from, to, kpis, channelBreakdown(tenantId, from, to),
        orderBreakdown(tenantId, start, end, "customer_lifecycle"), orderBreakdown(tenantId, start, end, "business_model"), quality);
  }

  public List<CustomerSummary> customers(UUID tenantId) {
    return jdbc.query("""
        select id,source_system,external_id,full_name,email_masked,phone_masked,history_complete,lifecycle,valid_order_count,first_order_at,last_order_at
        from revenue_intelligence.customer where tenant_id=? and merged_into is null order by updated_at desc limit 200
        """, (r, n) -> new CustomerSummary(r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getString(4),
        r.getString(5), r.getString(6), r.getBoolean(7), r.getString(8), r.getInt(9),
        r.getTimestamp(10) == null ? null : r.getTimestamp(10).toInstant(), r.getTimestamp(11) == null ? null : r.getTimestamp(11).toInstant()), tenantId);
  }

  private List<Breakdown> channelBreakdown(UUID tenantId, LocalDate from, LocalDate to) {
    return jdbc.query("""
        with revenue as (
          select a.channel_id,sum(a.attributed_revenue) revenue,count(*) orders
          from revenue_intelligence.attribution_result a join revenue_intelligence.sales_order o on o.id=a.order_id and o.tenant_id=a.tenant_id
          where a.tenant_id=? and a.model='LAST_NON_DIRECT' and o.ordered_at>=? and o.ordered_at<? group by a.channel_id
        ), spend as (
          select channel_id,sum(amount) spend from revenue_intelligence.ad_spend where tenant_id=? and spend_date>=? and spend_date<=? group by channel_id
        )
        select c.code,c.name,coalesce(r.revenue,0),coalesce(s.spend,0),coalesce(r.orders,0)
        from revenue_intelligence.channel c left join revenue r on r.channel_id=c.id left join spend s on s.channel_id=c.id
        where c.tenant_id=? and (r.channel_id is not null or s.channel_id is not null)
        order by coalesce(r.revenue,0) desc,c.code
        """, (r, n) -> new Breakdown(r.getString(1), r.getString(2), r.getBigDecimal(3), r.getBigDecimal(4), r.getLong(5)),
        tenantId, start(from), endExclusive(to), tenantId, from, to, tenantId);
  }

  private List<Breakdown> orderBreakdown(UUID tenantId, Timestamp start, Timestamp end, String dimension) {
    if (!"customer_lifecycle".equals(dimension) && !"business_model".equals(dimension)) throw new IllegalArgumentException("Unsupported dimension");
    String sql = "select " + dimension + ",sum(net_revenue),count(*) from revenue_intelligence.sales_order " +
        "where tenant_id=? and ordered_at>=? and ordered_at<? and status<>'CANCELLED' group by " + dimension + " order by sum(net_revenue) desc";
    return jdbc.query(sql, (r, n) -> new Breakdown(r.getString(1), label(r.getString(1)), r.getBigDecimal(2), ZERO, r.getLong(3)), tenantId, start, end);
  }

  private Touch firstTouch(UUID tenantId, OrderForAttribution order) {
    if (order.customerId() == null) return null;
    var rows = jdbc.query("""
        select id,channel_id from revenue_intelligence.touchpoint
        where tenant_id=? and customer_id=? and occurred_at>=cast(? as timestamptz) - interval '30 days' and occurred_at<=?
        order by occurred_at,id limit 1
        """, (r, n) -> new Touch(r.getObject(1, UUID.class), r.getObject(2, UUID.class)), tenantId, order.customerId(), order.orderedAt(), order.orderedAt());
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private Touch lastNonDirect(UUID tenantId, OrderForAttribution order) {
    if (order.customerId() == null) return null;
    var rows = jdbc.query("""
        select t.id,t.channel_id from revenue_intelligence.touchpoint t join revenue_intelligence.channel c on c.id=t.channel_id and c.tenant_id=t.tenant_id
        where t.tenant_id=? and t.customer_id=? and t.occurred_at>=cast(? as timestamptz) - interval '30 days' and t.occurred_at<=? and c.channel_type<>'DIRECT'
        order by t.occurred_at desc,t.id desc limit 1
        """, (r, n) -> new Touch(r.getObject(1, UUID.class), r.getObject(2, UUID.class)), tenantId, order.customerId(), order.orderedAt(), order.orderedAt());
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private int insertAttribution(UUID tenantId, OrderForAttribution order, String model, UUID channelId, UUID touchpointId) {
    return jdbc.update("""
        insert into revenue_intelligence.attribution_result(tenant_id,order_id,model,channel_id,touchpoint_id,attributed_revenue)
        values(?,?,?,?,?,?)
        """, tenantId, order.id(), model, channelId, touchpointId, order.netRevenue());
  }

  private UUID ensureChannel(UUID tenantId, String sourceChannel) {
    String code = sourceChannel == null ? "" : sourceChannel.trim().toUpperCase().replaceAll("[^A-Z0-9]+", "_").replaceAll("^_|_$", "");
    if (code.isBlank()) code = "DIRECT_UNKNOWN";
    String type = code.startsWith("PAID") ? "PAID" : code.startsWith("DIRECT") ? "DIRECT" : code.startsWith("ORGANIC") ? "ORGANIC" : code.startsWith("REFERRAL") ? "REFERRAL" : "UNKNOWN";
    return jdbc.queryForObject("""
        insert into revenue_intelligence.channel(tenant_id,code,name,channel_type) values(?,?,?,?)
        on conflict(tenant_id,code) do update set name=excluded.name returning id
        """, UUID.class, tenantId, code, code.replace('_', ' '), type);
  }

  static void validatePeriod(LocalDate from, LocalDate to) {
    if (from == null || to == null || from.isAfter(to)) throw new ApiProblem(HttpStatus.BAD_REQUEST, "PERIOD_INVALID", "Khoảng ngày không hợp lệ");
    if (from.plusDays(366).isBefore(to)) throw new ApiProblem(HttpStatus.BAD_REQUEST, "PERIOD_TOO_LARGE", "Mỗi lần chỉ truy vấn tối đa 366 ngày");
  }
  private static Timestamp start(LocalDate value) { return Timestamp.from(value.atStartOfDay(BUSINESS_ZONE).toInstant()); }
  private static Timestamp endExclusive(LocalDate value) { return Timestamp.from(value.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant()); }
  private static BigDecimal decimal(Map<String, Object> row, String key) { var value = row.get(key); return value == null ? ZERO : new BigDecimal(value.toString()).setScale(2, RoundingMode.HALF_UP); }
  private static long number(Map<String, Object> row, String key) { var value = row.get(key); return value == null ? 0 : ((Number) value).longValue(); }
  private static BigDecimal divide(BigDecimal numerator, BigDecimal denominator) { return denominator.signum() == 0 ? null : numerator.divide(denominator, 2, RoundingMode.HALF_UP); }
  private static BigDecimal ratio(long numerator, long denominator) { return denominator == 0 ? ZERO : BigDecimal.valueOf(numerator * 100.0 / denominator).setScale(2, RoundingMode.HALF_UP); }
  private static String label(String key) { return switch (key) { case "NEW" -> "Khách mới"; case "RETURNING" -> "Khách quay lại"; case "WHOLESALE" -> "Bán buôn"; case "RETAIL" -> "Bán lẻ"; default -> "Chưa xác định"; }; }
}
