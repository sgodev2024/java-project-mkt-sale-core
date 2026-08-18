package vn.sgodata.revenueintelligence;

import java.util.List;
import org.springframework.stereotype.Component;
import vn.coreplatform.kernel.ModuleContributor;
import vn.coreplatform.kernel.ModuleDescriptor;
import vn.coreplatform.kernel.NavigationItemDescriptor;

@Component
public class RevenueIntelligenceModule implements ModuleContributor {
  @Override public ModuleDescriptor descriptor() {
    return new ModuleDescriptor(
        "revenue-intelligence", "Revenue Intelligence", "1.0.0",
        List.of("kernel", "permission", "audit-store", "event-outbox"),
        List.of("revenue-import", "customer-identity", "revenue-attribution", "revenue-dashboard"),
        "Đo lường nguồn khách hàng, cơ cấu doanh thu, mua lại và hiệu quả quảng cáo",
        ">=1.1.0 <2.0.0");
  }

  @Override public List<NavigationItemDescriptor> navigationItems() {
    return List.of(
        new NavigationItemDescriptor(
            "module.revenue-intelligence.analytics", "business", "", "Marketing & Doanh thu",
            "navigation.revenueIntelligence", "chart", "", "", 30, "", "", "",
            List.of("marketing", "doanh thu", "khách hàng", "quảng cáo")),
        new NavigationItemDescriptor(
            "module.revenue-intelligence.dashboard", "business", "module.revenue-intelligence.analytics",
            "Hiệu quả kinh doanh", "navigation.revenueDashboard", "chart", "revenue-intelligence",
            "/business/revenue-intelligence", 31, "", "REVENUE_ANALYTICS", "READ",
            List.of("roas", "mer", "attribution", "mua lại", "bán buôn", "bán lẻ")));
  }
}
