package vn.coreplatform.webhook;

import java.util.List;
import org.springframework.stereotype.Component;
import vn.coreplatform.kernel.ModuleContributor;
import vn.coreplatform.kernel.ModuleDescriptor;

@Component
public class WebhookModule implements ModuleContributor {
  @Override public ModuleDescriptor descriptor() {
    return new ModuleDescriptor("webhook", "Webhook", "1.0.0", List.of("event-outbox"),
        List.of("webhook-delivery"), "Webhook endpoint với SSRF guard — chỉ trỏ tới public https");
  }
}
