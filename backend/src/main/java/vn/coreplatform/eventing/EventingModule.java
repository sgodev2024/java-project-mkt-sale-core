package vn.coreplatform.eventing;

import java.util.List;
import org.springframework.stereotype.Component;
import vn.coreplatform.kernel.ModuleContributor;
import vn.coreplatform.kernel.ModuleDescriptor;

@Component
public class EventingModule implements ModuleContributor {
  @Override public ModuleDescriptor descriptor() {
    return new ModuleDescriptor("event-outbox", "Event Outbox", "1.0.0", List.of(),
        List.of("integration-events", "outbox", "inbox"), "Transactional outbox, relay lease/retry, inbox idempotent và DLQ replay");
  }
}
