package vn.coreplatform.identity;

import jakarta.validation.Valid; import jakarta.validation.constraints.*;
import java.security.SecureRandom; import java.sql.Timestamp; import java.time.*; import java.util.*;
import org.springframework.http.*; import org.springframework.jdbc.core.JdbcTemplate; import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional; import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import vn.coreplatform.security.SecurityConfig; import vn.coreplatform.shared.ApiExceptionHandler.ApiProblem; import vn.coreplatform.shared.CorrelationIdFilter;

@RestController @RequestMapping("/api/v1/auth")
public class AuthController {
  private final JdbcTemplate jdbc; private final PasswordEncoder encoder; private final vn.coreplatform.audit.AuditService audits; private final SecureRandom random=new SecureRandom();
  private final String bootstrapMfaCode; private final boolean mfaEnabled; private final boolean allowBootstrapMfa; private final boolean requireAdminMfa;
  static final int MAX_FAILED_ATTEMPTS = 5;
  public AuthController(JdbcTemplate jdbc,PasswordEncoder encoder,vn.coreplatform.audit.AuditService audits,
                        @Value("${core.bootstrap-mfa-code:}") String bootstrapMfaCode,
                        @Value("${core.mfa.enabled:true}") boolean mfaEnabled,
                        @Value("${core.mfa.allow-bootstrap:false}") boolean allowBootstrapMfa,
                        @Value("${core.mfa.require-admin:true}") boolean requireAdminMfa){
    this.jdbc=jdbc;this.encoder=encoder;this.audits=audits;this.bootstrapMfaCode=bootstrapMfaCode;this.mfaEnabled=mfaEnabled;this.allowBootstrapMfa=allowBootstrapMfa;this.requireAdminMfa=requireAdminMfa;
  }
  record LoginRequest(@Email @NotBlank String email,@NotBlank @Size(min=8,max=128) String password,boolean remember){}
  record LoginResponse(String challengeId,boolean mfaRequired,String maskedDestination,SessionResponse session){}
  record MfaRequest(@NotBlank String challengeId,@Pattern(regexp="[A-Za-z0-9]{6,12}") String code,boolean remember){}
  record SessionResponse(String accessToken,String refreshToken,Instant expiresAt,UserResponse user){}
  record UserResponse(UUID id,String email,String displayName,String role){}
  record RefreshRequest(@NotBlank String refreshToken){}
  record ChangePasswordRequest(@NotBlank String currentPassword,@NotBlank @Size(min=12,max=128) String newPassword){}
  record ConfirmRequest(@Pattern(regexp="\\d{6}") String code){}
  record EnrollmentResponse(String secret,String otpauthUri){}
  record ConfirmResponse(List<String> recoveryCodes){}

  @PostMapping("/login")
  LoginResponse login(@Valid @RequestBody LoginRequest input){
    var rows=jdbc.query("select id,email,display_name,password_hash,password_algo,enabled,account_type,role,failed_attempts,locked_until from identity.account where lower(email)=lower(?)",
      (rs,n)->{var row=new java.util.HashMap<String,Object>();row.put("id",rs.getObject("id",UUID.class));row.put("hash",rs.getString("password_hash"));
        row.put("email",rs.getString("email"));row.put("displayName",rs.getString("display_name"));
        row.put("algo",rs.getString("password_algo"));row.put("enabled",rs.getBoolean("enabled"));row.put("type",rs.getString("account_type"));
        row.put("role",rs.getString("role"));row.put("failed",rs.getInt("failed_attempts"));row.put("lockedUntil",rs.getTimestamp("locked_until"));return row;},input.email());
    if(!rows.isEmpty()&&"SERVICE".equals(rows.getFirst().get("type"))){
      auditFailure(input.email(),"SERVICE_LOGIN_BLOCKED"); throw new ApiProblem(HttpStatus.FORBIDDEN,"SERVICE_ACCOUNT_LOGIN_FORBIDDEN","Tài khoản service không được đăng nhập như người dùng");
    }
    if(rows.isEmpty()||!encoder.matches(input.password(),(String)rows.getFirst().get("hash"))){
      if(!rows.isEmpty()) registerFailedAttempt((UUID)rows.getFirst().get("id"),(Integer)rows.getFirst().get("failed"));
      auditFailure(input.email(),"AUTH_LOGIN_CHALLENGE"); throw new ApiProblem(HttpStatus.UNAUTHORIZED,"INVALID_CREDENTIALS","Email hoặc mật khẩu không chính xác");
    }
    var account=rows.getFirst();
    if(!((Boolean)account.get("enabled"))) throw new ApiProblem(HttpStatus.FORBIDDEN,"ACCOUNT_DISABLED","Tài khoản đã bị vô hiệu hóa");
    var lockedUntil=(Timestamp)account.get("lockedUntil");
    if(lockedUntil!=null&&lockedUntil.toInstant().isAfter(Instant.now()))
      throw new ApiProblem(HttpStatus.FORBIDDEN,"ACCOUNT_LOCKED","Tài khoản tạm khóa do nhiều lần đăng nhập sai, thử lại sau 15 phút");
    var id=(UUID)account.get("id");
    if((Integer)account.get("failed")>0) jdbc.update("update identity.account set failed_attempts=0,locked_until=null where id=?",id);
    // E3-S02: hash cũ (bcrypt) được nâng cấp lên Argon2id ngay khi đăng nhập thành công
    if(!"{argon2}".regionMatches(true,0,(String)account.get("hash"),0,7))
      jdbc.update("update identity.account set password_hash=?,password_algo='ARGON2ID',password_changed_at=now() where id=?",encoder.encode(input.password()),id);
    if(!mfaEnabled){
      var user=new UserResponse(id,(String)account.get("email"),(String)account.get("displayName"),(String)account.get("role"));
      audit(id,user.email(),"AUTH_MFA_SKIPPED_BY_CONFIGURATION","SUCCESS");
      return new LoginResponse(null,false,null,issueSession(user,input.remember(),null));
    }
    if(requireAdminMfa&&"PLATFORM_ADMIN".equals(account.get("role"))&&adminEnrollmentMissing(id)&&!allowBootstrapMfa)
      throw new ApiProblem(HttpStatus.FORBIDDEN,"MFA_ENROLLMENT_REQUIRED","Administrator phải kích hoạt MFA trước khi đăng nhập; liên hệ quản trị để cấp recovery");
    var challenge=UUID.randomUUID(); jdbc.update("insert into identity.mfa_challenge(id,account_id,expires_at) values(?,?,now()+interval '5 minutes')",challenge,id);
    audit(id,input.email(),"AUTH_LOGIN_CHALLENGE","SUCCESS"); return new LoginResponse(challenge.toString(),true,"Authenticator app",null);
  }

  @PostMapping("/mfa")
  SessionResponse mfa(@Valid @RequestBody MfaRequest input){
    var account=jdbc.query("select a.id,a.email,a.display_name,a.role,a.account_type from identity.mfa_challenge c join identity.account a on a.id=c.account_id where c.id=? and c.used_at is null and c.expires_at>now()",
      (rs,n)->new UserResponse(rs.getObject("id",UUID.class),rs.getString("email"),rs.getString("display_name"),rs.getString("role")),UUID.fromString(input.challengeId()));
    if(account.isEmpty()) throw new ApiProblem(HttpStatus.UNAUTHORIZED,"INVALID_MFA_CODE","Mã xác thực không hợp lệ hoặc đã hết hạn");
    var user=account.getFirst();
    var enrollment=jdbc.query("select secret_base32,confirmed_at,recovery_code_hashes from identity.mfa_enrollment where account_id=? and confirmed_at is not null",
      (rs,n)->Map.of("secret",rs.getString("secret_base32"),"recovery",(String[])rs.getArray("recovery_code_hashes").getArray()),user.id());
    boolean passed;
    if(!enrollment.isEmpty()){
      var secret=(String)enrollment.getFirst().get("secret");
      passed=Totp.verify(secret,input.code(),1);
      if(!passed&&input.code().length()>=8) passed=consumeRecoveryCode(user.id(),input.code(),(String[])enrollment.getFirst().get("recovery"));
    } else {
      // E3-S04: mã bootstrap từ env chỉ hợp lệ khi cấu hình cho phép (demo/dev); production mặc định fail-closed
      passed=allowBootstrapMfa&&!bootstrapMfaCode.isBlank()&&bootstrapMfaCode.equals(input.code());
    }
    if(!passed){ audit(user.id(),user.email(),"AUTH_MFA","FAILED"); throw new ApiProblem(HttpStatus.UNAUTHORIZED,"INVALID_MFA_CODE","Mã xác thực không hợp lệ hoặc đã hết hạn"); }
    jdbc.update("update identity.mfa_challenge set used_at=now() where id=?",UUID.fromString(input.challengeId()));
    return issueSession(user,input.remember(),null);
  }

  @PostMapping("/refresh")
  SessionResponse refresh(@Valid @RequestBody RefreshRequest input){
    var hash=SecurityConfig.sha256(input.refreshToken());
    var rows=jdbc.query("""
        select r.id refresh_id, r.session_id, r.used_at, r.revoked_at, r.expires_at, s.family_id, a.id account_id, a.email, a.display_name, a.role, a.enabled
        from identity.refresh_token r join identity.session s on s.id=r.session_id join identity.account a on a.id=s.account_id
        where r.token_hash=?""",(rs,n)->{var row=new java.util.HashMap<String,Object>();
          row.put("refreshId",rs.getObject("refresh_id",UUID.class));row.put("sessionId",rs.getObject("session_id",UUID.class));
          row.put("used",rs.getTimestamp("used_at"));row.put("revoked",rs.getTimestamp("revoked_at"));row.put("expires",rs.getTimestamp("expires_at"));
          row.put("family",rs.getObject("family_id",UUID.class));row.put("user",new UserResponse(rs.getObject("account_id",UUID.class),rs.getString("email"),rs.getString("display_name"),rs.getString("role")));
          row.put("enabled",rs.getBoolean("enabled"));return row;},hash);
    if(rows.isEmpty()||!((Boolean)rows.getFirst().get("enabled"))||((Timestamp)rows.getFirst().get("revoked"))!=null
        ||((Timestamp)rows.getFirst().get("expires")).toInstant().isBefore(Instant.now()))
      throw new ApiProblem(HttpStatus.UNAUTHORIZED,"INVALID_REFRESH_TOKEN","Refresh token không hợp lệ hoặc đã hết hạn");
    var row=rows.getFirst();
    if(((Timestamp)row.get("used"))!=null){
      // E3-S03: token đã dùng lại -> thu hồi toàn bộ family và ghi security audit
      var family=(UUID)row.get("family");
      jdbc.update("update identity.session set revoked_at=now() where family_id=? and revoked_at is null",family);
      jdbc.update("update identity.refresh_token set revoked_at=now() where session_id in (select id from identity.session where family_id=?) and revoked_at is null",family);
      var user=(UserResponse)row.get("user");
      audit(user.id(),user.email(),"AUTH_REFRESH_REUSE_DETECTED","FAILED");
      throw new ApiProblem(HttpStatus.UNAUTHORIZED,"REFRESH_TOKEN_REUSE","Refresh token đã được dùng — toàn bộ phiên đăng nhập bị thu hồi");
    }
    var user=(UserResponse)row.get("user");
    jdbc.update("update identity.refresh_token set used_at=now() where id=?",row.get("refreshId"));
    audit(user.id(),user.email(),"AUTH_REFRESH_ROTATED","SUCCESS");
    return issueSession(user,false,(UUID)row.get("sessionId"));
  }

  @GetMapping("/me") UserResponse me(Authentication auth){ return jdbc.queryForObject("select id,email,display_name,role from identity.account where email=?",(rs,n)->new UserResponse(rs.getObject("id",UUID.class),rs.getString("email"),rs.getString("display_name"),rs.getString("role")),auth.getName()); }

  @PostMapping("/logout") @ResponseStatus(HttpStatus.NO_CONTENT) @Transactional
  void logout(@RequestHeader(HttpHeaders.AUTHORIZATION) String bearer,Authentication auth){
    var hash=SecurityConfig.sha256(bearer.substring(7));
    var family=jdbc.queryForList("select family_id from identity.session where token_hash=?",String.class,hash);
    if(family.isEmpty()) { jdbc.update("update identity.session set revoked_at=now() where token_hash=?",hash); }
    else {
      jdbc.update("update identity.session set revoked_at=now() where family_id=? and revoked_at is null",UUID.fromString(family.getFirst()));
      jdbc.update("update identity.refresh_token set revoked_at=now() where session_id in (select id from identity.session where family_id=?) and revoked_at is null",UUID.fromString(family.getFirst()));
    }
    audit(null, auth.getName(), "AUTH_LOGOUT", "SUCCESS");
  }

  @PostMapping("/change-password") @Transactional
  void changePassword(@Valid @RequestBody ChangePasswordRequest input,@RequestHeader(HttpHeaders.AUTHORIZATION) String bearer,Authentication auth){
    var rows=jdbc.query("select id,password_hash from identity.account where email=?",(rs,n)->Map.of("id",rs.getObject("id",UUID.class),"hash",rs.getString("password_hash")),auth.getName());
    if(rows.isEmpty()||!encoder.matches(input.currentPassword(),(String)rows.getFirst().get("hash")))
      throw new ApiProblem(HttpStatus.BAD_REQUEST,"INVALID_CURRENT_PASSWORD","Mật khẩu hiện tại không đúng");
    if(!input.newPassword().matches(".*[A-Za-z].*")||!input.newPassword().matches(".*\\d.*"))
      throw new ApiProblem(HttpStatus.BAD_REQUEST,"WEAK_PASSWORD","Mật khẩu mới phải chứa cả chữ và số, tối thiểu 12 ký tự");
    var id=(UUID)rows.getFirst().get("id");
    jdbc.update("update identity.account set password_hash=?,password_algo='ARGON2ID',password_changed_at=now(),must_change_password=false where id=?",encoder.encode(input.newPassword()),id);
    jdbc.update("update identity.session set revoked_at=now() where account_id=? and revoked_at is null and token_hash<>?",id,SecurityConfig.sha256(bearer.substring(7)));
    audit(id, auth.getName(), "AUTH_PASSWORD_CHANGED", "SUCCESS");
  }

  @PostMapping("/mfa/enroll") @Transactional
  EnrollmentResponse enroll(Authentication auth){
    var id=accountId(auth);
    var secret=Totp.generateSecret();
    jdbc.update("insert into identity.mfa_enrollment(account_id,tenant_id,secret_base32,recovery_code_hashes) values(?,?,?,'{}'::text[]) " +
      "on conflict (account_id) do update set secret_base32=excluded.secret_base32, confirmed_at=null, recovery_code_hashes='{}'::text[]",
      id,tenantId(auth),secret);
    var uri="otpauth://totp/CorePlatform:"+auth.getName()+"?secret="+secret+"&issuer=CorePlatform&algorithm=SHA1&digits=6&period=30";
    return new EnrollmentResponse(secret,uri);
  }

  @PostMapping("/mfa/confirm") @Transactional
  ConfirmResponse confirm(@Valid @RequestBody ConfirmRequest input,Authentication auth){
    var id=accountId(auth);
    var secrets=jdbc.queryForList("select secret_base32 from identity.mfa_enrollment where account_id=? and confirmed_at is null",String.class,id);
    if(secrets.isEmpty()||!Totp.verify(secrets.getFirst(),input.code(),1))
      throw new ApiProblem(HttpStatus.BAD_REQUEST,"MFA_CONFIRM_FAILED","Mã xác thực không khớp, thử lại");
    var codes=new ArrayList<String>(); var hashes=new ArrayList<String>();
    for(int i=0;i<8;i++){ var code=randomCode(); codes.add(code); hashes.add(SecurityConfig.sha256(code)); }
    jdbc.update("update identity.mfa_enrollment set confirmed_at=now(),recovery_code_hashes=?::text[] where account_id=?",textArray(hashes),id);
    audit(id, auth.getName(), "AUTH_MFA_ENROLLED", "SUCCESS");
    return new ConfirmResponse(codes);
  }

  private SessionResponse issueSession(UserResponse user,boolean remember,UUID rotatedFrom){
    var accessToken=newToken(); var refreshToken=newToken();
    var accessExpiry=Instant.now().plus(remember?Duration.ofDays(7):Duration.ofHours(8));
    var family=rotatedFrom==null?UUID.randomUUID():jdbc.queryForObject("select family_id from identity.session where id=?",UUID.class,rotatedFrom);
    if(rotatedFrom!=null) jdbc.update("update identity.session set revoked_at=now() where id=?",rotatedFrom);
    var sessionId=UUID.randomUUID();
    jdbc.update("insert into identity.session(id,account_id,token_hash,expires_at,family_id,rotated_from) values(?,?,?,?,?,?)",
      sessionId,user.id(),SecurityConfig.sha256(accessToken),Timestamp.from(accessExpiry),family,rotatedFrom);
    jdbc.update("insert into identity.refresh_token(session_id,token_hash,expires_at) values(?,?,?)",
      sessionId,SecurityConfig.sha256(refreshToken),Timestamp.from(accessExpiry));
    audit(user.id(),user.email(),"AUTH_LOGIN","SUCCESS");
    return new SessionResponse(accessToken,refreshToken,accessExpiry,user);
  }

  private boolean consumeRecoveryCode(UUID accountId,String code,String[] hashes){
    for(var hash:hashes) if(SecurityConfig.sha256(code).equals(hash)){
      var remaining=new ArrayList<>(Arrays.asList(hashes)); remaining.remove(hash);
      jdbc.update("update identity.mfa_enrollment set recovery_code_hashes=?::text[] where account_id=?",textArray(remaining),accountId);
      audit(accountId,null,"AUTH_MFA_RECOVERY_USED","SUCCESS");
      return true;
    }
    return false;
  }
  private String textArray(List<String> values){ return "{"+String.join(",",values)+"}"; }
  private boolean adminEnrollmentMissing(UUID accountId){
    return jdbc.queryForObject("select count(*) from identity.mfa_enrollment where account_id=? and confirmed_at is not null",Integer.class,accountId)==0;
  }
  private void registerFailedAttempt(UUID accountId,int previousFailures){
    var attempts=previousFailures+1;
    if(attempts>=MAX_FAILED_ATTEMPTS) jdbc.update("update identity.account set failed_attempts=?,locked_until=now()+interval '15 minutes' where id=?",attempts,accountId);
    else jdbc.update("update identity.account set failed_attempts=? where id=?",attempts,accountId);
  }
  private UUID accountId(Authentication auth){ return jdbc.queryForObject("select id from identity.account where email=?",UUID.class,auth.getName()); }
  private UUID tenantId(Authentication auth){ return jdbc.queryForObject("select tenant_id from identity.account where email=?",UUID.class,auth.getName()); }
  private String randomCode(){ var bytes=new byte[8]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).replace('-','A').replace('_','B').substring(0,10).toUpperCase(Locale.ROOT); }
  private String tenantKeyOf(UUID actorId,String email){
    if(actorId!=null){var keys=jdbc.queryForList("select t.tenant_key from identity.account a join platform.tenant t on t.id=a.tenant_id where a.id=?",String.class,actorId);if(!keys.isEmpty())return keys.getFirst();}
    if(email!=null){var keys=jdbc.queryForList("select t.tenant_key from identity.account a join platform.tenant t on t.id=a.tenant_id where lower(a.email)=lower(?)",String.class,email);if(!keys.isEmpty())return keys.getFirst();}
    return null;
  }
  private String newToken(){ var bytes=new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
  private void audit(UUID actor,String email,String action,String result){ audits.record(tenantKeyOf(actor,email), actor, email, action, null, null, result, null); }
  private void auditFailure(String email,String action){ audits.record(tenantKeyOf(null,email), null, email, action, null, null, "FAILED", null); }
}
