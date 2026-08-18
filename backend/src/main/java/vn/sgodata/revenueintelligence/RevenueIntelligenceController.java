package vn.sgodata.revenueintelligence;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vn.coreplatform.permission.PermissionService;
import vn.coreplatform.permission.RequirePermission;
import vn.coreplatform.shared.ApiExceptionHandler.ApiProblem;

@RestController
@RequestMapping("/api/v1/revenue-intelligence")
public class RevenueIntelligenceController {
  private final RevenueImportService imports;
  private final RevenueAnalyticsService analytics;
  private final PermissionService permissions;

  public RevenueIntelligenceController(RevenueImportService imports, RevenueAnalyticsService analytics, PermissionService permissions) {
    this.imports = imports; this.analytics = analytics; this.permissions = permissions;
  }

  @RequirePermission(resource="REVENUE_IMPORT", action="CREATE")
  @PostMapping(value="/imports/{dataset}", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
  RevenueImportService.ImportResult importCsv(@PathVariable String dataset, @RequestParam("file") MultipartFile file, Authentication auth) {
    permissions.require(auth, "REVENUE_IMPORT", "CREATE", null);
    if (file.isEmpty()) throw new ApiProblem(HttpStatus.BAD_REQUEST, "FILE_EMPTY", "Tệp CSV rỗng");
    try {
      return imports.importCsv(RevenueImportService.Dataset.fromPath(dataset), file.getOriginalFilename(), file.getBytes(),
          permissions.tenant(auth), permissions.account(auth), auth.getName(), permissions.tenantKey(auth));
    } catch (IOException error) {
      throw new ApiProblem(HttpStatus.BAD_REQUEST, "FILE_READ_FAILED", "Không đọc được tệp CSV");
    }
  }

  @RequirePermission(resource="REVENUE_IMPORT", action="READ")
  @GetMapping("/imports")
  List<RevenueImportService.ImportBatch> batches(Authentication auth) {
    permissions.require(auth, "REVENUE_IMPORT", "READ", null);
    return imports.batches(permissions.tenant(auth));
  }

  @RequirePermission(resource="REVENUE_IMPORT", action="READ")
  @GetMapping("/imports/{id}/errors")
  List<RevenueImportService.ImportError> errors(@PathVariable UUID id, Authentication auth) {
    permissions.require(auth, "REVENUE_IMPORT", "READ", null);
    return imports.errors(permissions.tenant(auth), id);
  }

  @RequirePermission(resource="REVENUE_ANALYTICS", action="READ")
  @GetMapping("/reconciliation")
  RevenueAnalyticsService.Reconciliation reconciliation(@RequestParam(required=false) LocalDate from, @RequestParam(required=false) LocalDate to, Authentication auth) {
    var period = period(from, to);
    permissions.require(auth, "REVENUE_ANALYTICS", "READ", null);
    return analytics.reconciliation(permissions.tenant(auth), period[0], period[1]);
  }

  @RequirePermission(resource="REVENUE_CUSTOMER", action="READ")
  @GetMapping("/customers")
  List<RevenueAnalyticsService.CustomerSummary> customers(Authentication auth) {
    permissions.require(auth, "REVENUE_CUSTOMER", "READ", null);
    return analytics.customers(permissions.tenant(auth));
  }

  @RequirePermission(resource="REVENUE_ATTRIBUTION", action="UPDATE")
  @PostMapping("/attribution/rebuild")
  RevenueAnalyticsService.RebuildResult rebuild(@RequestParam(required=false) LocalDate from, @RequestParam(required=false) LocalDate to, Authentication auth) {
    var period = period(from, to);
    permissions.require(auth, "REVENUE_ATTRIBUTION", "UPDATE", null);
    return analytics.rebuild(permissions.tenant(auth), period[0], period[1], permissions.account(auth), auth.getName(), permissions.tenantKey(auth));
  }

  @RequirePermission(resource="REVENUE_ANALYTICS", action="READ")
  @GetMapping("/dashboard")
  RevenueAnalyticsService.Dashboard dashboard(@RequestParam(required=false) LocalDate from, @RequestParam(required=false) LocalDate to, Authentication auth) {
    var period = period(from, to);
    permissions.require(auth, "REVENUE_ANALYTICS", "READ", null);
    return analytics.dashboard(permissions.tenant(auth), period[0], period[1]);
  }

  private static LocalDate[] period(LocalDate from, LocalDate to) {
    var resolvedTo = to == null ? LocalDate.now(RevenueAnalyticsServiceTestClock.ZONE) : to;
    var resolvedFrom = from == null ? resolvedTo.minusDays(89) : from;
    return new LocalDate[]{resolvedFrom, resolvedTo};
  }

  /** Package-visible clock constants keep controller defaults and tests deterministic by timezone. */
  static final class RevenueAnalyticsServiceTestClock {
    static final java.time.ZoneId ZONE = java.time.ZoneId.of("Asia/Ho_Chi_Minh");
    private RevenueAnalyticsServiceTestClock() {}
  }
}
