package vn.coreplatform.permission;

import static vn.coreplatform.shared.ApiExceptionHandler.ApiProblem;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import vn.coreplatform.shared.CorrelationIdFilter;

/**
 * PDP (E4-S02, E4-S03). Quyết định được cache theo (permission revision, tenant, account,
 * resource, action) — revision đổi (role/policy/binding thay đổi) thì cache tự vô hiệu.
 * Fail-closed: policy lỗi cú pháp, thiếu revision row hay auth details hỏng đều trả Deny
 * kèm security audit, không bao giờ 500 hay Allow.
 */
@Service
public class PermissionService {
  private final JdbcTemplate jdbc; private final vn.coreplatform.audit.AuditService audits; public PermissionService(JdbcTemplate jdbc,vn.coreplatform.audit.AuditService audits){this.jdbc=jdbc;this.audits=audits;}
  public record Decision(boolean allowed,String reason,boolean ownerOnly){}

  private record CacheKey(long revision,UUID tenant,UUID account,String resource,String action,boolean exact){}
  private record PolicyRow(String effect,String conditionJson){}
  private final Map<CacheKey, List<PolicyRow>> policyCache = new ConcurrentHashMap<>();

  public Decision decide(Authentication auth,String resource,String action,UUID ownerId){
    try{
      var tenant=tenant(auth);var account=account(auth);
      var rows=policies(tenant,account,resource,action);
      boolean allow=false,ownerOnly=false;
      for(var p:rows){JsonNode condition=read(p.conditionJson());boolean owner=condition.path("ownerOnly").asBoolean(false);boolean matches=!owner||Objects.equals(ownerId,account);if(matches&&"DENY".equals(p.effect()))return new Decision(false,"EXPLICIT_DENY",owner);if(matches&&"ALLOW".equals(p.effect())){allow=true;ownerOnly|=owner;}}
      return new Decision(allow,allow?"POLICY_ALLOW":"NO_MATCHING_POLICY",ownerOnly);
    }catch(Exception e){return evaluationDenied(e);}
  }
  public void require(Authentication auth,String resource,String action,UUID ownerId){var d=decide(auth,resource,action,ownerId);if(!d.allowed())throw new ApiProblem(HttpStatus.FORBIDDEN,"PERMISSION_DENIED","Không có quyền "+action+" trên "+resource);}
  public Decision scope(Authentication auth,String resource,String action){
    try{
      var tenant=tenant(auth);var account=account(auth);
      var rows=policies(tenant,account,resource,action);
      boolean allow=false,ownerOnly=false;for(var p:rows){boolean owner=read(p.conditionJson()).path("ownerOnly").asBoolean(false);if("DENY".equals(p.effect())&&!owner)return new Decision(false,"EXPLICIT_DENY",false);if("ALLOW".equals(p.effect())){allow=true;ownerOnly|=owner;}}
      return new Decision(allow,allow?"POLICY_ALLOW":"NO_MATCHING_POLICY",ownerOnly);
    }catch(Exception e){return evaluationDenied(e);}
  }

  /**
   * Capability gate cho task/assignment. Khác scope thông thường, wildcard * / * không
   * được tính là nhiệm vụ được giao; tài khoản phải có policy đúng resource/action.
   */
  public Decision scopeExplicit(Authentication auth,String resource,String action){
    try{
      var tenant=tenant(auth);var account=account(auth);
      var rows=policiesExact(tenant,account,resource,action);
      boolean allow=false,ownerOnly=false;
      for(var p:rows){boolean owner=read(p.conditionJson()).path("ownerOnly").asBoolean(false);if("DENY".equals(p.effect())&&!owner)return new Decision(false,"EXPLICIT_DENY",false);if("ALLOW".equals(p.effect())){allow=true;ownerOnly|=owner;}}
      return new Decision(allow,allow?"EXPLICIT_POLICY_ALLOW":"NO_EXPLICIT_POLICY",ownerOnly);
    }catch(Exception e){return evaluationDenied(e);}
  }

  private List<PolicyRow> policies(UUID tenant,UUID account,String resource,String action){
    var revision=revision(tenant);
    var key=new CacheKey(revision,tenant,account,resource,action,false);
    var cached=policyCache.get(key);
    if(cached!=null)return cached;
    var rows=jdbc.query("""
        select p.effect,p.condition_json from identity.account_role ar
        join identity.role_policy rp on rp.tenant_id=ar.tenant_id and rp.role_id=ar.role_id
        join identity.policy p on p.tenant_id=rp.tenant_id and p.id=rp.policy_id
        where ar.tenant_id=? and ar.account_id=? and p.enabled=true
          and (p.resource_type='*' or p.resource_type=?) and (p.action='*' or p.action=?)
        """,(r,n)->new PolicyRow(r.getString(1),r.getString(2)),tenant,account,resource,action);
    policyCache.put(key,rows);
    return rows;
  }

  private List<PolicyRow> policiesExact(UUID tenant,UUID account,String resource,String action){
    var revision=revision(tenant);
    var key=new CacheKey(revision,tenant,account,resource,action,true);
    var cached=policyCache.get(key);
    if(cached!=null)return cached;
    var rows=jdbc.query("""
        select p.effect,p.condition_json from identity.account_role ar
        join identity.role_policy rp on rp.tenant_id=ar.tenant_id and rp.role_id=ar.role_id
        join identity.policy p on p.tenant_id=rp.tenant_id and p.id=rp.policy_id
        where ar.tenant_id=? and ar.account_id=? and p.enabled=true
          and p.resource_type=? and p.action=?
        """,(r,n)->new PolicyRow(r.getString(1),r.getString(2)),tenant,account,resource,action);
    policyCache.put(key,rows);
    return rows;
  }

  private long revision(UUID tenant){
    Long value=jdbc.queryForObject("select revision from identity.permission_revision where tenant_id=?",Long.class,tenant);
    return value==null?0L:value;
  }

  private Decision evaluationDenied(Exception cause){
    String tenantKey=null;
    try{ var auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
      if(auth!=null&&auth.getDetails() instanceof Map<?,?> details&&details.get("tenantId") instanceof UUID tenantId)
        tenantKey=jdbc.queryForObject("select tenant_key from platform.tenant where id=?",String.class,tenantId);
    }catch(Exception ignored){}
    audits.record(tenantKey,null,null,"POLICY_EVALUATION_ERROR","PERMISSION",null,"FAILED",null);
    return new Decision(false,"POLICY_EVALUATION_ERROR",false);
  }
  public String tenantKey(Authentication a){ return jdbc.queryForObject("select tenant_key from platform.tenant where id=?", String.class, tenant(a)); }
  @SuppressWarnings("unchecked") public UUID tenant(Authentication a){return (UUID)((Map<String,Object>)a.getDetails()).get("tenantId");}
  @SuppressWarnings("unchecked") public UUID account(Authentication a){return (UUID)((Map<String,Object>)a.getDetails()).get("accountId");}
  private JsonNode read(String value){try{return new com.fasterxml.jackson.databind.ObjectMapper().readTree(value);}catch(Exception e){throw new IllegalArgumentException("condition_json không hợp lệ",e);}}
}
