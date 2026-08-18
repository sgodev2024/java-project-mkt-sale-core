package vn.coreplatform.identity;

import java.util.List;
import org.springframework.stereotype.Component;
import vn.coreplatform.kernel.ModuleContributor;
import vn.coreplatform.kernel.ModuleDescriptor;

@Component
public class IdentityModule implements ModuleContributor {
  @Override public ModuleDescriptor descriptor() {
    return new ModuleDescriptor("local-identity", "Local Identity", "1.0.0", List.of(),
        List.of("authentication", "session", "mfa"), "Tài khoản nội bộ, phiên đăng nhập và MFA");
  }
}
