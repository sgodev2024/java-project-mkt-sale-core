package vn.coreplatform.dynamicresource;

import static vn.coreplatform.shared.ApiExceptionHandler.ApiProblem;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import vn.coreplatform.kernel.ResourceDescriptor;
import vn.coreplatform.kernel.ResourceRegistry;
import vn.coreplatform.permission.PermissionService;
import vn.coreplatform.permission.RequirePermission;

@RestController @RequestMapping("/api/v1/dynamic")
public class DynamicResourceController {
  private final JdbcTemplate jdbc;private final PermissionService permissions;private final ResourceRegistry resources;private final vn.coreplatform.audit.AuditService audits;private final vn.coreplatform.eventing.OutboxService outbox; public DynamicResourceController(JdbcTemplate jdbc,PermissionService permissions,ResourceRegistry resources,vn.coreplatform.audit.AuditService audits,vn.coreplatform.eventing.OutboxService outbox){this.jdbc=jdbc;this.permissions=permissions;this.resources=resources;this.audits=audits;this.outbox=outbox;}
  public record Definition(UUID id,String resourceKey,String name,int version,JsonNode schema,String status,String dataClassification,Instant updatedAt){}
  public record RecordItem(UUID id,String resourceKey,JsonNode data,int version,String status,UUID ownerSubjectId,Instant createdAt,Instant updatedAt){}
  public record Revision(UUID id,int version,String operation,JsonNode data,UUID actorId,Instant occurredAt){}
  public record DefinitionCreate(@NotBlank @Pattern(regexp="[a-z][a-z0-9-]{2,99}") String resourceKey,@NotBlank @Size(max=160) String name,@NotNull JsonNode schema,String classification){}
  public record ClassificationUpdate(@Pattern(regexp="PUBLIC|INTERNAL|CONFIDENTIAL|RESTRICTED") String classification){}
  public record PageResult(List<RecordItem> items,int page,int size,long total){}
  public record ImportResult(int imported,int failed,List<String> errors){}

  @RequirePermission(resource = "DYNAMIC_DEFINITION", action = "READ")
  @GetMapping("/definitions") List<Definition> definitions(Authentication auth){permissions.require(auth,"DYNAMIC_DEFINITION","READ",null);var t=tenant(auth);return jdbc.query("select * from dynamic_resource.definition where tenant_id=? order by name",(r,n)->definition(r),t);}
  @PostMapping("/definitions") @ResponseStatus(HttpStatus.CREATED) @Transactional Definition createDefinition(@Valid @RequestBody DefinitionCreate request,Authentication auth){
    permissions.require(auth,"DYNAMIC_DEFINITION","CREATE",null); validateSchema(request.schema());
    // E4-S05 classification gate: definition thiếu classification được phê duyệt không bao giờ ACTIVE
    var approved=request.classification()!=null&&ResourceRegistry.APPROVED_CLASSIFICATIONS.contains(request.classification());
    var id=UUID.randomUUID(); var t=tenant(auth); var actor=account(auth);
    try{jdbc.update("insert into dynamic_resource.definition(id,tenant_id,resource_key,name,schema_json,data_classification,status,created_by) values(?,?,?,?,?::jsonb,?,?,?)",
      id,t,request.resourceKey(),request.name().trim(),request.schema().toString(),approved?request.classification():null,approved?"ACTIVE":"PENDING",actor);}
    catch(Exception e){throw new ApiProblem(HttpStatus.CONFLICT,"DEFINITION_EXISTS","Resource key đã tồn tại");}
    resources.register(new ResourceDescriptor(request.resourceKey(),request.name().trim(),"dynamic-resource","DYNAMIC","v1",
      List.of("READ","CREATE","UPDATE","DELETE"),"ALWAYS",approved?request.classification():null));
    audit(auth,"DYNAMIC_DEFINITION_CREATED","DYNAMIC_DEFINITION",id);
    if(!approved) audit(auth,"DYNAMIC_DEFINITION_PENDING_CLASSIFICATION","DYNAMIC_DEFINITION",id);
    return getDefinition(id,t);
  }

  /** E4-S05: phê duyệt classification để kích hoạt definition đang PENDING. */
  @PostMapping("/{resourceKey}/classification") @Transactional Definition classify(@PathVariable String resourceKey,@Valid @RequestBody ClassificationUpdate request,Authentication auth){
    permissions.require(auth,"ACCESS_ADMIN","MANAGE",null);
    var t=tenant(auth);
    var changed=jdbc.update("update dynamic_resource.definition set data_classification=?, status='ACTIVE', updated_at=now() where tenant_id=? and resource_key=?",
      request.classification(),t,resourceKey);
    if(changed==0)throw new ApiProblem(HttpStatus.NOT_FOUND,"DEFINITION_NOT_FOUND","Dynamic Resource không tồn tại");
    resources.classify(resourceKey,request.classification());
    audit(auth,"DYNAMIC_CLASSIFICATION_APPROVED",resourceKey,UUID.nameUUIDFromBytes(resourceKey.getBytes()));
    return jdbc.queryForObject("select * from dynamic_resource.definition where tenant_id=? and resource_key=?",(r,n)->definition(r),t,resourceKey);
  }
  @RequirePermission(resource = "DYNAMIC_RECORD", action = "READ")
  @GetMapping("/{resourceKey}/records") PageResult records(@PathVariable String resourceKey,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size,@RequestParam(defaultValue="") String q,Authentication auth){
    var scope=permissions.scope(auth,"DYNAMIC_RECORD","READ");if(!scope.allowed())throw new ApiProblem(HttpStatus.FORBIDDEN,"PERMISSION_DENIED","Không có quyền đọc record");var t=tenant(auth); var d=definitionByKey(resourceKey,t); int safeSize=Math.max(1,Math.min(size,100)),safePage=Math.max(page,0); String search="%"+q.toLowerCase(Locale.ROOT)+"%";UUID owner=scope.ownerOnly()?account(auth):null;
    long total=jdbc.queryForObject("select count(*) from dynamic_resource.record where tenant_id=? and definition_id=? and status='ACTIVE' and (?::uuid is null or owner_subject_id=?) and (?='' or lower(data::text) like ?)",Long.class,t,d.id(),owner,owner,q,search);
    var items=jdbc.query("select r.*,? resource_key from dynamic_resource.record r where tenant_id=? and definition_id=? and status='ACTIVE' and (?::uuid is null or owner_subject_id=?) and (?='' or lower(data::text) like ?) order by updated_at desc,id limit ? offset ?",(r,n)->record(r),resourceKey,t,d.id(),owner,owner,q,search,safeSize,safePage*safeSize);
    return new PageResult(items,safePage,safeSize,total);
  }
  @PostMapping("/{resourceKey}/records") @ResponseStatus(HttpStatus.CREATED) @Transactional RecordItem create(@PathVariable String resourceKey,@RequestBody JsonNode data,Authentication auth){JsonNode customAttributes=data==null?null:data.path("_custom");var cleanData=data==null?null:stripCustom(data);
    var actor=account(auth);permissions.require(auth,"DYNAMIC_RECORD","CREATE",actor);var t=tenant(auth);var d=definitionByKey(resourceKey,t);validateData(d.schema(),data);var id=UUID.randomUUID();
    jdbc.update("insert into dynamic_resource.record(id,tenant_id,definition_id,data,custom_attributes,owner_subject_id,created_by) values(?,?,?,?::jsonb,?::jsonb,?,?)",id,t,d.id(),cleanData.toString(),safeCustom(d.schema(),customAttributes),actor,actor);
    jdbc.update("insert into dynamic_resource.revision(tenant_id,record_id,record_version,operation,data,actor_id) values(?,?,1,'CREATE',?::jsonb,?)",t,id,data.toString(),actor);
    resources.adjustRecordCount(resourceKey,1);
    outbox.publish(t.toString(), "dynamic-record.created.v1", "dynamic-record", id.toString(),
        new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("resourceKey", resourceKey).put("version", 1));
    audit(auth,"DYNAMIC_RECORD_CREATED",resourceKey,id); return getRecord(id,resourceKey,t);
  }
  /** E11-S01: full-text search dùng tsvector + GIN index. */
  @RequirePermission(resource = "DYNAMIC_RECORD", action = "READ")
  @GetMapping("/{resourceKey}/search") PageResult searchRecords(@PathVariable String resourceKey,@RequestParam String q,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size,Authentication auth){
    permissions.require(auth,"DYNAMIC_RECORD","READ",null);var t=tenant(auth);var d=definitionByKey(resourceKey,t);
    int safeSize=Math.max(1,Math.min(size,100)),safePage=Math.max(page,0);
    long total=jdbc.queryForObject("select count(*) from dynamic_resource.record where tenant_id=? and definition_id=? and status='ACTIVE' and search_vector @@ plainto_tsquery('simple',?)",Long.class,t,d.id(),q);
    var items=jdbc.query("select r.*,? resource_key from dynamic_resource.record r where tenant_id=? and definition_id=? and status='ACTIVE' and search_vector @@ plainto_tsquery('simple',?) order by updated_at desc,id limit ? offset ?",(r,n)->record(r),resourceKey,t,d.id(),q,safeSize,safePage*safeSize);
    return new PageResult(items,safePage,safeSize,total);
  }

  @GetMapping("/{resourceKey}/records/{id}") RecordItem get(@PathVariable String resourceKey,@PathVariable UUID id,Authentication auth){var item=getRecord(id,resourceKey,tenant(auth));permissions.require(auth,"DYNAMIC_RECORD","READ",item.ownerSubjectId());return item;}
  @PutMapping("/{resourceKey}/records/{id}") @Transactional RecordItem update(@PathVariable String resourceKey,@PathVariable UUID id,@RequestBody JsonNode data,@RequestHeader("If-Match") int expectedVersion,Authentication auth){
    var current=getRecord(id,resourceKey,tenant(auth));permissions.require(auth,"DYNAMIC_RECORD","UPDATE",current.ownerSubjectId());var t=tenant(auth);var d=definitionByKey(resourceKey,t);validateData(d.schema(),data);int next=expectedVersion+1;
    int changed=jdbc.update("update dynamic_resource.record set data=?::jsonb,record_version=?,updated_at=now() where id=? and tenant_id=? and definition_id=? and record_version=? and status='ACTIVE'",data.toString(),next,id,t,d.id(),expectedVersion);
    if(changed==0)throw new ApiProblem(HttpStatus.CONFLICT,"VERSION_CONFLICT","Record đã thay đổi hoặc không tồn tại");
    jdbc.update("insert into dynamic_resource.revision(tenant_id,record_id,record_version,operation,data,actor_id) values(?,?,?,'UPDATE',?::jsonb,?)",t,id,next,data.toString(),account(auth));
    outbox.publish(t.toString(), "dynamic-record.updated.v1", "dynamic-record", id.toString(),
        new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("resourceKey", resourceKey).put("version", next));
    audit(auth,"DYNAMIC_RECORD_UPDATED",resourceKey,id);return getRecord(id,resourceKey,t);
  }
  @DeleteMapping("/{resourceKey}/records/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) @Transactional void archive(@PathVariable String resourceKey,@PathVariable UUID id,Authentication auth){var current=getRecord(id,resourceKey,tenant(auth));permissions.require(auth,"DYNAMIC_RECORD","DELETE",current.ownerSubjectId());var t=tenant(auth);var d=definitionByKey(resourceKey,t);int c=jdbc.update("update dynamic_resource.record set status='ARCHIVED',record_version=record_version+1,updated_at=now() where id=? and tenant_id=? and definition_id=? and status='ACTIVE'",id,t,d.id());if(c==0)throw new ApiProblem(HttpStatus.NOT_FOUND,"RECORD_NOT_FOUND","Record không tồn tại");
    outbox.publish(t.toString(), "dynamic-record.archived.v1", "dynamic-record", id.toString(),
        new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("resourceKey", resourceKey));
    audit(auth,"DYNAMIC_RECORD_ARCHIVED",resourceKey,id);}
  @GetMapping("/{resourceKey}/records/{id}/history") List<Revision> history(@PathVariable String resourceKey,@PathVariable UUID id,Authentication auth){var current=getRecord(id,resourceKey,tenant(auth));permissions.require(auth,"DYNAMIC_RECORD","READ",current.ownerSubjectId());var t=tenant(auth);return jdbc.query("select * from dynamic_resource.revision where tenant_id=? and record_id=? order by record_version desc",(r,n)->new Revision(r.getObject("id",UUID.class),r.getInt("record_version"),r.getString("operation"),readJson(r.getString("data")),r.getObject("actor_id",UUID.class),r.getTimestamp("occurred_at").toInstant()),t,id);}

  @GetMapping(value="/{resourceKey}/export.csv",produces="text/csv") ResponseEntity<byte[]> exportCsv(@PathVariable String resourceKey,Authentication auth){
    var scope=permissions.scope(auth,"DYNAMIC_RECORD","READ");if(!scope.allowed())throw new ApiProblem(HttpStatus.FORBIDDEN,"PERMISSION_DENIED","Không có quyền export");var t=tenant(auth);var d=definitionByKey(resourceKey,t);var owner=scope.ownerOnly()?account(auth):null;
    var rows=jdbc.query("select data from dynamic_resource.record where tenant_id=? and definition_id=? and status='ACTIVE' and (?::uuid is null or owner_subject_id=?) order by created_at,id",(r,n)->readJson(r.getString(1)),t,d.id(),owner,owner);
    var csv=new StringBuilder();var keys=fieldKeys(d.schema());csv.append(String.join(",",keys.stream().map(this::csvEscape).toList())).append('\n');for(var row:rows){var values=new ArrayList<String>();for(var key:keys)values.add(csvEscape(row.path(key).isMissingNode()?"":row.path(key).asText()));csv.append(String.join(",",values)).append('\n');}audit(auth,"DYNAMIC_CSV_EXPORTED",resourceKey,d.id());return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+resourceKey+".csv\"").body(csv.toString().getBytes(StandardCharsets.UTF_8));
  }
  @PostMapping(value="/{resourceKey}/import.csv",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @Transactional ImportResult importCsv(@PathVariable String resourceKey,@RequestPart("file") MultipartFile file,@RequestParam(defaultValue="") String batchKey,Authentication auth){
    if(file.isEmpty()||file.getSize()>10_000_000)throw new ApiProblem(HttpStatus.BAD_REQUEST,"INVALID_FILE","CSV rỗng hoặc vượt 10 MB");var t=tenant(auth);var d=definitionByKey(resourceKey,t);permissions.require(auth,"DYNAMIC_RECORD","CREATE",account(auth));
    if(!batchKey.isBlank()){var existing=jdbc.queryForList("select imported_count from dynamic_resource.import_batch where tenant_id=? and definition_id=? and batch_key=?",Integer.class,t,d.id(),batchKey);
    if(!existing.isEmpty())return new ImportResult(0,0,List.of("Batch '"+batchKey+"' đã import "+existing.getFirst()+" record trước đó — idempotent skip, 0 record mới"));}
    int imported=0,failed=0,rowNo=1;var errors=new ArrayList<String>();
    try(var reader=new BufferedReader(new InputStreamReader(file.getInputStream(),StandardCharsets.UTF_8))){var headerLine=reader.readLine();if(headerLine==null)throw new ApiProblem(HttpStatus.BAD_REQUEST,"INVALID_CSV","CSV thiếu header");var headers=parseCsvLine(headerLine);String line;while((line=reader.readLine())!=null){rowNo++;if(rowNo>10001)throw new ApiProblem(HttpStatus.BAD_REQUEST,"ROW_LIMIT","Tối đa 10.000 dòng");try{var values=parseCsvLine(line);var row=new HashMap<String,String>();for(int i=0;i<headers.size();i++)row.put(headers.get(i),i<values.size()?values.get(i):"");var data=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();for(var field:d.schema().path("fields")){var key=field.path("key").asText();var raw=row.getOrDefault(key,"");if(raw.isBlank())continue;switch(field.path("type").asText()){case "number"->data.put(key,new java.math.BigDecimal(raw));case "boolean"->data.put(key,Boolean.parseBoolean(raw));default->data.put(key,raw);}}create(resourceKey,data,auth);imported++;}catch(Exception e){failed++;if(errors.size()<100)errors.add("Dòng "+rowNo+": "+e.getMessage());}}}catch(IOException|IllegalArgumentException e){throw new ApiProblem(HttpStatus.BAD_REQUEST,"INVALID_CSV","CSV không hợp lệ: "+e.getMessage());}audit(auth,"DYNAMIC_CSV_IMPORTED",resourceKey,d.id());if(!batchKey.isBlank())jdbc.update("insert into dynamic_resource.import_batch(tenant_id,definition_id,batch_key,imported_count,failed_count) values(?,?,?,?,?) on conflict do nothing",t,d.id(),batchKey,imported,failed);return new ImportResult(imported,failed,errors);
  }

  static com.fasterxml.jackson.databind.node.ObjectNode stripCustom(JsonNode data){var out=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();data.fields().forEachRemaining(e->{if(!e.getKey().equals("_custom"))out.set(e.getKey(),e.getValue());});return out;}
  static String safeCustom(JsonNode schema,JsonNode custom){if(custom==null||!custom.isObject()||custom.isEmpty())return "{}";var allowed=new HashSet<String>();schema.path("fields").forEach(f->allowed.add(f.path("key").asText()));var out=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();custom.fields().forEachRemaining(e->{var k=e.getKey();if(!k.equals("_custom")&&!allowed.contains(k)&&k.matches("[a-z][a-zA-Z0-9_]{1,79}"))out.set(k,e.getValue());});return out.toString();}
  void validateSchemaShape(JsonNode schema){validateSchema(schema);} private void validateSchema(JsonNode schema){if(!schema.isObject()||!schema.path("fields").isArray())throw new ApiProblem(HttpStatus.BAD_REQUEST,"INVALID_SCHEMA","Schema phải có mảng fields");var keys=new HashSet<String>();for(var f:schema.path("fields")){var k=f.path("key").asText();var type=f.path("type").asText();if(!k.matches("[a-z][a-zA-Z0-9_]{1,79}")||!Set.of("string","number","boolean","date","object").contains(type)||!keys.add(k))throw new ApiProblem(HttpStatus.BAD_REQUEST,"INVALID_FIELD","Field key/type không hợp lệ hoặc trùng");}}
  private void validateData(JsonNode schema,JsonNode data){if(!data.isObject())throw new ApiProblem(HttpStatus.BAD_REQUEST,"INVALID_DATA","Data phải là JSON object");for(var f:schema.path("fields")){var k=f.path("key").asText();var v=data.get(k);if(f.path("required").asBoolean(false)&&(v==null||v.isNull()||v.asText().isBlank()))throw new ApiProblem(HttpStatus.BAD_REQUEST,"REQUIRED_FIELD","Thiếu field bắt buộc: "+k);if(v!=null&&!v.isNull()){var type=f.path("type").asText();boolean ok=switch(type){case "string","date"->v.isTextual();case "number"->v.isNumber();case "boolean"->v.isBoolean();case "object"->v.isObject();default->false;};if(!ok)throw new ApiProblem(HttpStatus.BAD_REQUEST,"FIELD_TYPE_MISMATCH","Sai kiểu dữ liệu: "+k);}}}
  private List<String> fieldKeys(JsonNode schema){var keys=new ArrayList<String>();schema.path("fields").forEach(f->keys.add(f.path("key").asText()));return keys;}
  private String csvEscape(String value){return value.matches(".*[,\"\\r\\n].*")?"\""+value.replace("\"","\"\"")+"\"":value;}
  private List<String> parseCsvLine(String line){var out=new ArrayList<String>();var cell=new StringBuilder();boolean quoted=false;for(int i=0;i<line.length();i++){char c=line.charAt(i);if(c=='\"'){if(quoted&&i+1<line.length()&&line.charAt(i+1)=='\"'){cell.append('\"');i++;}else quoted=!quoted;}else if(c==','&&!quoted){out.add(cell.toString());cell.setLength(0);}else cell.append(c);}if(quoted)throw new IllegalArgumentException("Dấu nháy CSV chưa đóng");out.add(cell.toString());return out;}
  private Definition definitionByKey(String key,UUID t){var domainCheck=jdbc.queryForList("select storage_mode from platform.resource_descriptor where resource_type=?",String.class,key);if(!domainCheck.isEmpty()&&"DOMAIN".equals(domainCheck.getFirst()))throw new ApiProblem(HttpStatus.CONFLICT,"DOMAIN_RESOURCE_NOT_GENERIC","Resource "+key+" là DOMAIN descriptor - dùng code-first API, không dùng generic CRUD");var x=jdbc.query("select * from dynamic_resource.definition where tenant_id=? and resource_key=? and status='ACTIVE'",(r,n)->definition(r),t,key);if(x.isEmpty())throw new ApiProblem(HttpStatus.NOT_FOUND,"DEFINITION_NOT_FOUND","Dynamic Resource không tồn tại");return x.get(0);}
  Definition definitionRow(String key,UUID t){return definitionByKey(key,t);}
  private Definition getDefinition(UUID id,UUID t){return jdbc.queryForObject("select * from dynamic_resource.definition where id=? and tenant_id=?",(r,n)->definition(r),id,t);}
  private RecordItem getRecord(UUID id,String key,UUID t){var d=definitionByKey(key,t);var x=jdbc.query("select r.*,? resource_key from dynamic_resource.record r where id=? and tenant_id=? and definition_id=? and status='ACTIVE'",(r,n)->record(r),key,id,t,d.id());if(x.isEmpty())throw new ApiProblem(HttpStatus.NOT_FOUND,"RECORD_NOT_FOUND","Record không tồn tại");return x.get(0);}
  private Definition definition(java.sql.ResultSet r)throws java.sql.SQLException{return new Definition(r.getObject("id",UUID.class),r.getString("resource_key"),r.getString("name"),r.getInt("version"),readJson(r.getString("schema_json")),r.getString("status"),r.getString("data_classification"),r.getTimestamp("updated_at").toInstant());}
  private RecordItem record(java.sql.ResultSet r)throws java.sql.SQLException{return new RecordItem(r.getObject("id",UUID.class),r.getString("resource_key"),readJson(r.getString("data")),r.getInt("record_version"),r.getString("status"),r.getObject("owner_subject_id",UUID.class),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant());}
  private JsonNode readJson(String value){try{return new com.fasterxml.jackson.databind.ObjectMapper().readTree(value);}catch(Exception e){throw new IllegalStateException(e);}}
  @SuppressWarnings("unchecked") private Map<String,Object> details(Authentication a){return (Map<String,Object>)a.getDetails();}
  UUID tenantOf(Authentication a){return tenant(a);}
  String tenantKeyOf(Authentication a){return jdbc.queryForObject("select tenant_key from platform.tenant where id=?", String.class, tenant(a));} private UUID tenant(Authentication a){if(a==null)throw new ApiProblem(HttpStatus.UNAUTHORIZED,"AUTH_REQUIRED","Yêu cầu đăng nhập");return (UUID)details(a).get("tenantId");}
  UUID accountOf(Authentication a){return account(a);} private UUID account(Authentication a){return (UUID)details(a).get("accountId");}
  private void requireAdmin(Authentication a){if(a==null||a.getAuthorities().stream().noneMatch(x->x.getAuthority().equals("ROLE_PLATFORM_ADMIN")))throw new ApiProblem(HttpStatus.FORBIDDEN,"PERMISSION_DENIED","Yêu cầu quyền Platform Administrator");}
  private void audit(Authentication a,String action,String type,UUID id){jdbc.update("insert into audit.event(id,actor_id,actor_email,tenant_key,action,resource_type,resource_id,result,correlation_id,occurred_at) values(?,?,?,?,?,?,?,?,?,now())",UUID.randomUUID(),account(a),a.getName(),tenant(a).toString(),action,type,id.toString(),"SUCCESS",vn.coreplatform.shared.CorrelationIdFilter.current());}
}
