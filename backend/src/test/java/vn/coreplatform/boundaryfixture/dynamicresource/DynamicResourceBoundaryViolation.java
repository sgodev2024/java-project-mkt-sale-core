package vn.coreplatform.boundaryfixture.dynamicresource;

/** Fixture vi phạm boundary CỐ Ý (E1-S02): dynamicresource không được chạm identity. */
public class DynamicResourceBoundaryViolation {
  private final vn.coreplatform.identity.AuthController forbidden;

  public DynamicResourceBoundaryViolation(vn.coreplatform.identity.AuthController forbidden) { this.forbidden = forbidden; }

  @SuppressWarnings("unused") Object access() { return forbidden.getClass(); }
}
