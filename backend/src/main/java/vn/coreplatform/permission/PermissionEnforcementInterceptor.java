package vn.coreplatform.permission;

import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import vn.coreplatform.shared.CorrelationIdFilter;

/**
 * PEP (E4-S03): mọi endpoint gắn @RequirePermission đều qua PDP trước khi vào controller.
 * Deny trả 403 kèm correlation id; decision Deny do policy lỗi cũng đi vào đây (fail-closed).
 */
@Component
public class PermissionEnforcementInterceptor implements HandlerInterceptor {
  private final PermissionService permissions;
  public PermissionEnforcementInterceptor(PermissionService permissions){this.permissions=permissions;}

  @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
    if (!(handler instanceof HandlerMethod method)) return true;
    var required = method.getMethodAnnotation(RequirePermission.class);
    if (required == null) return true;
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    // gate thô dùng scope(): policy ownerOnly được coi là allow + thu hẹp phạm vi,
    // controller vẫn tự kiểm tra mức record (decide với ownerId cụ thể).
    var decision = permissions.scope(authentication, required.resource(), required.action());
    if (decision.allowed()) return true;
    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    response.setContentType("application/problem+json");
    response.getWriter().write("{\"code\":\"PERMISSION_DENIED\",\"detail\":\"PEP chặn " + required.action() + " trên " + required.resource()
        + " (" + decision.reason() + ")\",\"correlationId\":\"" + CorrelationIdFilter.current() + "\"}");
    return false;
  }

  @Component
  static class Registration implements WebMvcConfigurer {
    private final PermissionEnforcementInterceptor interceptor;
    Registration(PermissionEnforcementInterceptor interceptor){this.interceptor=interceptor;}
    @Override public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(interceptor).addPathPatterns("/api/v1/**");}
  }
}
