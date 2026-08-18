package vn.coreplatform.kernel;

import java.util.UUID;

/** Tenant của request hiện tại (E2-S02). Null khi chưa xác thực — mọi consumer phải fail closed. */
public final class TenantContext {
  private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();
  private TenantContext() {}
  public static void set(UUID tenantId) { CURRENT.set(tenantId); }
  public static UUID current() { return CURRENT.get(); }
  public static void clear() { CURRENT.remove(); }
}
