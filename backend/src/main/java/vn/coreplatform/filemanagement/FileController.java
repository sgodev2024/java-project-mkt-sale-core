package vn.coreplatform.filemanagement;

import static vn.coreplatform.shared.ApiExceptionHandler.ApiProblem;

import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import vn.coreplatform.eventing.OutboxService;
import vn.coreplatform.kernel.ResourceRegistry;
import vn.coreplatform.permission.PermissionService;

/**
 * E8: upload qua session (STAGING -> SCANNING -> ACTIVE sau scan CLEAN); endpoint /files cũ là
 * đường tắt đi đủ 3 bước trong một lời gọi. Reconcile + staging-cleanup chỉ dành cho Platform Admin.
 */
@RestController @RequestMapping("/api/v1/files")
public class FileController {
  private final JdbcTemplate jdbc;private final PermissionService permissions;private final vn.coreplatform.audit.AuditService audits;private final OutboxService outbox;private final FileStorageService storage;private final ResourceRegistry resources;
  public FileController(JdbcTemplate jdbc,PermissionService permissions,vn.coreplatform.audit.AuditService audits,OutboxService outbox,FileStorageService storage,ResourceRegistry resources){this.jdbc=jdbc;this.permissions=permissions;this.audits=audits;this.outbox=outbox;this.storage=storage;this.resources=resources;}
  public record FileItem(UUID id,String name,String mediaType,long sizeBytes,String classification,String status,String checksumSha256,Instant createdAt,Instant updatedAt){}
  public record PageResult(List<FileItem> items,int page,int size,long total){}
  public record SessionCreate(String name,String mediaType,@jakarta.validation.constraints.Pattern(regexp="INTERNAL|CONFIDENTIAL|RESTRICTED") String classification,String resourceType,String resourceId){}
  public record SessionView(UUID sessionId,String status,String notice){}
  public record ContentResult(long sizeBytes,String checksumSha256,String status){}

  @GetMapping PageResult list(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size,@RequestParam(defaultValue="") String q,Authentication a){
    var scope=permissions.scope(a,"FILE","READ");if(!scope.allowed())throw new ApiProblem(HttpStatus.FORBIDDEN,"PERMISSION_DENIED","Không có quyền đọc file");
    var t=permissions.tenant(a);var owner=scope.ownerOnly()?permissions.account(a):null;int s=Math.max(1,Math.min(size,100)),p=Math.max(0,page);String search="%"+q.toLowerCase(Locale.ROOT)+"%";
    long total=jdbc.queryForObject("select count(*) from files.file_object where tenant_id=? and status in ('ACTIVE','QUARANTINED') and (?::uuid is null or owner_subject_id=?) and (?='' or lower(name) like ?)",Long.class,t,owner,owner,q,search);
    var items=jdbc.query("select * from files.file_object where tenant_id=? and status in ('ACTIVE','QUARANTINED') and (?::uuid is null or owner_subject_id=?) and (?='' or lower(name) like ?) order by updated_at desc,id limit ? offset ?",(r,n)->item(r),t,owner,owner,q,search,s,p*s);
    return new PageResult(items,p,s,total);
  }

  /** Bước 1: mở session — chưa có nội dung, chưa bao giờ ACTIVE (E8-S01). */
  @PostMapping("/upload-sessions") @ResponseStatus(HttpStatus.CREATED) @Transactional
  SessionView createSession(@RequestBody SessionCreate request,Authentication a){
    permissions.require(a,"FILE","CREATE",permissions.account(a));
    var id=storage.createSession(permissions.tenant(a),tenantKey(a),permissions.account(a),
      sanitize(request.name()),Optional.ofNullable(request.mediaType()).orElse("application/octet-stream"),
      Optional.ofNullable(request.classification()).orElse("INTERNAL"),request.resourceType(),request.resourceId());
    audits.record(tenantKey(a),permissions.account(a),a.getName(),"FILE_SESSION_OPENED","FILE",id.toString(),"SUCCESS",null);
    return new SessionView(id,"STAGING","PUT /files/upload-sessions/{id}/content sau đó POST .../finalize");
  }

  /** Bước 2: stream nội dung + checksum -> SCANNING. */
  @PostMapping("/upload-sessions/{id}/content") @Transactional
  ContentResult putContent(@PathVariable UUID id,@RequestPart("file") MultipartFile file,Authentication a) throws java.io.IOException {
    requireOwnSession(id,a);
    if(file.isEmpty())throw new ApiProblem(HttpStatus.BAD_REQUEST,"FILE_SIZE","Nội dung rỗng");
    var uploaded=storage.writeContent(id,file.getInputStream());
    return new ContentResult(uploaded.sizeBytes(),uploaded.checksumSha256(),"SCANNING");
  }

  /** Bước 3: scan -> CLEAN mới ACTIVE (E8-S02); INFECTED -> QUARANTINED. */
  @PostMapping("/upload-sessions/{id}/finalize") @Transactional
  FileItem finalizeUpload(@PathVariable UUID id,Authentication a){
    requireOwnSession(id,a);
    var item=storage.finalizeUpload(id);
    audits.record(tenantKey(a),permissions.account(a),a.getName(),"FILE_UPLOADED","FILE",id.toString(),"SUCCESS","{\"status\":\""+item.status()+"\"}");
    var payload=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
      .put("fileId",item.id().toString()).put("name",item.name()).put("classification",item.classification());
    outbox.publish(tenantKey(a),"file.uploaded.v1","file",item.id().toString(),payload);
    resources.adjustRecordCount("file-object",1);
    return item;
  }

  /** Đường tắt một lời gọi (frontend hiện tại): đi đủ 3 bước qua đúng state machine. */
  @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.CREATED) @Transactional
  FileItem upload(@RequestPart("file") MultipartFile file,@RequestParam(defaultValue="INTERNAL") String classification,Authentication a) throws java.io.IOException {
    if(file.isEmpty()||file.getSize()>25L*1024*1024)throw new ApiProblem(HttpStatus.BAD_REQUEST,"FILE_SIZE","File rỗng hoặc vượt 25 MB");if(!classification.matches("INTERNAL|CONFIDENTIAL|RESTRICTED"))throw new ApiProblem(HttpStatus.BAD_REQUEST,"CLASSIFICATION","Phân loại không hợp lệ");
    var session=createSession(new SessionCreate(file.getOriginalFilename(),file.getContentType(),classification,null,null),a);
    putContent(session.sessionId(),file,a);
    return finalizeUpload(session.sessionId(),a);
  }

  @GetMapping("/{id}/content") ResponseEntity<org.springframework.core.io.Resource> download(@PathVariable UUID id,Authentication a){
    var row=row(id,a);permissions.require(a,"FILE","READ",row.owner());
    if(!"ACTIVE".equals(row.status()))throw new ApiProblem(HttpStatus.NOT_FOUND,"CONTENT_NOT_AVAILABLE","File không ở trạng thái ACTIVE");
    var path=storage.resolve(row.key());
    if(!java.nio.file.Files.isRegularFile(path))throw new ApiProblem(HttpStatus.NOT_FOUND,"CONTENT_MISSING","Không tìm thấy nội dung file");
    audits.record(tenantKey(a),permissions.account(a),a.getName(),"FILE_DOWNLOADED","FILE",id.toString(),"SUCCESS",null);
    return ResponseEntity.ok().contentType(MediaType.parseMediaType(row.mediaType()))
      .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename*=UTF-8''"+java.net.URLEncoder.encode(row.name(),java.nio.charset.StandardCharsets.UTF_8))
      .contentLength(row.size()).body(new org.springframework.core.io.FileSystemResource(path));
  }

  @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) @Transactional
  void delete(@PathVariable UUID id,Authentication a){
    var row=row(id,a);permissions.require(a,"FILE","DELETE",row.owner());
    if(row.legalHold())throw new ApiProblem(HttpStatus.CONFLICT,"FILE_LEGAL_HOLD","File đang bị giữ dữ liệu — không được xóa");
    jdbc.update("update files.file_object set status='DELETED',deleted_at=now(),updated_at=now() where id=?",id);
    resources.adjustRecordCount("file-object",-1);
    if(row.key()!=null&&row.key().startsWith("objects/"))try{java.nio.file.Files.deleteIfExists(storage.resolve(row.key()));}catch(java.io.IOException e){throw new ApiProblem(HttpStatus.INTERNAL_SERVER_ERROR,"FILE_DELETE_FAILED","Không thể xóa object");}
    audits.record(tenantKey(a),permissions.account(a),a.getName(),"FILE_DELETED","FILE",id.toString(),"SUCCESS",null);
  }

  // ---- E8-S04: reconcile + staging cleanup (Platform Admin) ----
  @PostMapping("/reconcile") FileStorageService.Reconciliation reconcile(Authentication a){
    platformAdmin(a);
    var report=storage.reconcile();
    audits.record(tenantKey(a),permissions.account(a),a.getName(),"FILE_RECONCILED","FILE",null,"SUCCESS",
      "{\"checked\":"+report.checked()+",\"missing\":"+report.missingObjects()+",\"mismatch\":"+report.checksumMismatches()+",\"orphans\":"+report.orphansDeleted()+"}");
    return report;
  }
  @PostMapping("/staging-cleanup") java.util.Map<String,Object> stagingCleanup(@RequestParam(defaultValue="60") int olderThanMinutes,Authentication a){
    platformAdmin(a);
    var deleted=storage.cleanupStaging(olderThanMinutes);
    audits.record(tenantKey(a),permissions.account(a),a.getName(),"FILE_STAGING_CLEANED","FILE",null,"SUCCESS","{\"deleted\":"+deleted+"}");
    return java.util.Map.of("deleted",deleted);
  }

  private record Row(String key,String name,String mediaType,long size,UUID owner,String status,boolean legalHold){}
  private Row row(UUID id,Authentication a){
    var x=jdbc.query("select storage_key,name,media_type,size_bytes,owner_subject_id,status,legal_hold from files.file_object where id=? and tenant_id=? and status<>'DELETED'",
      (r,n)->new Row(r.getString(1),r.getString(2),r.getString(3),r.getLong(4),r.getObject(5,UUID.class),r.getString(6),r.getBoolean(7)),id,permissions.tenant(a));
    if(x.isEmpty())throw new ApiProblem(HttpStatus.NOT_FOUND,"FILE_NOT_FOUND","File không tồn tại");
    return x.get(0);
  }
  private void requireOwnSession(UUID id,Authentication a){
    var owner=jdbc.queryForList("select owner_subject_id from files.file_object where id=? and tenant_id=?",java.util.UUID.class,id,permissions.tenant(a));
    if(owner.isEmpty()||!owner.getFirst().equals(permissions.account(a)))throw new ApiProblem(HttpStatus.FORBIDDEN,"PERMISSION_DENIED","Session không phải của bạn");
  }
  private void platformAdmin(Authentication a){if(a==null||a.getAuthorities().stream().noneMatch(x->x.getAuthority().equals("ROLE_PLATFORM_ADMIN")))throw new ApiProblem(HttpStatus.FORBIDDEN,"PERMISSION_DENIED","Yêu cầu quyền Platform Administrator");}
  private String tenantKey(Authentication a){return permissions.tenantKey(a);}
  private String sanitize(String value){var name=Optional.ofNullable(value).orElse("file.bin").replace('\\','/');name=name.substring(name.lastIndexOf('/')+1).replaceAll("[\\r\\n\\u0000]","_");return name.length()>255?name.substring(name.length()-255):name;}
  private FileItem item(java.sql.ResultSet r)throws java.sql.SQLException{return new FileItem(r.getObject("id",UUID.class),r.getString("name"),r.getString("media_type"),r.getLong("size_bytes"),r.getString("classification"),r.getString("status"),r.getString("checksum_sha256"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant());}
}
