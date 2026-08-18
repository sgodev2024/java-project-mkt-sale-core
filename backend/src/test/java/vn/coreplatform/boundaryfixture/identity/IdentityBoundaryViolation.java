package vn.coreplatform.boundaryfixture.identity;

/** Fixture vi phạm boundary CỐ Ý (E1-S02): identity không được chạm dynamicresource. */
public class IdentityBoundaryViolation {
  private final vn.coreplatform.dynamicresource.DynamicResourceController forbidden;

  public IdentityBoundaryViolation(vn.coreplatform.dynamicresource.DynamicResourceController forbidden) { this.forbidden = forbidden; }

  @SuppressWarnings("unused") Object access() { return forbidden.getClass(); }
}
