package vn.coreplatform.webhook;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import vn.coreplatform.audit.AuditService;
import vn.coreplatform.permission.PermissionService;

@RestController @RequestMapping("/api/v1/webhooks")
public class WebhookController {
  private final WebhookService webhooks;
  private final PermissionService permissions;
  private final AuditService audits;

  public WebhookController(WebhookService webhooks, PermissionService permissions, AuditService audits) {
    this.webhooks = webhooks; this.permissions = permissions; this.audits = audits;
  }

  @GetMapping
  java.util.List<WebhookService.EndpointItem> list(Authentication auth) {
    permissions.require(auth, "WEBHOOK", "READ", null);
    return webhooks.listEndpoints(permissions.tenant(auth));
  }

  @PostMapping @ResponseStatus(HttpStatus.CREATED) @Transactional
  WebhookService.EndpointItem create(@org.springframework.validation.annotation.Validated @RequestBody WebhookService.EndpointCreate request, Authentication auth) {
    permissions.require(auth, "WEBHOOK", "CREATE", null);
    var id = webhooks.createEndpoint(permissions.tenant(auth), request.url(), request.eventTypes());
    audits.record(permissions.tenantKey(auth), permissions.account(auth), auth.getName(), "WEBHOOK_CREATED", "WEBHOOK", id.toString(), "SUCCESS",
        "{\"url\":\"" + request.url() + "\"}");
    return webhooks.listEndpoints(permissions.tenant(auth)).stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
  }

  @PostMapping("/{id}/disable") @Transactional
  void disable(@PathVariable UUID id, Authentication auth) {
    permissions.require(auth, "WEBHOOK", "DELETE", null);
    webhooks.disable(permissions.tenant(auth), id);
    audits.record(permissions.tenantKey(auth), permissions.account(auth), auth.getName(), "WEBHOOK_DISABLED", "WEBHOOK", id.toString(), "SUCCESS", null);
  }
}
