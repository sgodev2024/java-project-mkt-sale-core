package vn.sgodata.revenueintelligence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import vn.coreplatform.kernel.DomainResourceAdapter;
import vn.coreplatform.kernel.ResourceDescriptor;

@Component
public class RevenueOrderAdapter implements DomainResourceAdapter {
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public RevenueOrderAdapter(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

  @Override public ResourceDescriptor descriptor() {
    return new ResourceDescriptor("revenue-order", "Revenue Order", "revenue-intelligence", "DOMAIN", "v1",
        List.of("READ", "CREATE"), "ALWAYS", "CONFIDENTIAL");
  }

  @Override public Optional<Snapshot> find(UUID tenantId, String resourceId) {
    final UUID id;
    try { id = UUID.fromString(resourceId); } catch (IllegalArgumentException invalid) { return Optional.empty(); }
    var rows = jdbc.query("""
        select o.id,o.source_system,o.external_id,o.ordered_at,o.gross_amount,o.discount_amount,o.returned_amount,
          o.cancelled_amount,o.shipping_amount,o.tax_amount,o.net_revenue,o.source_channel,o.business_model,
          o.customer_lifecycle,o.status,coalesce(max(r.revision),1) revision
        from revenue_intelligence.sales_order o left join revenue_intelligence.order_revision r on r.order_id=o.id and r.tenant_id=o.tenant_id
        where o.tenant_id=? and o.id=? group by o.id
        """, (r, n) -> {
          var attributes = new LinkedHashMap<String, Object>();
          for (var column : List.of("source_system","external_id","ordered_at","gross_amount","discount_amount","returned_amount",
              "cancelled_amount","shipping_amount","tax_amount","net_revenue","source_channel","business_model","customer_lifecycle","status"))
            if (r.getObject(column) != null) attributes.put(column, r.getObject(column));
          return new Snapshot("revenue-order", r.getObject("id", UUID.class).toString(), "v1", r.getLong("revision"), attributes);
        }, tenantId, id);
    return rows.stream().findFirst();
  }

  @Override public List<HistoryEntry> history(UUID tenantId, String resourceId, int limit) {
    final UUID id;
    try { id = UUID.fromString(resourceId); } catch (IllegalArgumentException invalid) { return List.of(); }
    return jdbc.query("""
        select revision,action,actor,occurred_at,snapshot::text from revenue_intelligence.order_revision
        where tenant_id=? and order_id=? order by revision desc limit ?
        """, (r, n) -> new HistoryEntry(r.getLong(1), r.getString(2), r.getString(3), r.getTimestamp(4).toInstant(), read(r.getString(5))),
        tenantId, id, limit);
  }

  private Map<String, Object> read(String value) {
    try { return json.readValue(value, MAP); } catch (Exception invalid) { return Map.of(); }
  }
}
