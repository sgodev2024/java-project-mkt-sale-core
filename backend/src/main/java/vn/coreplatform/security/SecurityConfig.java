package vn.coreplatform.security;

import jakarta.servlet.*; import jakarta.servlet.http.*;
import java.io.IOException; import java.nio.charset.StandardCharsets; import java.security.MessageDigest; import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*; import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.stereotype.Component; import org.springframework.web.cors.*;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {
  /**
   * E3-S02: encode mặc định Argon2id; hash {bcrypt} cũ vẫn verify được và được rehash
   * khi đăng nhập thành công (xử lý trong AuthController).
   */
  @Bean PasswordEncoder passwordEncoder(){
    var encoders = new LinkedHashMap<String, PasswordEncoder>();
    encoders.put("argon2", Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8());
    encoders.put("bcrypt", new BCryptPasswordEncoder(12));
    return new DelegatingPasswordEncoder("argon2", encoders);
  }
  @Bean CorsConfigurationSource corsConfigurationSource(
      @Value("${core.cors.allowed-origin-patterns}") List<String> allowedOriginPatterns) {
    var configuration = new CorsConfiguration();
    configuration.setAllowedOriginPatterns(allowedOriginPatterns);
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Correlation-Id"));
    configuration.setAllowCredentials(false);
    var source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }
  @Bean SecurityFilterChain security(HttpSecurity http, TokenFilter tokenFilter) throws Exception { return http.csrf(x->x.disable()).cors(x->{}).sessionManagement(x->x.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).authorizeHttpRequests(x->x.requestMatchers("/api/v1/auth/login","/api/v1/auth/mfa","/api/v1/auth/refresh","/actuator/health/**","/v3/api-docs/**","/swagger-ui/**").permitAll().anyRequest().authenticated()).addFilterBefore(tokenFilter, UsernamePasswordAuthenticationFilter.class).build(); }

  @Component static class TokenFilter extends OncePerRequestFilter {
    private final JdbcTemplate jdbc; TokenFilter(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{
      var header=req.getHeader(HttpHeaders.AUTHORIZATION);
      if(header!=null&&header.startsWith("Bearer ")){
        var token=header.substring(7);
        if(token.startsWith("cpa_")) authenticateApiKey(token);
        else authenticateSession(token);
      }
      var auth=SecurityContextHolder.getContext().getAuthentication();
      // E3-S02: mật khẩu tạm (admin reset) chỉ cho phép đổi mật khẩu, chặn mọi thao tác khác
      if(auth!=null&&auth.getDetails() instanceof Map<?,?> details&&Boolean.TRUE.equals(details.get("mustChange"))&&!req.getRequestURI().startsWith("/api/v1/auth/")){
        res.setStatus(HttpServletResponse.SC_FORBIDDEN);res.setContentType("application/problem+json");
        res.getWriter().write("{\"code\":\"PASSWORD_CHANGE_REQUIRED\",\"detail\":\"Cần đổi mật khẩu trước khi tiếp tục\",\"correlationId\":\""+vn.coreplatform.shared.CorrelationIdFilter.current()+"\"}");
        return;
      }
      chain.doFilter(req,res);
    }
    private void authenticateSession(String token){
      var hash=sha256(token);
      jdbc.query("""
          select a.id,a.tenant_id,a.email,a.display_name,a.role,a.account_type,a.must_change_password from identity.session s
          join identity.account a on a.id=s.account_id
          where s.token_hash=? and s.revoked_at is null and s.expires_at>now() and a.enabled=true
          """,rs->{ if(rs.next()){
            var service="SERVICE".equals(rs.getString("account_type"));
            var authorities=service?List.of(new SimpleGrantedAuthority("ROLE_SERVICE")):List.of(new SimpleGrantedAuthority("ROLE_"+rs.getString("role")));
            var auth=new UsernamePasswordAuthenticationToken(rs.getString("email"),null,authorities);
            auth.setDetails(new java.util.HashMap<>(Map.of("accountId",rs.getObject("id"),"tenantId",rs.getObject("tenant_id"),"displayName",rs.getString("display_name"),"mustChange",rs.getBoolean("must_change_password"))));
            SecurityContextHolder.getContext().setAuthentication(auth);
          } return null;},hash);
    }
    private void authenticateApiKey(String token){
      var parts=token.split("_");
      if(parts.length!=3) return;
      var hash=sha256(token);
      jdbc.query("""
          select a.id,a.tenant_id,a.email,a.display_name from identity.api_key k
          join identity.account a on a.id=k.account_id
          where k.prefix=? and k.key_hash=? and k.status='ACTIVE' and a.enabled=true and a.account_type='SERVICE'
            and (k.expires_at is null or k.expires_at>now())
          """,rs->{ if(rs.next()){
            var auth=new UsernamePasswordAuthenticationToken(rs.getString("email"),null,List.of(new SimpleGrantedAuthority("ROLE_SERVICE")));
            auth.setDetails(new java.util.HashMap<>(Map.of("accountId",rs.getObject("id"),"tenantId",rs.getObject("tenant_id"),"displayName",rs.getString("display_name"),"mustChange",false)));
            SecurityContextHolder.getContext().setAuthentication(auth);
            jdbc.update("update identity.api_key set last_used_at=now() where prefix=?",parts[1]);
          } return null;},parts[1],hash);
    }
  }
  public static String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
