package vn.coreplatform.kernel;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import vn.coreplatform.shared.ApiExceptionHandler.ApiProblem;

/**
 * Resource Registry SPI (E4-S01, kernel trung tính nghiệp vụ). Đăng ký descriptor cho
 * resource của mọi module; chặn owner chưa đăng ký, trùng resource_type, và descriptor
 * drift (đăng ký lại resource_type cũ với nội dung khác) — không bao giờ chấp nhận im lặng.
 */
@Service
public class ResourceRegistry {
  public static final Set<String> APPROVED_CLASSIFICATIONS = Set.of("PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED");
  private static final String TYPE_PATTERN = "[a-z][a-z0-9-]{2,119}";
  private final JdbcTemplate jdbc;

  public ResourceRegistry(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  public ResourceDescriptor register(ResourceDescriptor descriptor) {
    validate(descriptor);
    var existing = findByType(descriptor.resourceType());
    if (existing == null) {
      jdbc.update("""
          insert into platform.resource_descriptor(name, storage_mode, owner_module, record_count, schema_version, resource_type, supported_actions, audit_policy, data_classification)
          values (?, ?, ?, 0, ?, ?, ?, ?, ?)
          """, descriptor.name(), descriptor.storageMode(), descriptor.ownerModule(), descriptor.schemaVersion(),
          descriptor.resourceType(), String.join(",", descriptor.supportedActions()), descriptor.auditPolicy(), descriptor.dataClassification());
      return descriptor;
    }
    if (drifted(existing, descriptor)) {
      var drift = driftField(existing, descriptor);
      throw new ApiProblem(HttpStatus.CONFLICT, "DESCRIPTOR_DRIFT",
          "Resource type '" + descriptor.resourceType() + "' đã đăng ký với " + drift + " khác — không được ghi đè im lặng");
    }
    return asDescriptor(existing); // idempotent: đăng ký lặp giống hệt thì giữ nguyên
  }

  private ResourceDescriptor asDescriptor(Map<String, Object> row) {
    var actions = String.valueOf(row.get("supported_actions"));
    return new ResourceDescriptor(row.get("resource_type").toString(), row.get("name").toString(), row.get("owner_module").toString(),
        row.get("storage_mode").toString(), row.get("schema_version").toString(),
        actions.isBlank() ? List.of() : List.of(actions.split(",")), row.get("audit_policy").toString(), (String) row.get("data_classification"));
  }

  public void classify(String resourceType, String classification) {
    if (!APPROVED_CLASSIFICATIONS.contains(classification))
      throw new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_CLASSIFICATION", "Classification chưa được phê duyệt: " + classification);
    int changed = jdbc.update("update platform.resource_descriptor set data_classification=?, updated_at=now() where resource_type=?", classification, resourceType);
    if (changed == 0)
      throw new ApiProblem(HttpStatus.NOT_FOUND, "DESCRIPTOR_NOT_FOUND", "Descriptor chưa đăng ký cho resource type " + resourceType);
  }

  public void adjustRecordCount(String resourceType, long delta) {
    jdbc.update("update platform.resource_descriptor set record_count = greatest(record_count + ?, 0), updated_at=now() where resource_type=?", delta, resourceType);
  }

  public void synchronizeRecordCount(String resourceType, long count) {
    if (count < 0) throw new IllegalArgumentException("record count không được âm");
    int changed = jdbc.update("update platform.resource_descriptor set record_count=?, updated_at=now() where resource_type=?", count, resourceType);
    if (changed == 0) throw new IllegalStateException("Descriptor chưa đăng ký: " + resourceType);
  }

  private void validate(ResourceDescriptor descriptor) {
    if (!descriptor.resourceType().matches(TYPE_PATTERN))
      throw new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_RESOURCE_TYPE", "resource_type phải khớp " + TYPE_PATTERN);
    if (!Set.of("DOMAIN", "DYNAMIC").contains(descriptor.storageMode()))
      throw new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_STORAGE_MODE", "storage_mode phải là DOMAIN hoặc DYNAMIC");
    if (descriptor.dataClassification() != null && !APPROVED_CLASSIFICATIONS.contains(descriptor.dataClassification()))
      throw new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_CLASSIFICATION", "Classification chưa được phê duyệt: " + descriptor.dataClassification());
    var owner = jdbc.queryForObject("select count(*) from platform.module where module_key=?", Integer.class, descriptor.ownerModule());
    if (owner == null || owner == 0)
      throw new ApiProblem(HttpStatus.UNPROCESSABLE_ENTITY, "OWNER_MODULE_NOT_FOUND", "Owner module không tồn tại: " + descriptor.ownerModule());
  }

  private Map<String, Object> findByType(String resourceType) {
    return jdbc.queryForList("select resource_type, name, storage_mode, owner_module, schema_version, supported_actions, audit_policy, data_classification from platform.resource_descriptor where resource_type=?", resourceType)
        .stream().findFirst().orElse(null);
  }

  private boolean drifted(Map<String, Object> existing, ResourceDescriptor descriptor) {
    return !Objects.equals(existing.get("name"), descriptor.name())
        || !Objects.equals(existing.get("storage_mode"), descriptor.storageMode())
        || !Objects.equals(existing.get("owner_module"), descriptor.ownerModule())
        || !Objects.equals(existing.get("schema_version"), descriptor.schemaVersion())
        || !Objects.equals(existing.get("supported_actions"), String.join(",", descriptor.supportedActions()))
        || !Objects.equals(existing.get("audit_policy"), descriptor.auditPolicy())
        || !Objects.equals(existing.get("data_classification"), descriptor.dataClassification());
  }

  private String driftField(Map<String, Object> existing, ResourceDescriptor descriptor) {
    if (!Objects.equals(existing.get("name"), descriptor.name())) return "name";
    if (!Objects.equals(existing.get("storage_mode"), descriptor.storageMode())) return "storage_mode";
    if (!Objects.equals(existing.get("owner_module"), descriptor.ownerModule())) return "owner_module";
    if (!Objects.equals(existing.get("schema_version"), descriptor.schemaVersion())) return "schema_version";
    if (!Objects.equals(existing.get("supported_actions"), String.join(",", descriptor.supportedActions()))) return "supported_actions";
    if (!Objects.equals(existing.get("audit_policy"), descriptor.auditPolicy())) return "audit_policy";
    return "data_classification";
  }
}
