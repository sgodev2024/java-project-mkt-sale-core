package vn.coreplatform.filemanagement;

import java.util.List;
import org.springframework.stereotype.Component;
import vn.coreplatform.kernel.ModuleContributor;
import vn.coreplatform.kernel.ModuleDescriptor;

@Component
public class FileManagementModule implements ModuleContributor {
  @Override public ModuleDescriptor descriptor() {
    return new ModuleDescriptor("file-management", "File Management", "1.0.0", List.of("permission"),
        List.of("file-storage"), "Upload, download, soft delete và checksum cho file object");
  }
}
