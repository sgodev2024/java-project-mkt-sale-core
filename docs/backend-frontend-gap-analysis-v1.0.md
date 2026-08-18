# Ma trận hiện trạng Backend – Frontend và API kiểm thử

| Thuộc tính | Giá trị |
|---|---|
| Phạm vi | Java Core Platform, dedicated deployment |
| Ngày đánh giá | 2026-08-17 |
| Backend | Java 21 / Spring Boot / PostgreSQL |
| Frontend | Next.js 16.3.1 App Router / React 19 |
| Nguồn chuẩn | `core-platform-ba-requirements-v1.1.md` |

## 1. Kết luận

Frontend đang dùng Next.js chính thức, build theo chế độ `standalone`; không có vinext, Vite hoặc runtime thay thế. Các màn hình hiện hữu đã được phân loại rõ thành ba nhóm: đã nối API, backend có API nhưng chưa có UI, và capability chưa có backend. Production không còn hiển thị chỉ số seed/demo dưới nhãn dữ liệu thật.

## 2. Hạng mục đã khớp Backend – Frontend

| Capability | Frontend | API backend | Trạng thái |
|---|---|---|---|
| Đăng nhập, MFA tùy cấu hình, làm mới phiên, đăng xuất | Login/MFA/logout và tự xoay refresh token | `/api/v1/auth/login`, `/mfa`, `/refresh`, `/me`, `/logout` | Hoạt động |
| Điều hướng động và tùy chọn cá nhân | Sidebar, route guard, favorites, recent, command palette | `/api/v1/navigation/me`, `/me/preferences` | Hoạt động |
| Trang tổng quan quản trị | Chỉ số, module, resource, activity, file từ PostgreSQL | `/api/v1/control-plane/bootstrap` | Hoạt động; seed legacy đã loại bỏ |
| Module runtime | Tìm kiếm, lọc, bật/tắt, icon theo module | `/api/v1/control-plane/modules/{id}/status` | Hoạt động |
| Dynamic Resource | Tạo definition, tạo record, danh sách, import/export CSV | `/api/v1/dynamic/**` | Luồng chính hoạt động |
| Người dùng | Danh sách, tạo, bật/tắt, reset mật khẩu | `/api/v1/access/users/**` | Hoạt động |
| Cơ cấu tổ chức | Danh sách và tạo đơn vị | `/api/v1/access/organizations` | Hoạt động |
| Vai trò và policy | Danh sách, tạo vai trò, tạo policy | `/api/v1/access/roles`, `/policies` | Hoạt động |
| Jobs và transactional outbox | Số liệu runtime, danh sách, retry/cancel/replay, refresh | `/api/v1/control-plane/jobs/**`, `/outbox/**` | Hoạt động |
| Tệp tin | Danh sách, tìm/lọc, upload, download | `/api/v1/files` | Hoạt động |
| Cấu hình deployment | Đọc/lưu cấu hình tổng quát | `/api/v1/control-plane/settings` | Hoạt động |
| Approval sample | UI lazy chunk theo manifest | `/api/v1/approvals/**` | Chỉ `demo`/`test`; không tồn tại ở Production |

## 3. Backend đã có API nhưng Frontend chưa có màn hình đầy đủ

Các API này có thể kiểm thử bằng integration test hoặc Swagger trong môi trường test. Không chạy thao tác ghi/xóa trực tiếp trên Production nếu chưa có kịch bản và dữ liệu thử được duyệt.

| Nhóm | API đã có | Phần UI còn thiếu |
|---|---|---|
| Bảo mật tài khoản | `POST /auth/change-password`, `/auth/mfa/enroll`, `/auth/mfa/confirm` | Hồ sơ cá nhân, đổi mật khẩu, bật/tắt/quản lý MFA |
| Service account | `GET/POST /access/service-accounts`, rotate, revoke | Màn hình API key hiển thị một lần, rotate/revoke |
| Gán policy | `PUT /access/roles/{roleId}/policies` | Backend chưa trả danh sách policy đã gán theo role nên UI bind an toàn chưa hoàn chỉnh |
| Tenant foundation | `GET/POST /access/tenants` | Cố ý không hiển thị ở dedicated deployment; chỉ dùng provisioning kỹ thuật |
| Dynamic Resource nâng cao | classification, schema migration, index, get/update/archive record, history, full-text search | Schema/version console, record editor, history, archive, index management |
| File lifecycle nâng cao | upload session 3 bước, delete, reconcile, staging cleanup | Xóa file, legal-hold status, reconcile và cleanup console |
| Job scheduler | `GET/POST /control-plane/job-schedules` | Danh sách và tạo lịch chạy |
| Audit integrity | audit list, verify, checkpoint, purge, legal hold/release | Audit console, integrity status và retention workflow có xác nhận |
| Webhook | `GET/POST /webhooks`, disable | Webhook administration, secret rotation và delivery history |
| Resource/role API cũ | `POST /control-plane/resources`, `/roles` | Đã có luồng chuẩn mới; cần đánh dấu deprecate hoặc hợp nhất contract |

## 4. Capability Backend còn thiếu hoặc chưa đủ so với MVP

| BA capability | Hiện trạng | Khoảng trống cần triển khai |
|---|---|---|
| CAP-003 Domain Resource Adapter | Có `DomainResourceAdapter` + registry fail-fast, read/history contract và module template | Domain module thật phải cung cấp adapter/repository riêng; Core không generic hóa command/invariant |
| CAP-009 Resource history | Dynamic Resource có revision; Domain Resource có public history SPI | Từng domain module phải triển khai persistence/history và retention cụ thể |
| CAP-010 Hook/extension system | Có `ModuleContributor`, event handler và job handler | Chưa có lifecycle hook registry với thứ tự, timeout, isolation và failure policy |
| CAP-015 Search cơ bản | Có search theo dynamic resource và file | Chưa có search API liên resource/module với permission filter thống nhất |
| CAP-016 Localization/i18n | Descriptor có `labelKey` | Chưa có message catalog, locale negotiation và API tải bản dịch |
| CAP-017 Naming/numbering series | Chưa có | Cần sequence definition, scope tenant/module, concurrency và format policy |
| CAP-018 Archive policy | Dynamic record và file có soft delete | Chưa có retention/archive contract dùng chung cho domain module |
| CAP-019 Module compatibility | Có dependency graph, semver, cycle validation và runtime `coreVersionRange` gate | Chưa có compatibility matrix, install/upgrade/rollback API |
| DEP-002 Kubernetes/Helm | Có OCI image và Docker Compose | Chưa có Helm chart/HA manifest; triển khai khi chọn service tier HA |
| Storage adapter | Local filesystem có checksum/reconcile | Chưa có adapter S3-compatible và migration giữa storage backend |

Các capability Giai đoạn 2/3 như OIDC/LDAP, BPMN, email, feature flags, report builder, SaaS control plane và AI extension chưa được coi là lỗi MVP; chỉ triển khai khi có quyết định phạm vi riêng.

## 5. API backend dùng để kiểm thử

| Nhóm test | Endpoint chính | Test tự động hiện có |
|---|---|---|
| Authentication/session | `/auth/login`, `/mfa`, `/refresh`, `/logout`, `/change-password` | `AuthApiTest`, `MfaEnrollFlowTest`, `MfaEnrollmentTest` |
| Permission/tenant | `/access/users`, `/roles`, `/policies`, `/organizations`, `/tenants`, `/service-accounts` | `AccessManagementTest`, `PermissionPredicateTest`, `TenantIsolationTest` |
| Navigation | `/navigation/me`, `/navigation/me/preferences` | `NavigationApiTest`, `NavigationVisibilityPolicyTest` |
| Dynamic Resource | `/dynamic/definitions`, `/{key}/records`, history, search, CSV, schema/index | `DynamicResourceTest`, `DynamicResourceAdvancedTest`, `WebhookAndSearchTest` |
| Audit | `/control-plane/audit/**` | `AuditIntegrityTest`, `ControlPlaneTest` |
| Outbox | `/control-plane/outbox`, replay | `OutboxRelayTest`, `EventContractTest` |
| Jobs/scheduler | `/control-plane/jobs/**`, `/job-schedules` | `JobQueueSchedulerTest` |
| File | `/files/**` | `FileManagementTest`, `FileLifecycleTest` |
| Webhook | `/webhooks/**` | `WebhookAndSearchTest` |
| Demo approval | `/approvals/**` | `ApprovalDomainTest`, `DemoApprovalProfileTest` |
| Health/OpenAPI | `/actuator/health/readiness`, `/v3/api-docs` | Deployment smoke test |

### Quy tắc smoke test Production

1. Chỉ kiểm tra readiness, đăng nhập bằng tài khoản kiểm thử được duyệt, `/auth/me`, navigation và các API `GET`.
2. API mutation phải dùng dữ liệu có tiền tố kiểm thử, có bước dọn dẹp và được phê duyệt trước.
3. Không gọi audit purge, legal hold, file delete, job cancel/replay hoặc module disable trên Production trong smoke test thông thường.
4. API sample `/approvals/**` phải không được đăng ký ở profile Production.

## 6. Hạng mục dọn dẹp trong đợt này

- Bỏ Tailwind/PostCSS vì frontend chỉ dùng CSS module toàn cục tự quản; giảm 14 package trực tiếp/transitive.
- Nâng và pin Next.js `16.3.1`, đồng bộ `eslint-config-next`, audit dependency đạt 0 lỗ hổng đã biết tại thời điểm build.
- Bỏ ba SVG mặc định của template Next.js không được tham chiếu.
- Xóa archive `frontend-runtime.tgz` cũ, giải phóng 10.133.876 byte.
- Migration V18 xóa chính xác file/outbox/job/activity/role/resource placeholder legacy; V19 chuẩn hóa trạng thái runtime của bốn module lõi từng bị gắn `DISABLED` bởi dữ liệu trình diễn.
- Đổi `DemoAccountInitializer` thành `BootstrapAdminInitializer`; Production fail-fast nếu thiếu secret hoặc dùng mật khẩu demo.
