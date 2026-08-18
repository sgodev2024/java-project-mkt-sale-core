package vn.coreplatform.kernel;

import java.util.List;

/**
 * Unified Resource Contract (CP-BA-001 mục 9.2, E4-S01): descriptor mô tả resource của
 * cả Domain Model lẫn Dynamic Resource mà không ép chung persistence. Registry dùng
 * descriptor này để chặn trùng owner/type và phát hiện drift giữa các lần đăng ký.
 */
public record ResourceDescriptor(
    String resourceType,      // định danh duy nhất toàn deployment, vd: "customer-preference"
    String name,
    String ownerModule,       // phải là module đã đăng ký trong platform.module
    String storageMode,       // DOMAIN | DYNAMIC
    String schemaVersion,
    List<String> supportedActions,
    String auditPolicy,       // vd: ALWAYS
    String dataClassification // PUBLIC | INTERNAL | CONFIDENTIAL | RESTRICTED | null (chưa duyệt)
) {
  public ResourceDescriptor {
    resourceType = resourceType == null ? "" : resourceType.trim();
    name = name == null ? "" : name.trim();
    ownerModule = ownerModule == null ? "" : ownerModule.trim();
    storageMode = storageMode == null ? "" : storageMode.trim();
    schemaVersion = schemaVersion == null ? "" : schemaVersion.trim();
    supportedActions = supportedActions == null ? List.of() : List.copyOf(supportedActions);
    auditPolicy = auditPolicy == null || auditPolicy.isBlank() ? "ALWAYS" : auditPolicy.trim();
    dataClassification = normalizedClassification(dataClassification);
  }
  private static String normalizedClassification(String value) { return value == null || value.trim().isEmpty() ? null : value.trim(); }
}
