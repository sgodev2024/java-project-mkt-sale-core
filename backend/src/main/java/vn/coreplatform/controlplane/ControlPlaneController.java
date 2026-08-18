package vn.coreplatform.controlplane;

import static vn.coreplatform.shared.ApiExceptionHandler.ApiProblem;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/control-plane")
public class ControlPlaneController {
  private final JdbcTemplate jdbc;
  private final vn.coreplatform.kernel.ResourceRegistry resources;
  private final vn.coreplatform.audit.AuditService audits;
  private final vn.coreplatform.jobs.JobService jobs;
  private final vn.coreplatform.eventing.OutboxService outbox;
  public ControlPlaneController(JdbcTemplate jdbc, vn.coreplatform.kernel.ResourceRegistry resources, vn.coreplatform.audit.AuditService audits, vn.coreplatform.eventing.OutboxService outbox, vn.coreplatform.jobs.JobService jobs){this.jdbc=jdbc;this.resources=resources;this.audits=audits;this.outbox=outbox;this.jobs=jobs;}

  public record Summary(long resources,long modules,long pendingOutbox,long runningJobs,long files,double storageGb,String coreVersion,String environment){}
  public record Module(UUID id,String name,String moduleKey,String version,String status,String description,String metric){}
  public record Resource(UUID id,String name,String storageMode,String ownerModule,long records,String schemaVersion,Instant updatedAt){}
  public record Activity(UUID id,String kind,String name,String metadata,String status,Instant occurredAt){}
  public record Role(UUID id,String name,int users,int policies,String scope){}
  public record FileItem(UUID id,String name,String mediaType,long sizeBytes,String classification,String status,Instant updatedAt){}
  public record AuditItem(UUID id,String actorEmail,String action,String resourceType,String resourceId,String result,UUID correlationId,Instant occurredAt){}
  public record Bootstrap(Summary summary,List<Module> modules,List<Resource> resources,List<Activity> activities,List<Role> roles,List<FileItem> files,List<AuditItem> audit,Map<String,String> settings){}
  public record ModuleStatus(@Pattern(regexp="HEALTHY|DISABLED|ATTENTION") String status){}
  public record ResourceCreate(@NotBlank @Size(max=160) String name,@Pattern(regexp="DOMAIN|DYNAMIC") String storageMode,@NotBlank @Size(max=80) String ownerModule,@NotBlank @Size(max=20) String schemaVersion){}
  public record RoleCreate(@NotBlank @Size(max=100) String name,@Size(max=120) String scope){}
  public record SettingUpdate(@NotBlank @Size(max=100) String key,@NotBlank @Size(max=500) String value){}

  @GetMapping("/bootstrap")
  Bootstrap bootstrap(Authentication auth){
    requireAdmin(auth);
    var runtimeSettings=settings();
    var summary=jdbc.queryForObject("select (select coalesce(sum(record_count),0) from platform.resource_descriptor) resources,(select count(*) from platform.module) modules,(select count(*) from async.outbox_event where status='PENDING') outbox,(select count(*) from async.job where status='RUNNING') jobs,(select count(*) from files.file_object where status in ('ACTIVE','QUARANTINED')) files,(select coalesce(sum(size_bytes),0)/1073741824.0 from files.file_object where status in ('ACTIVE','QUARANTINED')) storage_gb",(r,n)->new Summary(r.getLong("resources"),r.getLong("modules"),r.getLong("outbox"),r.getLong("jobs"),r.getLong("files"),r.getDouble("storage_gb"),"1.0.0",runtimeSettings.getOrDefault("environment.name","core-production-vn")));
    return new Bootstrap(summary,modules(),resources(),activities(),roles(),files(),auditRows(50),runtimeSettings);
  }

  @GetMapping("/audit") List<AuditItem> audit(@RequestParam(defaultValue="50") int limit, Authentication auth){
    requireAdmin(auth);
    return auditRows(limit);
  }

  private List<AuditItem> auditRows(int limit){
    int safe=Math.max(1,Math.min(limit,200));
    return jdbc.query("select id,actor_email,action,resource_type,resource_id,result,correlation_id,occurred_at from audit.event order by occurred_at desc limit ?",(r,n)->new AuditItem(r.getObject("id",UUID.class),r.getString("actor_email"),r.getString("action"),r.getString("resource_type"),r.getString("resource_id"),r.getString("result"),r.getObject("correlation_id",UUID.class),r.getTimestamp("occurred_at").toInstant()),safe);
  }

  @PatchMapping("/modules/{id}/status") @Transactional
  Module updateModule(@PathVariable UUID id,@Valid @RequestBody ModuleStatus request,Authentication auth){
    requireAdmin(auth); int changed=jdbc.update("update platform.module set status=? where id=?",request.status(),id);
    if(changed==0) throw new ApiProblem(HttpStatus.NOT_FOUND,"MODULE_NOT_FOUND","Module không tồn tại");
    audit(auth,"MODULE_STATUS_CHANGED","MODULE",id.toString(),"SUCCESS");
    return jdbc.queryForObject("select * from platform.module where id=?",(r,n)->module(r),id);
  }

  @PostMapping("/resources") @ResponseStatus(HttpStatus.CREATED) @Transactional
  Resource createResource(@Valid @RequestBody ResourceCreate request,Authentication auth){
    requireAdmin(auth);
    // E4-S01: qua Resource Registry — owner chưa đăng ký hoặc drift descriptor đều bị chặn có kiểm soát
    var descriptor = resources.register(new vn.coreplatform.kernel.ResourceDescriptor(
        request.name().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+","-").replaceAll("(^-+|-+$)",""),
        request.name().trim(), request.ownerModule(), request.storageMode(), request.schemaVersion(),
        java.util.List.of("READ","CREATE","UPDATE","DELETE"), "ALWAYS", null));
    var created = jdbc.queryForObject("select * from platform.resource_descriptor where resource_type=?",(r,n)->resource(r),descriptor.resourceType());
    audit(auth,"RESOURCE_CREATED","RESOURCE",created.id().toString(),"SUCCESS");
    return created;
  }

  @PostMapping("/roles") @ResponseStatus(HttpStatus.CREATED) @Transactional
  Role createRole(@Valid @RequestBody RoleCreate request,Authentication auth){
    requireAdmin(auth); var id=UUID.randomUUID();
    try{jdbc.update("insert into identity.role_summary(id,name,user_count,policy_count,scope) values(?,?,0,0,?)",id,request.name().trim(),Optional.ofNullable(request.scope()).filter(x->!x.isBlank()).orElse("Toàn deployment"));}
    catch(Exception e){throw new ApiProblem(HttpStatus.CONFLICT,"ROLE_ALREADY_EXISTS","Tên vai trò đã tồn tại");}
    audit(auth,"ROLE_CREATED","ROLE",id.toString(),"SUCCESS");
    return jdbc.queryForObject("select * from identity.role_summary where id=?",(r,n)->role(r),id);
  }

  // ---- E7: job queue + scheduler administration (Platform Admin) ----
  public record JobItem(UUID id,String tenantKey,String jobType,String status,int attempts,String leasedBy,Instant availableAt,String lastError,Instant createdAt){}
  public record ScheduleItem(UUID id,String tenantKey,String jobType,int intervalSeconds,int misfireGraceSeconds,boolean enabled,Instant lastFiredAt,Instant createdAt){}
  public record ScheduleCreate(@NotBlank String tenantKey,@NotBlank String jobType,String payload,@Min(1) int intervalSeconds,Integer misfireGraceSeconds){}

  @GetMapping("/jobs")
  java.util.List<JobItem> jobs(@RequestParam(defaultValue="") String status, Authentication auth) {
    requireAdmin(auth);
    var safe = status == null ? "" : status.replaceAll("[^A-Z_]", "");
    return safe.isBlank()
        ? jdbc.query("select * from async.job order by created_at desc limit 100",(r,n)->job(r))
        : jdbc.query("select * from async.job where status=? order by created_at desc limit 100",(r,n)->job(r),safe);
  }

  @PostMapping("/jobs/{id}/retry") @Transactional
  JobItem retryJob(@PathVariable UUID id,Authentication auth){
    requireAdmin(auth);
    jobs.requeue(id);
    audit(auth,"JOB_RETRIED","JOB",id.toString(),"SUCCESS");
    return jdbc.queryForObject("select * from async.job where id=?",(r,n)->job(r),id);
  }

  @PostMapping("/jobs/{id}/cancel") @Transactional
  JobItem cancelJob(@PathVariable UUID id,Authentication auth){
    requireAdmin(auth);
    jobs.cancel(id);
    audit(auth,"JOB_CANCELLED","JOB",id.toString(),"SUCCESS");
    return jdbc.queryForObject("select * from async.job where id=?",(r,n)->job(r),id);
  }

  @GetMapping("/job-schedules")
  java.util.List<ScheduleItem> schedules(Authentication auth) {
    requireAdmin(auth);
    return jdbc.query("select * from async.job_schedule order by created_at desc",(r,n)->schedule(r));
  }

  @PostMapping("/job-schedules") @ResponseStatus(HttpStatus.CREATED) @Transactional
  ScheduleItem createSchedule(@Valid @RequestBody ScheduleCreate request,Authentication auth){
    requireAdmin(auth);
    var id=UUID.randomUUID();
    var grace=request.misfireGraceSeconds()==null?60:request.misfireGraceSeconds();
    jdbc.update("insert into async.job_schedule(id,tenant_key,job_type,payload,interval_seconds,misfire_grace_seconds) values(?,?,?,?::jsonb,?,?)",
        id,request.tenantKey(),request.jobType(),request.payload()==null||request.payload().isBlank()?"{}":request.payload(),request.intervalSeconds(),grace);
    audit(auth,"JOB_SCHEDULE_CREATED","SCHEDULE",id.toString(),"SUCCESS");
    return jdbc.queryForObject("select * from async.job_schedule where id=?",(r,n)->schedule(r),id);
  }

  private JobItem job(java.sql.ResultSet r)throws java.sql.SQLException{
    return new JobItem(r.getObject("id",UUID.class),r.getString("tenant_key"),r.getString("job_type"),r.getString("status"),
        r.getInt("attempts"),r.getString("leased_by"),r.getTimestamp("available_at")==null?null:r.getTimestamp("available_at").toInstant(),
        r.getString("last_error"),r.getTimestamp("created_at").toInstant());
  }
  private ScheduleItem schedule(java.sql.ResultSet r)throws java.sql.SQLException{
    return new ScheduleItem(r.getObject("id",UUID.class),r.getString("tenant_key"),r.getString("job_type"),r.getInt("interval_seconds"),
        r.getInt("misfire_grace_seconds"),r.getBoolean("enabled"),r.getTimestamp("last_fired_at")==null?null:r.getTimestamp("last_fired_at").toInstant(),
        r.getTimestamp("created_at").toInstant());
  }

  @PutMapping("/settings") @Transactional
  Map<String,String> updateSettings(@Valid @RequestBody List<SettingUpdate> updates,Authentication auth){
    requireAdmin(auth);
    for(var item:updates) jdbc.update("insert into platform.setting(setting_key,setting_value,updated_by) values(?,?,?) on conflict(setting_key) do update set setting_value=excluded.setting_value,updated_by=excluded.updated_by,updated_at=now()",item.key(),item.value(),auth.getName());
    audit(auth,"SETTINGS_UPDATED","SETTING",null,"SUCCESS"); return settings();
  }

  // ---- E5: audit integrity (verify / checkpoint / retention / legal hold) ----
  public record AuditVerificationView(boolean verified, long checked, Long brokenAtSequence, String reason) {}
  public record CheckpointView(String tenantKey, long verifiedSequence, String chainHash) {}
  public record PurgeRequest(@NotBlank String tenantKey, @Min(1) int olderThanDays) {}
  public record LegalHoldRequest(@NotBlank String tenantKey, @NotBlank @Size(max=400) String reason) {}

  @GetMapping("/audit/verify")
  AuditVerificationView verifyAuditChain(@RequestParam(defaultValue="default") String tenantKey, Authentication auth) {
    requireAdmin(auth);
    var verification = audits.verify(tenantKey);
    return new AuditVerificationView(verification.verified(), verification.checked(), verification.brokenAtSequence(), verification.reason());
  }

  @PostMapping("/audit/checkpoint") @Transactional
  CheckpointView checkpointAuditChain(@RequestParam(defaultValue="default") String tenantKey, Authentication auth) {
    requireAdmin(auth);
    long sequence = audits.checkpoint(tenantKey);
    var chainHash = jdbc.queryForObject("select chain_hash from audit.checkpoint where tenant_key=?", String.class, tenantKey);
    audit(auth, "AUDIT_CHECKPOINT_CREATED", "AUDIT", tenantKey, "SUCCESS");
    return new CheckpointView(tenantKey, sequence, chainHash);
  }

  @PostMapping("/audit/purge") @Transactional
  Map<String, Object> purgeAudit(@Valid @RequestBody PurgeRequest request, Authentication auth) {
    requireAdmin(auth);
    long deleted = audits.purge(request.tenantKey(), request.olderThanDays());
    audits.record(request.tenantKey(), accountIdOf(auth), auth.getName(), "AUDIT_RETENTION_PURGED", "AUDIT", request.tenantKey(), "SUCCESS", "{\"deleted\":" + deleted + ",\"olderThanDays\":" + request.olderThanDays() + "}");
    return Map.of("deleted", deleted);
  }

  @PostMapping("/audit/legal-hold") @Transactional
  Map<String, Object> setLegalHold(@Valid @RequestBody LegalHoldRequest request, Authentication auth) {
    requireAdmin(auth);
    audits.setLegalHold(request.tenantKey(), request.reason(), auth.getName());
    audits.record(request.tenantKey(), accountIdOf(auth), auth.getName(), "AUDIT_LEGAL_HOLD_SET", "AUDIT", request.tenantKey(), "SUCCESS", "{\"reason\":\"" + request.reason().replace("\"", "'") + "\"}");
    return Map.of("tenantKey", request.tenantKey(), "held", true);
  }

  @DeleteMapping("/audit/legal-hold/{tenantKey}") @Transactional
  Map<String, Object> releaseLegalHold(@PathVariable String tenantKey, Authentication auth) {
    requireAdmin(auth);
    audits.releaseLegalHold(tenantKey);
    audits.record(tenantKey, accountIdOf(auth), auth.getName(), "AUDIT_LEGAL_HOLD_RELEASED", "AUDIT", tenantKey, "SUCCESS", null);
    return Map.of("tenantKey", tenantKey, "held", false);
  }

  // ---- E6: outbox operations ----
  public record OutboxItem(UUID id,String eventType,String tenantKey,String status,int attempts,Instant createdAt,Instant availableAt,String lastError) {}
  public record ReplayResult(UUID id, boolean replayed) {}

  @GetMapping("/outbox")
  List<OutboxItem> outbox(@RequestParam(defaultValue="all") String status, Authentication auth) {
    requireAdmin(auth);
    if ("all".equals(status))
      return jdbc.query("select id,event_type,tenant_key,status,attempts,created_at,available_at,last_error from async.outbox_event order by created_at desc limit 100",
        (r,n)->new OutboxItem(r.getObject("id",UUID.class),r.getString("event_type"),r.getString("tenant_key"),r.getString("status"),r.getInt("attempts"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("available_at").toInstant(),r.getString("last_error")));
    return jdbc.query("select id,event_type,tenant_key,status,attempts,created_at,available_at,last_error from async.outbox_event where status=? order by created_at desc limit 100",
      (r,n)->new OutboxItem(r.getObject("id",UUID.class),r.getString("event_type"),r.getString("tenant_key"),r.getString("status"),r.getInt("attempts"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("available_at").toInstant(),r.getString("last_error")),status);
  }

  @PostMapping("/outbox/{id}/replay") @Transactional
  ReplayResult replayOutboxEvent(@PathVariable UUID id, Authentication auth) {
    requireAdmin(auth);
    outbox.replay(id, auth.getName());
    audit(auth, "OUTBOX_REPLAYED", "OUTBOX", id.toString(), "SUCCESS");
    return new ReplayResult(id, true);
  }

  private List<Module> modules(){return jdbc.query("select * from platform.module order by sort_order",(r,n)->module(r));}
  private List<Resource> resources(){return jdbc.query("select * from platform.resource_descriptor order by updated_at desc",(r,n)->resource(r));}
  private List<Activity> activities(){return jdbc.query("select * from platform.activity order by occurred_at desc limit 50",(r,n)->activity(r));}
  private List<Role> roles(){return jdbc.query("select * from identity.role_summary order by name",(r,n)->role(r));}
  private List<FileItem> files(){return jdbc.query("select * from files.file_object where status in ('ACTIVE','QUARANTINED') order by updated_at desc",(r,n)->new FileItem(r.getObject("id",UUID.class),r.getString("name"),r.getString("media_type"),r.getLong("size_bytes"),r.getString("classification"),r.getString("status"),r.getTimestamp("updated_at").toInstant()));}
  private Map<String,String> settings(){var result=new LinkedHashMap<String,String>();jdbc.query("select setting_key,setting_value from platform.setting order by setting_key",r->{result.put(r.getString(1),r.getString(2));});return result;}
  private Module module(java.sql.ResultSet r)throws java.sql.SQLException{return new Module(r.getObject("id",UUID.class),r.getString("name"),r.getString("module_key"),r.getString("version"),r.getString("status"),r.getString("description"),r.getString("metric"));}
  private Resource resource(java.sql.ResultSet r)throws java.sql.SQLException{return new Resource(r.getObject("id",UUID.class),r.getString("name"),r.getString("storage_mode"),r.getString("owner_module"),r.getLong("record_count"),r.getString("schema_version"),r.getTimestamp("updated_at").toInstant());}
  private Activity activity(java.sql.ResultSet r)throws java.sql.SQLException{return new Activity(r.getObject("id",UUID.class),r.getString("kind"),r.getString("name"),r.getString("metadata"),r.getString("status"),r.getTimestamp("occurred_at").toInstant());}
  private Role role(java.sql.ResultSet r)throws java.sql.SQLException{return new Role(r.getObject("id",UUID.class),r.getString("name"),r.getInt("user_count"),r.getInt("policy_count"),r.getString("scope"));}
  private void requireAdmin(Authentication auth){if(auth==null||auth.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("ROLE_PLATFORM_ADMIN")))throw new ApiProblem(HttpStatus.FORBIDDEN,"PERMISSION_DENIED","Yêu cầu quyền Platform Administrator");}
  private void audit(Authentication auth,String action,String type,String resourceId,String result){ audits.record(tenantKeyOf(auth), accountIdOf(auth), auth.getName(), action, type, resourceId, result, null); }
  private String tenantKeyOf(Authentication auth){ var tenantId=((java.util.Map<?,?>)auth.getDetails()).get("tenantId"); return jdbc.queryForObject("select tenant_key from platform.tenant where id=?", String.class, tenantId); }
  private UUID accountIdOf(Authentication auth){ return (UUID)((java.util.Map<?,?>)auth.getDetails()).get("accountId"); }
}
