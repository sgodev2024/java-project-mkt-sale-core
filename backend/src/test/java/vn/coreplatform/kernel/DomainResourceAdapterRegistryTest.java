package vn.coreplatform.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DomainResourceAdapterRegistryTest {
  private final ResourceRegistry resources = mock(ResourceRegistry.class);

  @Test void registersAndDelegatesWithoutOwningDomainPersistence() throws Exception {
    var adapter = adapter("sales-order", "DOMAIN");
    var registry = new DomainResourceAdapterRegistry(List.of(adapter), resources);
    registry.run(null);

    verify(resources).register(adapter.descriptor());
    assertThat(registry.find(UUID.randomUUID(), "sales-order", "ORD-1").attributes()).containsEntry("status", "PAID");
    assertThat(registry.history(UUID.randomUUID(), "sales-order", "ORD-1", 10)).hasSize(1);
  }

  @Test void duplicateOrNonDomainAdapterFailsFast() {
    assertThatThrownBy(() -> new DomainResourceAdapterRegistry(List.of(adapter("sales-order", "DOMAIN"), adapter("sales-order", "DOMAIN")), resources))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("Duplicate");
    assertThatThrownBy(() -> new DomainResourceAdapterRegistry(List.of(adapter("sales-order", "DYNAMIC")), resources))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("storageMode=DOMAIN");
  }

  @Test void historyLimitIsBounded() {
    var registry = new DomainResourceAdapterRegistry(List.of(adapter("sales-order", "DOMAIN")), resources);
    assertThatThrownBy(() -> registry.history(UUID.randomUUID(), "sales-order", "ORD-1", 201))
        .hasMessageContaining("1 đến 200");
  }

  private static DomainResourceAdapter adapter(String type, String storageMode) {
    return new DomainResourceAdapter() {
      @Override public ResourceDescriptor descriptor() {
        return new ResourceDescriptor(type, "Sales Order", "revenue-intelligence", storageMode, "1.0.0", List.of("READ"), "ALWAYS", "CONFIDENTIAL");
      }
      @Override public Optional<Snapshot> find(UUID tenantId, String resourceId) {
        return Optional.of(new Snapshot(type, resourceId, "1.0.0", 1, Map.of("status", "PAID")));
      }
      @Override public List<HistoryEntry> history(UUID tenantId, String resourceId, int limit) {
        return List.of(new HistoryEntry(1, "CREATED", "tester", Instant.EPOCH, Map.of("status", "PAID")));
      }
    };
  }
}
