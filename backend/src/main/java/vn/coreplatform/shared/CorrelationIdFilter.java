package vn.coreplatform.shared;

import jakarta.servlet.*; import jakarta.servlet.http.*;
import java.io.IOException; import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered; import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component; import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {
  public static final String HEADER="X-Correlation-Id"; private static final String MDC_KEY="correlationId";
  @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{
    var id=normalize(req.getHeader(HEADER)); req.setAttribute(HEADER,id); res.setHeader(HEADER,id); MDC.put(MDC_KEY,id);
    try{chain.doFilter(req,res);}finally{MDC.remove(MDC_KEY);}
  }
  static String normalize(String value){return value!=null&&value.matches("[A-Za-z0-9._-]{8,64}")?value:UUID.randomUUID().toString();}
  public static UUID current(){var value=MDC.get(MDC_KEY);try{return UUID.fromString(value);}catch(Exception e){return UUID.randomUUID();}}
}
