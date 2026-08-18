package vn.coreplatform.kernel;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Public read/history SPI cho code-first aggregate (BA CAP-003/CAP-009).
 *
 * Adapter chỉ cung cấp contract quan sát thống nhất; command, repository, transaction và
 * invariant vẫn thuộc domain module. Kernel không biến Domain Model thành generic CRUD.
 */
public interface DomainResourceAdapter {
  ResourceDescriptor descriptor();

  Optional<Snapshot> find(UUID tenantId, String resourceId);

  List<HistoryEntry> history(UUID tenantId, String resourceId, int limit);

  record Snapshot(
      String resourceType,
      String resourceId,
      String schemaVersion,
      long revision,
      Map<String, Object> attributes
  ) {
    public Snapshot {
      attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
  }

  record HistoryEntry(
      long revision,
      String action,
      String actor,
      java.time.Instant occurredAt,
      Map<String, Object> snapshot
  ) {
    public HistoryEntry {
      snapshot = snapshot == null ? Map.of() : Map.copyOf(snapshot);
    }
  }
}
