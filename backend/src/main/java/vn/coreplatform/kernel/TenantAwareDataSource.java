package vn.coreplatform.kernel;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/**
 * Decorate mọi DataSource: khi lease connection mà request có TenantContext, đặt
 * `core.tenant_id` (session GUC) để RLS policy lọc theo tenant; khi connection trả về pool,
 * reset GUC về rỗng để connection tái sử dụng không rò rỉ tenant của request trước (E2-S02).
 */
public class TenantAwareDataSource implements DataSource {
  static final String TENANT_GUC = "core.tenant_id";
  private final DataSource delegate;

  TenantAwareDataSource(DataSource delegate) { this.delegate = delegate; }

  @Override public Connection getConnection() throws SQLException { return decorate(delegate.getConnection()); }
  @Override public Connection getConnection(String username, String password) throws SQLException { return decorate(delegate.getConnection(username, password)); }
  @Override public <T> T unwrap(Class<T> iface) throws SQLException { return iface.isInstance(this) ? iface.cast(this) : delegate.unwrap(iface); }
  @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return iface.isInstance(this) || delegate.isWrapperFor(iface); }
  @Override public java.io.PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
  @Override public void setLogWriter(java.io.PrintWriter out) throws SQLException { delegate.setLogWriter(out); }
  @Override public void setLoginTimeout(int seconds) throws SQLException { delegate.setLoginTimeout(seconds); }
  @Override public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
  @Override public java.util.logging.Logger getParentLogger() throws java.sql.SQLFeatureNotSupportedException { return delegate.getParentLogger(); }

  private Connection decorate(Connection raw) throws SQLException {
    var tenant = TenantContext.current();
    if (tenant != null && !raw.isClosed()) setTenantGuc(raw, tenant);
    return (Connection) Proxy.newProxyInstance(TenantAwareDataSource.class.getClassLoader(), new Class<?>[]{Connection.class}, new ResetOnCloseHandler(raw));
  }

  private static void setTenantGuc(Connection connection, UUID tenantId) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("select set_config('" + TENANT_GUC + "', " + (tenantId == null ? "''" : "'" + tenantId + "'") + ", false)");
    }
  }

  private record ResetOnCloseHandler(Connection raw) implements InvocationHandler {
    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      if ("close".equals(method.getName()) && (args == null || args.length == 0)) {
        try { if (!raw.isClosed()) setTenantGuc(raw, null); }
        catch (Exception ignored) { /* connection đã hỏng thì việc reset không còn ý nghĩa */ }
      }
      if ("equals".equals(method.getName())) return proxy == args[0];
      if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
      if ("toString".equals(method.getName())) return "TenantAwareConnection[" + raw + "]";
      if ("unwrap".equals(method.getName())) return raw.unwrap((Class<?>) args[0]);
      if ("isWrapperFor".equals(method.getName())) return raw.isWrapperFor((Class<?>) args[0]);
      return method.invoke(raw, args);
    }
  }

  @Component
  static class Registration implements BeanPostProcessor {
    @Override public Object postProcessAfterInitialization(Object bean, String beanName) {
      if (bean instanceof DataSource dataSource && !(bean instanceof TenantAwareDataSource)) return new TenantAwareDataSource(dataSource);
      return bean;
    }
  }
}
