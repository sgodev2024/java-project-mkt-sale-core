package vn.coreplatform.permission;

import static vn.coreplatform.shared.ApiExceptionHandler.ApiProblem;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/access")
public class AccessManagementController {
  private final JdbcTemplate jdbc;private final PasswordEncoder passwords;private final PermissionService permissions;private final vn.coreplatform.audit.AuditService audits;private final vn.coreplatform.kernel.ResourceRegistry resources;
  public AccessManagementController(JdbcTemplate jdbc,PasswordEncoder passwords,PermissionService permissions,vn.coreplatform.audit.AuditService audits,vn.coreplatform.kernel.ResourceRegistry resources){this.jdbc=jdbc;this.passwords=passwords;this.permissions=permissions;this.audits=audits;this.resources=resources;}
  public record UserItem(UUID id,String email,String displayName,boolean enabled,List<String> roles,Instant createdAt){}
  public record RoleItem(UUID id,String code,String name,boolean systemRole){}
  public record PolicyItem(UUID id,String code,String resourceType,String action,String effect,String condition,int version,boolean enabled){}
  public record UserCreate(@Email @NotBlank String email,@NotBlank @Size(max=160) String displayName,@Size(min=12,max=128) String password,@NotEmpty List<UUID> roleIds,UUID orgId){}
  public record RoleCreate(@Pattern(regexp="[a-z][a-z0-9-]{2,99}") String code,@NotBlank @Size(max=160) String name){}
  public record PolicyCreate(@Pattern(regexp="[a-z][a-z0-9-]{2,119}") String code,@NotBlank String resourceType,@NotBlank String action,@Pattern(regexp="ALLOW|DENY") String effect,String condition){}
  public record Assignment(@NotEmpty List<UUID> ids){}
  public record TenantItem(UUID id,String key,String name,String status,Instant createdAt){}
  public record TenantCreate(@Pattern(regexp="[a-z][a-z0-9-]{1,79}") String key,@NotBlank @Size(max=160) String name){}
  public record OrganizationItem(UUID id,String code,String name,String parentCode,String status,Instant createdAt){}
  public record OrganizationCreate(@Pattern(regexp="[a-z][a-z0-9-]{1,79}") String code,@NotBlank @Size(max=160) String name,String parentCode){}
  public record TempPassword(String tempPassword, boolean mustChangePassword){}
  public record ServiceAccountCreate(@NotBlank @Size(max=160) String name,UUID orgId){}
  public record ServiceAccountItem(UUID id,String name,String prefix,String accountEmail,String status,Instant lastUsedAt,Instant createdAt){}
  public record ApiKeyIssued(UUID id,String name,String apiKey,String notice){}

  @GetMapping("/users") List<UserItem> users(Authentication a){manage(a);var t=permissions.tenant(a);return jdbc.query("select a.id,a.email,a.display_name,a.enabled,a.created_at,coalesce(string_agg(r.code,',' order by r.code),'') roles from identity.account a left join identity.account_role ar on ar.tenant_id=a.tenant_id and ar.account_id=a.id left join identity.role r on r.id=ar.role_id where a.tenant_id=? group by a.id order by a.email",(r,n)->new UserItem(r.getObject("id",UUID.class),r.getString("email"),r.getString("display_name"),r.getBoolean("enabled"),r.getString("roles").isBlank()?List.of():List.of(r.getString("roles").split(",")),r.getTimestamp("created_at").toInstant()),t);}
  @GetMapping("/roles") List<RoleItem> roles(Authentication a){manage(a);return jdbc.query("select id,code,name,system_role from identity.role where tenant_id=? order by name",(r,n)->new RoleItem(r.getObject("id",UUID.class),r.getString("code"),r.getString("name"),r.getBoolean("system_role")),permissions.tenant(a));}
  @GetMapping("/policies") List<PolicyItem> policies(Authentication a){manage(a);return jdbc.query("select * from identity.policy where tenant_id=? order by code,version desc",(r,n)->new PolicyItem(r.getObject("id",UUID.class),r.getString("code"),r.getString("resource_type"),r.getString("action"),r.getString("effect"),r.getString("condition_json"),r.getInt("version"),r.getBoolean("enabled")),permissions.tenant(a));}
  @PostMapping("/users") @ResponseStatus(HttpStatus.CREATED) @Transactional UserItem createUser(@Valid @RequestBody UserCreate x,Authentication a){manage(a);var t=permissions.tenant(a);var id=UUID.randomUUID();try{jdbc.update("insert into identity.account(id,tenant_id,email,display_name,password_hash,password_algo,role,org_id) values(?,?,?,?,?,'ARGON2ID','APPLICATION_USER',?)",id,t,x.email().trim().toLowerCase(),x.displayName().trim(),passwords.encode(x.password()),x.orgId());for(var role:x.roleIds()){int c=jdbc.update("insert into identity.account_role(tenant_id,account_id,role_id) select ?,?,id from identity.role where id=? and tenant_id=?",t,id,role,t);if(c==0)throw new ApiProblem(HttpStatus.BAD_REQUEST,"INVALID_ROLE","Role không thuộc tenant");}}catch(ApiProblem e){throw e;}catch(Exception e){throw new ApiProblem(HttpStatus.CONFLICT,"USER_EXISTS","Email đã tồn tại hoặc org không hợp lệ");}revision(t);audit(a,"USER_CREATED",id);return users(a).stream().filter(u->u.id().equals(id)).findFirst().orElseThrow();}

  @PostMapping("/users/{id}/reset-password") @Transactional TempPassword resetPassword(@PathVariable UUID id,Authentication a){
    manage(a);var t=permissions.tenant(a);
    var temp="Cp-"+UUID.randomUUID().toString().replace("-","").substring(0,14);
    int c=jdbc.update("update identity.account set password_hash=?,password_algo='ARGON2ID',must_change_password=true,password_changed_at=now(),failed_attempts=0,locked_until=null where id=? and tenant_id=? and account_type='HUMAN'",passwords.encode(temp),id,t);
    if(c==0)throw new ApiProblem(HttpStatus.NOT_FOUND,"USER_NOT_FOUND","User không tồn tại");
    jdbc.update("update identity.session set revoked_at=now() where account_id=? and revoked_at is null",id);
    audit(a,"USER_PASSWORD_RESET",id);
    return new TempPassword(temp,true);
  }
  @PatchMapping("/users/{id}/enabled") @Transactional void enabled(@PathVariable UUID id,@RequestBody Map<String,Boolean> body,Authentication a){manage(a);if(id.equals(permissions.account(a))&&!body.getOrDefault("enabled",true))throw new ApiProblem(HttpStatus.CONFLICT,"SELF_DISABLE","Không thể tự vô hiệu hóa tài khoản");int c=jdbc.update("update identity.account set enabled=? where id=? and tenant_id=?",body.getOrDefault("enabled",true),id,permissions.tenant(a));if(c==0)throw new ApiProblem(HttpStatus.NOT_FOUND,"USER_NOT_FOUND","User không tồn tại");jdbc.update("update identity.session set revoked_at=now() where account_id=? and revoked_at is null",id);audit(a,"USER_STATUS_CHANGED",id);}
  @PostMapping("/roles") @ResponseStatus(HttpStatus.CREATED) @Transactional RoleItem createRole(@Valid @RequestBody RoleCreate x,Authentication a){manage(a);var id=UUID.randomUUID();var t=permissions.tenant(a);try{jdbc.update("insert into identity.role(id,tenant_id,code,name) values(?,?,?,?)",id,t,x.code(),x.name());}catch(Exception e){throw new ApiProblem(HttpStatus.CONFLICT,"ROLE_EXISTS","Role code đã tồn tại");}revision(t);audit(a,"ROLE_CREATED",id);return new RoleItem(id,x.code(),x.name(),false);}
  @PostMapping("/policies") @ResponseStatus(HttpStatus.CREATED) @Transactional PolicyItem createPolicy(@Valid @RequestBody PolicyCreate x,Authentication a){manage(a);var id=UUID.randomUUID();var t=permissions.tenant(a);String condition=Optional.ofNullable(x.condition()).filter(s->!s.isBlank()).orElse("{}");try{new com.fasterxml.jackson.databind.ObjectMapper().readTree(condition);jdbc.update("insert into identity.policy(id,tenant_id,code,resource_type,action,effect,condition_json) values(?,?,?,?,?,?,?::jsonb)",id,t,x.code(),x.resourceType(),x.action(),x.effect(),condition);}catch(Exception e){throw new ApiProblem(HttpStatus.BAD_REQUEST,"INVALID_POLICY","Policy trùng hoặc condition JSON không hợp lệ");}revision(t);audit(a,"POLICY_CREATED",id);return policies(a).stream().filter(p->p.id().equals(id)).findFirst().orElseThrow();}
  @PutMapping("/roles/{roleId}/policies") @Transactional void bind(@PathVariable UUID roleId,@Valid @RequestBody Assignment x,Authentication a){manage(a);var t=permissions.tenant(a);if(jdbc.queryForObject("select count(*) from identity.role where id=? and tenant_id=?",Integer.class,roleId,t)==0)throw new ApiProblem(HttpStatus.NOT_FOUND,"ROLE_NOT_FOUND","Role không tồn tại");jdbc.update("delete from identity.role_policy where tenant_id=? and role_id=?",t,roleId);for(var id:x.ids()){int c=jdbc.update("insert into identity.role_policy(tenant_id,role_id,policy_id) select ?,?,id from identity.policy where id=? and tenant_id=?",t,roleId,id,t);if(c==0)throw new ApiProblem(HttpStatus.BAD_REQUEST,"INVALID_POLICY","Policy không thuộc tenant");}revision(t);audit(a,"ROLE_POLICIES_CHANGED",roleId);}

  // ---- E3-S01: tenant lifecycle (deployment-level, chỉ Platform Admin) ----
  @GetMapping("/tenants") List<TenantItem> tenants(Authentication a){platformAdmin(a);return jdbc.query("select id,tenant_key,name,status,created_at from platform.tenant order by tenant_key",(r,n)->new TenantItem(r.getObject("id",UUID.class),r.getString("tenant_key"),r.getString("name"),r.getString("status"),r.getTimestamp("created_at").toInstant()));}
  @PostMapping("/tenants") @ResponseStatus(HttpStatus.CREATED) @Transactional TenantItem createTenant(@Valid @RequestBody TenantCreate x,Authentication a){
    platformAdmin(a);var id=UUID.randomUUID();
    try{jdbc.update("insert into platform.tenant(id,tenant_key,name) values(?,?,?)",id,x.key(),x.name().trim());}
    catch(Exception e){throw new ApiProblem(HttpStatus.CONFLICT,"TENANT_EXISTS","Tenant key đã tồn tại");}
    jdbc.update("insert into identity.permission_revision(tenant_id) values(?) on conflict do nothing",id);
    jdbc.update("insert into identity.role(tenant_id,code,name,system_role) select id,'application-user','Application User',false from platform.tenant where id=? on conflict do nothing",id);
    jdbc.update("insert into identity.policy(tenant_id,code,resource_type,action,effect,condition_json) select id,'dynamic-record-owner','DYNAMIC_RECORD','*','ALLOW','{\"ownerOnly\":true}'::jsonb from platform.tenant where id=? on conflict do nothing",id);
    jdbc.update("insert into identity.policy(tenant_id,code,resource_type,action,effect,condition_json) select id,'file-owner','FILE','*','ALLOW','{\"ownerOnly\":true}'::jsonb from platform.tenant where id=? on conflict do nothing",id);
    jdbc.update("insert into identity.role_policy(tenant_id,role_id,policy_id) select r.tenant_id,r.id,p.id from identity.role r join identity.policy p on p.tenant_id=r.tenant_id where r.tenant_id=? and r.code='application-user' and p.code in ('dynamic-record-owner','file-owner') on conflict do nothing",id);
    audit(a,"TENANT_CREATED",id);
    return jdbc.queryForObject("select id,tenant_key,name,status,created_at from platform.tenant where id=?",(r,n)->new TenantItem(r.getObject("id",UUID.class),r.getString("tenant_key"),r.getString("name"),r.getString("status"),r.getTimestamp("created_at").toInstant()),id);
  }

  // ---- E3-S01: organization trong tenant của caller ----
  @GetMapping("/organizations") List<OrganizationItem> organizations(Authentication a){manage(a);var t=permissions.tenant(a);
    return jdbc.query("""
        select o.id,o.code,o.name,p.code parent_code,o.status,o.created_at from identity.organization o
        left join identity.organization p on p.id=o.parent_id where o.tenant_id=? order by o.code
        """,(r,n)->new OrganizationItem(r.getObject("id",UUID.class),r.getString("code"),r.getString("name"),r.getString("parent_code"),r.getString("status"),r.getTimestamp("created_at").toInstant()),t);}
  @PostMapping("/organizations") @ResponseStatus(HttpStatus.CREATED) @Transactional OrganizationItem createOrganization(@Valid @RequestBody OrganizationCreate x,Authentication a){
    manage(a);var t=permissions.tenant(a);var id=UUID.randomUUID();
    UUID parent=null;
    if(x.parentCode()!=null&&!x.parentCode().isBlank()){
      parent=jdbc.queryForList("select id from identity.organization where tenant_id=? and code=?",UUID.class,t,x.parentCode()).stream().findFirst()
        .orElseThrow(()->new ApiProblem(HttpStatus.BAD_REQUEST,"PARENT_NOT_FOUND","Org cha không tồn tại trong tenant của bạn — không thể dùng org thuộc tenant khác"));
    }
    try{jdbc.update("insert into identity.organization(id,tenant_id,parent_id,code,name) values(?,?,?,?,?)",id,t,parent,x.code(),x.name().trim());}
    catch(Exception e){throw new ApiProblem(HttpStatus.CONFLICT,"ORGANIZATION_EXISTS","Mã org đã tồn tại trong tenant");}
    audit(a,"ORGANIZATION_CREATED",id);
    return new OrganizationItem(id,x.code(),x.name().trim(),x.parentCode(),"ACTIVE",Instant.now());
  }

  // ---- E3-S05: service account + API key ----
  @GetMapping("/service-accounts") List<ServiceAccountItem> serviceAccounts(Authentication a){manage(a);var t=permissions.tenant(a);
    return jdbc.query("""
        select k.id,k.name,k.prefix,k.status,k.last_used_at,k.created_at,ac.email from identity.api_key k
        join identity.account ac on ac.id=k.account_id where k.tenant_id=? order by k.created_at desc
        """,(r,n)->new ServiceAccountItem(r.getObject("id",UUID.class),r.getString("name"),r.getString("prefix"),r.getString("email"),r.getString("status"),
        r.getTimestamp("last_used_at")==null?null:r.getTimestamp("last_used_at").toInstant(),r.getTimestamp("created_at").toInstant()),t);}
  @PostMapping("/service-accounts") @ResponseStatus(HttpStatus.CREATED) @Transactional ApiKeyIssued createServiceAccount(@Valid @RequestBody ServiceAccountCreate x,Authentication a){
    manage(a);var t=permissions.tenant(a);
    var account=UUID.randomUUID();var email="svc-"+UUID.randomUUID().toString().substring(0,8)+"@service.local";
    jdbc.update("insert into identity.account(id,tenant_id,email,display_name,password_hash,password_algo,role,account_type,org_id) values(?,?,?,?,?,'ARGON2ID','SERVICE','SERVICE',?)",
      account,t,email,x.name().trim(),passwords.encode(UUID.randomUUID()+"Cp9"),x.orgId());
    var issued=issueApiKey(t,account,x.name().trim());
    resources.adjustRecordCount("service-account",1);
    revision(t);audit(a,"SERVICE_ACCOUNT_CREATED",account);
    return issued;
  }
  @PostMapping("/service-accounts/{id}/rotate") @Transactional ApiKeyIssued rotateServiceAccount(@PathVariable UUID id,Authentication a){
    manage(a);var t=permissions.tenant(a);
    var key=jdbc.query("select k.account_id,k.name from identity.api_key k where k.id=? and k.tenant_id=? and k.status='ACTIVE'",
      (r,n)->Map.of("account",r.getObject("account_id",UUID.class),"name",r.getString("name")),id,t);
    if(key.isEmpty())throw new ApiProblem(HttpStatus.NOT_FOUND,"API_KEY_NOT_FOUND","API key không tồn tại hoặc đã bị thay");
    jdbc.update("update identity.api_key set status='ROTATED' where id=?",id);
    var issued=issueApiKey(t,(UUID)key.getFirst().get("account"),(String)key.getFirst().get("name"));
    audit(a,"SERVICE_ACCOUNT_ROTATED",id);
    return issued;
  }
  @PostMapping("/service-accounts/{id}/revoke") @Transactional void revokeServiceAccount(@PathVariable UUID id,Authentication a){
    manage(a);var t=permissions.tenant(a);
    int c=jdbc.update("update identity.api_key set status='REVOKED' where id=? and tenant_id=? and status='ACTIVE'",id,t);
    if(c==0)throw new ApiProblem(HttpStatus.NOT_FOUND,"API_KEY_NOT_FOUND","API key không tồn tại hoặc đã bị thay");
    jdbc.update("update identity.account set enabled=false where id=(select account_id from identity.api_key where id=?)",id);
    resources.adjustRecordCount("service-account",-1);
    audit(a,"SERVICE_ACCOUNT_REVOKED",id);
  }
  private ApiKeyIssued issueApiKey(UUID tenant,UUID account,String name){
    var prefix=vn.coreplatform.security.SecurityConfig.sha256(UUID.randomUUID().toString()).substring(0,8);
    var secret=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]).replace("-","A").replace("_","B");
    var apiKey="cpa_"+prefix+"_"+secret; var id=UUID.randomUUID();
    jdbc.update("insert into identity.api_key(id,tenant_id,account_id,name,prefix,key_hash) values(?,?,?,?,?,?)",id,tenant,account,name,prefix,vn.coreplatform.security.SecurityConfig.sha256(apiKey));
    return new ApiKeyIssued(id,name,apiKey,"API key chỉ hiển thị một lần duy nhất; server chỉ lưu hash SHA-256");
  }
  private void manage(Authentication a){permissions.require(a,"ACCESS_ADMIN","MANAGE",null);}
  private void platformAdmin(Authentication a){if(a==null||a.getAuthorities().stream().noneMatch(x->x.getAuthority().equals("ROLE_PLATFORM_ADMIN")))throw new ApiProblem(HttpStatus.FORBIDDEN,"PERMISSION_DENIED","Yêu cầu quyền Platform Administrator");}
  private void revision(UUID t){jdbc.update("update identity.permission_revision set revision=revision+1,updated_at=now() where tenant_id=?",t);}
  private void audit(Authentication a,String action,UUID id){jdbc.update("insert into audit.event(id,actor_id,actor_email,tenant_key,action,resource_type,resource_id,result,correlation_id,occurred_at) values(?,?,?,?,?,'ACCESS',?,'SUCCESS',?,now())",UUID.randomUUID(),permissions.account(a),a.getName(),permissions.tenant(a).toString(),action,id.toString(),vn.coreplatform.shared.CorrelationIdFilter.current());}
}
