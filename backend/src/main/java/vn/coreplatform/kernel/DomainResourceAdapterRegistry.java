package vn.coreplatform.kernel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import vn.coreplatform.shared.ApiExceptionHandler.ApiProblem;

/** Registry fail-fast cho Domain Resource Adapter; đăng ký descriptor sau ModuleRegistry. */
@Service
@Order(10)
public class DomainResourceAdapterRegistry implements ApplicationRunner {
  private final Map<String, DomainResourceAdapter> adapters;
  private final ResourceRegistry resources;

  public DomainResourceAdapterRegistry(List<DomainResourceAdapter> contributors, ResourceRegistry resources) {
    this.resources = resources;
    var validated = new LinkedHashMap<String, DomainResourceAdapter>();
    for (var adapter : contributors) {
      var descriptor = adapter.descriptor();
      if (!"DOMAIN".equals(descriptor.storageMode()))
        throw new IllegalStateException("Domain adapter phải dùng storageMode=DOMAIN: " + descriptor.resourceType());
      if (validated.put(descriptor.resourceType(), adapter) != null)
        throw new IllegalStateException("Duplicate Domain Resource Adapter: " + descriptor.resourceType());
    }
    this.adapters = Map.copyOf(validated);
  }

  @Override public void run(ApplicationArguments args) {
    adapters.values().forEach(adapter -> resources.register(adapter.descriptor()));
  }

  public DomainResourceAdapter require(String resourceType) {
    var adapter = adapters.get(resourceType);
    if (adapter == null)
      throw new ApiProblem(HttpStatus.NOT_FOUND, "DOMAIN_ADAPTER_NOT_FOUND", "Domain adapter chưa đăng ký: " + resourceType);
    return adapter;
  }

  public DomainResourceAdapter.Snapshot find(UUID tenantId, String resourceType, String resourceId) {
    requireTenantAndId(tenantId, resourceId);
    return require(resourceType).find(tenantId, resourceId)
        .orElseThrow(() -> new ApiProblem(HttpStatus.NOT_FOUND, "DOMAIN_RESOURCE_NOT_FOUND", "Không tìm thấy " + resourceType + "/" + resourceId));
  }

  public List<DomainResourceAdapter.HistoryEntry> history(UUID tenantId, String resourceType, String resourceId, int limit) {
    requireTenantAndId(tenantId, resourceId);
    if (limit < 1 || limit > 200)
      throw new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_HISTORY_LIMIT", "History limit phải từ 1 đến 200");
    return List.copyOf(require(resourceType).history(tenantId, resourceId, limit));
  }

  public Map<String, DomainResourceAdapter> adapters() { return adapters; }

  private static void requireTenantAndId(UUID tenantId, String resourceId) {
    if (tenantId == null) throw new ApiProblem(HttpStatus.BAD_REQUEST, "TENANT_REQUIRED", "Tenant bắt buộc");
    if (resourceId == null || resourceId.isBlank())
      throw new ApiProblem(HttpStatus.BAD_REQUEST, "RESOURCE_ID_REQUIRED", "Resource ID bắt buộc");
  }
}
