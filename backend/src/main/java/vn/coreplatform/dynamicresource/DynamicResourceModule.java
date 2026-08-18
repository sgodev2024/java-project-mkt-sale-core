package vn.coreplatform.dynamicresource;

import java.util.List;
import org.springframework.stereotype.Component;
import vn.coreplatform.kernel.ModuleContributor;
import vn.coreplatform.kernel.ModuleDescriptor;

@Component
public class DynamicResourceModule implements ModuleContributor {
  @Override public ModuleDescriptor descriptor() {
    return new ModuleDescriptor("dynamic-resource", "Dynamic Resource", "1.0.0", List.of("permission"),
        List.of("dynamic-crud", "csv-io"), "Definition, generic CRUD, history và CSV cho dynamic resource");
  }
}
