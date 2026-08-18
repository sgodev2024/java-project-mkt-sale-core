package vn.coreplatform.navigation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import vn.coreplatform.audit.AuditService;
import vn.coreplatform.kernel.NavigationRegistry;
import vn.coreplatform.permission.PermissionService;

/**
 * Navigation API cho một application shell thống nhất. Backend lọc section/item theo
 * module status, authority và PDP; frontend không tự suy diễn quyền từ menu.
 */
@RestController
@RequestMapping("/api/v1/navigation")
public class NavigationController {
  private final NavigationRegistry registry;
  private final PermissionService permissions;
  private final NavigationVisibilityPolicy visibility;
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final AuditService audits;

  public NavigationController(NavigationRegistry registry, PermissionService permissions,
                              NavigationVisibilityPolicy visibility, JdbcTemplate jdbc,
                              ObjectMapper json, AuditService audits) {
    this.registry = registry;
    this.permissions = permissions;
    this.visibility = visibility;
    this.jdbc = jdbc;
    this.json = json;
    this.audits = audits;
  }

  public record NavigationItemView(String key, String parentKey, String ownerModule, String label, String labelKey,
                                   String icon, String type, String viewKey, String route, int sortOrder,
                                   List<String> keywords) {}
  public record SectionView(String key, String label, String labelKey, String icon, int sortOrder,
                            List<NavigationItemView> items) {}
  public record NavigationResponse(String revision, List<SectionView> sections, List<String> favoriteKeys,
                                   List<String> recentKeys) {}
  public record PreferencesUpdate(
      @Size(max = 20) List<@Pattern(regexp = "(?:core|module|customer)\\.[a-z0-9][a-z0-9.-]{1,159}") String> favoriteKeys,
      @Size(max = 10) List<@Pattern(regexp = "(?:core|module|customer)\\.[a-z0-9][a-z0-9.-]{1,159}") String> recentKeys) {}
  private record Preference(List<String> favorites, List<String> recents) {}
  private record Effective(List<SectionView> sections, Set<String> itemKeys) {}

  @GetMapping("/me")
  NavigationResponse mine(Authentication auth) {
    return response(effective(auth), preference(auth));
  }

  @PutMapping("/me/preferences")
  @Transactional
  NavigationResponse updatePreferences(@Valid @RequestBody PreferencesUpdate request, Authentication auth) {
    var effective = effective(auth);
    var previous = preference(auth);
    var favorites = request.favoriteKeys() == null ? previous.favorites()
        : allowedDistinct(request.favoriteKeys(), effective.itemKeys(), 20);
    var recents = request.recentKeys() == null ? previous.recents()
        : allowedDistinct(request.recentKeys(), effective.itemKeys(), 10);
    jdbc.update("""
        insert into platform.navigation_preference(tenant_id,account_id,favorite_keys,recent_keys,last_workspace_key,updated_at)
        values (?,?,?::jsonb,?::jsonb,'',now())
        on conflict(tenant_id,account_id) do update set favorite_keys=excluded.favorite_keys,
          recent_keys=excluded.recent_keys,last_workspace_key='',updated_at=now()
        """, permissions.tenant(auth), permissions.account(auth), write(favorites), write(recents));
    audits.record(permissions.tenantKey(auth), permissions.account(auth), auth.getName(),
        "NAVIGATION_PREFERENCES_UPDATED", "NAVIGATION", null, "SUCCESS", null);
    return response(effective, new Preference(favorites, recents));
  }

  private NavigationResponse response(Effective effective, Preference preference) {
    var favoriteKeys = allowedDistinct(preference.favorites(), effective.itemKeys(), 20);
    var recentKeys = allowedDistinct(preference.recents(), effective.itemKeys(), 10);
    return new NavigationResponse(registry.revision(), effective.sections(), favoriteKeys, recentKeys);
  }

  private Effective effective(Authentication auth) {
    var administrator = hasAuthority(auth, "ROLE_PLATFORM_ADMIN");
    var moduleEnabled = new HashMap<String, Boolean>();
    jdbc.query("select module_key,status from platform.module", row -> {
      moduleEnabled.put(row.getString(1), !"DISABLED".equals(row.getString(2)));
    });

    // NavigationWorkspaceDescriptor được giữ như adapter contributor v1.0; API v1.1 phát ra section thống nhất.
    var visibleSections = new LinkedHashMap<String, NavigationRegistry.WorkspaceRegistration>();
    for (var section : registry.workspaces()) {
      var descriptor = section.descriptor();
      if (descriptor.requiredAuthority().isBlank() || hasAuthority(auth, descriptor.requiredAuthority()))
        visibleSections.put(descriptor.key(), section);
    }

    var accessiblePages = new LinkedHashMap<String, NavigationRegistry.ItemRegistration>();
    var groups = new LinkedHashMap<String, NavigationRegistry.ItemRegistration>();
    for (var item : registry.items()) {
      var descriptor = item.descriptor();
      if (!visibleSections.containsKey(descriptor.workspaceKey())) continue;
      if (!"kernel".equals(item.ownerModule()) && !moduleEnabled.getOrDefault(item.ownerModule(), false)) continue;
      if (!descriptor.requiredAuthority().isBlank() && !hasAuthority(auth, descriptor.requiredAuthority())) continue;
      if (descriptor.group()) {
        groups.put(descriptor.key(), item);
        continue;
      }
      if (!visibility.canRender(auth, descriptor, administrator)) continue;
      accessiblePages.put(descriptor.key(), item);
    }

    var visibleKeys = new LinkedHashSet<>(accessiblePages.keySet());
    boolean changed;
    do {
      changed = false;
      for (var item : registry.items()) {
        var parent = item.descriptor().parentKey();
        if (!parent.isBlank() && visibleKeys.contains(item.descriptor().key()) && groups.containsKey(parent))
          changed |= visibleKeys.add(parent);
      }
    } while (changed);

    var sectionViews = new ArrayList<SectionView>();
    for (var section : visibleSections.values()) {
      var itemViews = registry.items().stream()
          .filter(item -> item.descriptor().workspaceKey().equals(section.descriptor().key()))
          .filter(item -> visibleKeys.contains(item.descriptor().key()))
          .map(this::view)
          .toList();
      if (!itemViews.isEmpty()) {
        var descriptor = section.descriptor();
        sectionViews.add(new SectionView(descriptor.key(), descriptor.label(), descriptor.labelKey(), descriptor.icon(),
            descriptor.sortOrder(), itemViews));
      }
    }
    var itemKeys = new LinkedHashSet<String>();
    sectionViews.forEach(section -> section.items().stream().filter(item -> "PAGE".equals(item.type()))
        .forEach(item -> itemKeys.add(item.key())));
    return new Effective(List.copyOf(sectionViews), Set.copyOf(itemKeys));
  }

  private NavigationItemView view(NavigationRegistry.ItemRegistration item) {
    var descriptor = item.descriptor();
    return new NavigationItemView(descriptor.key(), descriptor.parentKey(), item.ownerModule(), descriptor.label(),
        descriptor.labelKey(), descriptor.icon(), descriptor.group() ? "GROUP" : "PAGE", descriptor.viewKey(),
        descriptor.route(), descriptor.sortOrder(), descriptor.keywords());
  }

  private Preference preference(Authentication auth) {
    var rows = jdbc.query("select favorite_keys::text,recent_keys::text from platform.navigation_preference where tenant_id=? and account_id=?",
        (row, number) -> new Preference(read(row.getString(1)), read(row.getString(2))),
        permissions.tenant(auth), permissions.account(auth));
    return rows.isEmpty() ? new Preference(List.of(), List.of()) : rows.getFirst();
  }

  private List<String> allowedDistinct(List<String> input, Set<String> allowed, int max) {
    if (input == null) return List.of();
    return input.stream().filter(Objects::nonNull).map(String::trim).filter(allowed::contains).distinct().limit(max).toList();
  }

  private boolean hasAuthority(Authentication auth, String authority) {
    return auth != null && auth.getAuthorities().stream().anyMatch(item -> authority.equals(item.getAuthority()));
  }

  private String write(List<String> value) {
    try { return json.writeValueAsString(value); }
    catch (Exception exception) { throw new IllegalStateException(exception); }
  }

  private List<String> read(String value) {
    try { return value == null ? List.of() : json.readValue(value, new TypeReference<List<String>>() {}); }
    catch (Exception exception) { return List.of(); }
  }
}
