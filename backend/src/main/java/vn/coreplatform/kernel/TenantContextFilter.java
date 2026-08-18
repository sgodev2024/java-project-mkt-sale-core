package vn.coreplatform.kernel;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gắn tenant của phiên đăng nhập vào TenantContext cho toàn bộ request (E2-S02).
 * Chạy sau security chain nên chỉ thấy tenant của session đã xác thực; request chưa xác thực
 * không có tenant — RLS policy bên dưới trả 0 dòng cho tenant null (fail-closed).
 */
@Component
public class TenantContextFilter extends OncePerRequestFilter {
  @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
    try {
      var authentication = SecurityContextHolder.getContext().getAuthentication();
      if (authentication != null && authentication.getDetails() instanceof Map<?, ?> details && details.get("tenantId") instanceof UUID tenantId)
        TenantContext.set(tenantId);
      chain.doFilter(request, response);
    } finally {
      TenantContext.clear();
    }
  }
}
