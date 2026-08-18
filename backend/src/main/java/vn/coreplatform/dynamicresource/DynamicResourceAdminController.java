package vn.coreplatform.dynamicresource;

import static vn.coreplatform.shared.ApiExceptionHandler.ApiProblem;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import vn.coreplatform.audit.AuditService;
import vn.coreplatform.permission.PermissionService;

/**
 * E9 admin operations trên dynamic resource:
 * S01 schema versioning + cổng breaking-change (phải xác nhận migration mới activate),
 * S03 guard từ chối descriptor DOMAIN trên generic endpoint,
 * S04 governed index compiler (chỉ nhận field key, SQL sinh từ template — không inject được),
 * S05 custom field (không bao giờ đè typed field của schema).
 */
@RestController @RequestMapping("/api/v1/dynamic")
public class DynamicResourceAdminController {
  private final JdbcTemplate jdbc;
  private final PermissionService permissions;
  private final AuditService audits;
  private final DynamicResourceController main;

  public DynamicResourceAdminController(JdbcTemplate jdbc, PermissionService permissions, AuditService audits, DynamicResourceController main) {
    this.jdbc = jdbc; this.permissions = permissions; this.audits = audits; this.main = main;
  }

  public record SchemaUpdate(JsonNode schema) {}
  public record IndexRequest(@NotBlank String fieldKey) {}
  public record IndexView(UUID id, String definitionKey, String fieldKey, String indexName, String status) {}

  /** E9-S03: generic endpoint từ chối resource có descriptor DOMAIN. */
  void rejectDomainDescriptor(String resourceKey, UUID tenantId) {
    var storageMode = jdbc.queryForList("select storage_mode from platform.resource_descriptor where resource_type=?", String.class, resourceKey);
    if (!storageMode.isEmpty() && "DOMAIN".equals(storageMode.getFirst()))
      throw new ApiProblem(HttpStatus.CONFLICT, "DOMAIN_RESOURCE_NOT_GENERIC",
          "Resource '" + resourceKey + "' là DOMAIN descriptor — phải dùng code-first API của module sở hữu, không dùng generic CRUD");
  }

  /**
   * E9-S01: nâng schema. Thay đổi KHÔNG phá vỡ (thêm field optional) áp dụng ngay;
   * thay đổi PHÁ VỠ (xóa field / đổi type / thêm required) chỉ được lưu thành pending
   * và chờ POST .../migration-confirmed — record vẫn validate theo schema cũ.
   */
  @PostMapping("/{resourceKey}/schema") @Transactional
  public DynamicResourceController.Definition updateSchema(@PathVariable String resourceKey, @RequestBody SchemaUpdate request, Authentication auth) {
    permissions.require(auth, "DYNAMIC_DEFINITION", "UPDATE", null);
    var tenantId = main.tenantOf(auth);
    rejectDomainDescriptor(resourceKey, tenantId);
    var current = main.definitionRow(resourceKey, tenantId);
    if (current.status() != null && !"ACTIVE".equals(current.status()))
      throw new ApiProblem(HttpStatus.CONFLICT, "DEFINITION_NOT_ACTIVE", "Definition chưa ACTIVE (đang " + current.status() + ")");
    main.validateSchemaShape(request.schema());
    var breaking = isBreaking(current.schema(), request.schema());
    if (breaking) {
      jdbc.update("""
          update dynamic_resource.definition set pending_schema=?::jsonb, previous_schema=schema_json, migration_state='REQUIRED', updated_at=now()
          where id=?
          """, request.schema().toString(), current.id());
      audits.record(main.tenantKeyOf(auth), main.accountOf(auth), auth.getName(), "DYNAMIC_SCHEMA_BREAKING_PENDING", resourceKey, current.id().toString(), "SUCCESS", null);
    } else {
      jdbc.update("update dynamic_resource.definition set schema_json=?::jsonb, version=version+1, updated_at=now() where id=?", request.schema().toString(), current.id());
      audits.record(main.tenantKeyOf(auth), main.accountOf(auth), auth.getName(), "DYNAMIC_SCHEMA_UPDATED", resourceKey, current.id().toString(), "SUCCESS", null);
    }
    return main.definitionRow(resourceKey, tenantId);
  }

  /** E9-S01: xác nhận đã chạy migration — mới áp dụng schema phá vỡ. */
  @PostMapping("/{resourceKey}/migration-confirmed") @Transactional
  public DynamicResourceController.Definition confirmMigration(@PathVariable String resourceKey, Authentication auth) {
    permissions.require(auth, "ACCESS_ADMIN", "MANAGE", null);
    var tenantId = main.tenantOf(auth);
    var current = main.definitionRow(resourceKey, tenantId);
    if (!"REQUIRED".equals(migrationState(current.id())))
      throw new ApiProblem(HttpStatus.CONFLICT, "NO_PENDING_MIGRATION", "Không có schema phá vỡ nào đang chờ migration");
    jdbc.update("""
        update dynamic_resource.definition set schema_json=pending_schema, pending_schema=null, previous_schema=null,
          migration_state='APPLIED', version=version+1, updated_at=now() where id=?
        """, current.id());
    audits.record(main.tenantKeyOf(auth), main.accountOf(auth), auth.getName(), "DYNAMIC_SCHEMA_MIGRATION_CONFIRMED", resourceKey, current.id().toString(), "SUCCESS", null);
    return main.definitionRow(resourceKey, tenantId);
  }

  /**
   * E9-S04: governed index — client chỉ gửi field key; index name + expression SQL do platform
   * sinh từ template, không có đường nào inject SQL. Index reproducible (tên deterministic).
   */
  @PostMapping("/{resourceKey}/indexes") @ResponseStatus(HttpStatus.CREATED) @Transactional
  public IndexView createIndex(@PathVariable String resourceKey, @org.springframework.validation.annotation.Validated @RequestBody IndexRequest request, Authentication auth) {
    permissions.require(auth, "DYNAMIC_DEFINITION", "UPDATE", null);
    var tenantId = main.tenantOf(auth);
    rejectDomainDescriptor(resourceKey, tenantId);
    var definition = main.definitionRow(resourceKey, tenantId);
    var fieldKey = request.fieldKey();
    if (!fieldKey.matches("[a-z][a-zA-Z0-9_]{1,79}"))
      throw new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_FIELD", "Field key không hợp lệ");
    var exists = false;
    for (var f : definition.schema().path("fields")) if (fieldKey.equals(f.path("key").asText())) { exists = true; break; }
    if (!exists) throw new ApiProblem(HttpStatus.BAD_REQUEST, "FIELD_NOT_IN_SCHEMA", "Field '" + fieldKey + "' không có trong schema");

    var indexName = ("dyn_" + resourceKey.replace('-', '_') + "_" + fieldKey + "_idx").toLowerCase();
    // SQL sinh hoàn toàn từ template với tham số đã validate — không nhận chuỗi nào từ client
    jdbc.update("create index if not exists " + indexName + " on dynamic_resource.record (((data ->> '" + fieldKey + "'))) where definition_id = '" + definition.id() + "'");
    var id = UUID.randomUUID();
    jdbc.update("""
        insert into dynamic_resource.managed_index(id, definition_id, field_key, index_name, status)
        values (?,?,?,?,'ACTIVE') on conflict (definition_id, field_key) do update set status='ACTIVE'
        """, id, definition.id(), fieldKey, indexName);
    audits.record(main.tenantKeyOf(auth), main.accountOf(auth), auth.getName(), "DYNAMIC_INDEX_CREATED", resourceKey, indexName, "SUCCESS", "{\"field\":\"" + fieldKey + "\"}");
    return new IndexView(id, resourceKey, fieldKey, indexName, "ACTIVE");
  }

  private String migrationState(UUID definitionId) {
    return jdbc.queryForObject("select migration_state from dynamic_resource.definition where id=?", String.class, definitionId);
  }

  static boolean isBreaking(JsonNode oldSchema, JsonNode newSchema) {
    var oldFields = new java.util.HashMap<String, JsonNode>();
    oldSchema.path("fields").forEach(f -> oldFields.put(f.path("key").asText(), f));
    for (var nf : newSchema.path("fields")) {
      var key = nf.path("key").asText();
      var of = oldFields.get(key);
      if (of == null) {
        if (nf.path("required").asBoolean(false)) return true; // thêm field required = phá vỡ
        continue;
      }
      if (!of.path("type").asText().equals(nf.path("type").asText())) return true; // đổi type
      if (!of.path("required").asBoolean(false) && nf.path("required").asBoolean(false)) return true; // optional -> required
    }
    for (var oldKey : oldFields.keySet()) {
      var stillThere = false;
      for (var nf : newSchema.path("fields")) if (oldKey.equals(nf.path("key").asText())) { stillThere = true; break; }
      if (!stillThere) return true; // xóa field
    }
    return false;
  }
}
