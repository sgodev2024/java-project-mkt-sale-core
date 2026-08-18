package vn.coreplatform.jobs;

import java.util.List;
import org.springframework.stereotype.Component;
import vn.coreplatform.kernel.ModuleContributor;
import vn.coreplatform.kernel.ModuleDescriptor;

@Component
public class JobsModule implements ModuleContributor {
  @Override public ModuleDescriptor descriptor() {
    return new ModuleDescriptor("job-queue", "Job Queue", "1.0.0", List.of(),
        List.of("background-jobs", "scheduler"), "Job queue lease/heartbeat, retry/backoff, DLQ và scheduler leader election");
  }
}
