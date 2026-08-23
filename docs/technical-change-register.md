# Core Platform — Sổ thay đổi kỹ thuật

## 1. Mục đích và phạm vi

Đây là tài liệu chuẩn để bộ phận lập trình, kiểm thử và vận hành tra cứu mọi điều chỉnh liên quan đến mã nguồn Core Platform. Tài liệu bao phủ backend Java, frontend Next.js, database migration, security, CI/CD, Docker, Nginx và quy trình release.

Phần quyết định kỹ thuật được cập nhật có chủ đích trong cùng commit với mã. Phần lịch sử Git ở cuối tài liệu được sinh tự động sau mỗi lần push lên `main` bởi workflow `.github/workflows/technical-change-log.yml`; không cần yêu cầu cập nhật riêng.

## 2. Baseline hiện hành

| Thành phần | Baseline |
|---|---|
| Backend | Java 21, Spring Boot 3.5, module registry code-first |
| Frontend | Next.js 16 App Router, React 19, output `standalone` |
| Database | PostgreSQL, Flyway, migration user tách runtime user, tenant RLS |
| Authentication | Internal account, opaque hashed session; MFA được điều khiển bằng feature flag |
| Navigation | Application shell hợp nhất; `Trang chủ` độc lập, section `business`/`system-administration`, menu động và cây tối đa ba cấp |
| Packaging | Docker multi-stage; frontend build bằng Next.js chính thức |
| Release | PR build/test, production release có backup, rehearsal và rollback container |

## 3. Thay đổi đang có hiệu lực

### 3.1 Application shell và Navigation Registry v1.1

- Bỏ Workspace switcher; dùng một cây điều hướng hợp nhất cho dedicated deployment.
- Module đăng ký section/group/page qua `ModuleContributor`; frontend không duy trì danh sách module cố định.
- Cây điều hướng bị giới hạn ở `Section → Group → Page`; registry fail startup nếu group lồng group.
- API `GET /api/v1/navigation/me` trả `sections[]` đã lọc theo module status, authority và permission.
- API `PUT /api/v1/navigation/me/preferences` chỉ lưu yêu thích và mục gần đây; trường Workspace cuối không còn trong contract, cột legacy được reset chuỗi rỗng để tương thích schema.
- Route chuyển từ hash sang `/home`, `/business/...`, `/administration/...`; Next.js có route tương ứng để direct load/refresh.
- Section `system-administration` nằm cuối và chỉ hiện cho `ROLE_PLATFORM_ADMIN`; label người dùng là **Quản trị viên hệ thống**.
- `core.home` được tách vào adapter `home` và render thành page cấp cao, đứng cùng cấp với `Nghiệp vụ` và `Quản trị hệ thống`; section `business` chỉ chứa menu do module nghiệp vụ đóng góp.
- Registry fail startup nếu module chèn item vào `home` hoặc nếu `core.home` bị đặt lại trong `business`; section Nghiệp vụ rỗng vẫn hiển thị empty state thay vì chứa Trang chủ làm fallback.
- Nền application shell và đăng nhập dùng bộ design token `transition-green-*`: canvas xanh nhạt `#e7f2ea`, xanh chuyển đổi `#238558` và xanh rừng `#062f24`; focus ring, topbar và main canvas dùng cùng hệ màu để Core và dự án không lệch nhận diện.
- `NavigationItemDescriptor.visibilityMode=ASSIGNMENT` luôn qua `NavigationVisibilityPolicy` và exact-policy PDP, kể cả System Administrator; wildcard `*/*` không được xem là nhiệm vụ được giao (FE-BA-13).
- Core shell không hard-code `Công việc của tôi`. Chỉ module có view/API/PEP hộp việc thật mới đăng ký item `ASSIGNMENT`; tài khoản quản trị muốn xử lý nghiệp vụ phải có capability assignment chính xác.
- Capability assignment giữ menu ổn định khi hộp việc đang rỗng; view hiển thị empty state, badge không tham gia authorization.
- Frontend chuẩn hóa route không có trong manifest về page được phép, ngăn việc render trực tiếp một view đã bị backend loại khỏi navigation.
- Kiểm thử tách riêng policy hiển thị menu, bao gồm negative test chứng minh System Administrator không bypass `ASSIGNMENT`; frontend có guard chống hard-code menu tác vụ cá nhân.

### 3.2 Chuyển frontend sang Next.js chuẩn

- Loại bỏ vinext, Vite, Cloudflare Worker, Sites hosting metadata và D1/Drizzle starter không sử dụng.
- Lệnh chuẩn: `next dev`, `next build`, `next start`.
- `next.config.ts` bật `output: "standalone"`.
- Dockerfile dùng ba stage: dependency, build và runtime non-root; runtime chỉ chứa `public`, `.next/standalone` và `.next/static`, tắt telemetry và có container healthcheck.
- `NEXT_PUBLIC_CORE_API_URL` được truyền bằng Docker build argument để cố định API URL theo môi trường.
- SSR smoke test khởi động trực tiếp `.next/standalone/server.js`.

### 3.3 Tạm tắt xác thực hai lớp

- Feature flag: `CORE_MFA_ENABLED`; mặc định `true` để fail-safe ở môi trường mới.
- Production hiện đặt `CORE_MFA_ENABLED=false` theo yêu cầu vận hành.
- Khi flag `false`, `POST /api/v1/auth/login` kiểm tra mật khẩu rồi trả `mfaRequired=false` và `session`; không tạo MFA challenge.
- Khi flag `true`, hợp đồng challenge/TOTP cũ vẫn hoạt động.
- Enrollment, secret và recovery codes được giữ nguyên để có thể bật lại mà không migration dữ liệu.
- Mọi lần bỏ qua MFA được audit bằng action `AUTH_MFA_SKIPPED_BY_CONFIGURATION`.
- Bật lại: đổi `CORE_MFA_ENABLED=true` và restart backend; không cần sửa code hoặc database.

### 3.4 Frontend quản trị dedicated deployment

- Baseline nghiệp vụ là một khách hàng/một deployment/một database; không hiển thị tenant switcher hoặc SaaS Control Plane.
- Trang Người dùng nối `GET/POST /api/v1/access/users`, hỗ trợ bật/tắt và đặt lại mật khẩu.
- Trang Cơ cấu tổ chức nối `GET/POST /api/v1/access/organizations`.
- Trang Vai trò & phân quyền nối `GET/POST /api/v1/access/roles` và `GET /api/v1/access/policies`.
- Chỉ số hard-code ở khu vực truy cập và latency đã được thay bằng dữ liệu backend hoặc loại bỏ.
- Trang chủ dùng nội dung theo ngữ cảnh: System Administrator thấy tổng quan vận hành; người dùng khác thấy module nghiệp vụ được cấp quyền.
- Loại bỏ dải thông tin tĩnh `Môi trường / Phiên bản Core / Database / Mô hình vận hành` khỏi cuối Trang chủ theo FE-FR-011; xóa đồng thời CSS responsive không còn sử dụng và có source guard test chống xuất hiện lại.
- Tách `approval-domain` sang package `vn.coreplatform.demo.approval`; module contributor, controller và metadata chỉ active ở profile `demo`/`test`. Production guard cùng migration V17 loại metadata legacy nhưng giữ bảng/dữ liệu để rollback.
- Section `business` là vùng Nghiệp vụ chuẩn chờ module khách hàng. Trong demo/test, `Đề nghị phê duyệt` nằm dưới group `Nghiệp vụ mẫu`; Production loại group/page rỗng khỏi manifest.
- Frontend approval demo được tách thành lazy chunk `app/demo/approval-workspace.tsx`; shell chỉ tải khi backend manifest cho phép view `approvals`.
- Tài liệu BA chuẩn là `core-platform-ba-requirements-v1.1.md`; v1.0 chỉ còn giá trị lịch sử.

## 4. Quy tắc cập nhật tài liệu

1. Thay đổi kiến trúc, security, API contract, migration hoặc vận hành phải cập nhật phần quyết định ở trên trong cùng pull request.
2. Commit subject phải mô tả kết quả kỹ thuật; lịch sử tự động sử dụng chính subject và danh sách file.
3. Không sửa nội dung giữa hai marker `AUTO-GENERATED`.
4. Có thể tái tạo cục bộ bằng `node scripts/update-technical-change-log.mjs` hoặc `npm run docs:changes` trong thư mục `frontend`.
5. Workflow trên `main` tự commit tài liệu sinh lại bằng tài khoản `github-actions[bot]` khi lịch sử thay đổi.

## 5. Lịch sử thay đổi tự động

<!-- AUTO-GENERATED:START -->
> Sinh tự động từ Git. Mốc mã gần nhất: `b29e20476722182dee80a72e7d36956b0f2d98e9` (2026-08-23). Không sửa trực tiếp phần này.

| Ngày | Commit | Nội dung | Tác giả | Số file |
|---|---|---|---|---:|
| 2026-08-23 | [`b29e204`](https://github.com/sgodev2024/java-core/commit/b29e20476722182dee80a72e7d36956b0f2d98e9) | Document CRM green interface release | sgodev2024 | 1 |
| 2026-08-23 | [`d452737`](https://github.com/sgodev2024/java-core/commit/d452737f2023d0978364f63af8457ae086db9954) | Apply green transformation interface palette | sgodev2024 | 5 |
| 2026-08-23 | [`3335faf`](https://github.com/sgodev2024/java-core/commit/3335fafa4d50499021d3b2b54e498c2bb3beb6c6) | Verify standalone home navigation in smoke test | sgodev2024 | 2 |
| 2026-08-23 | [`cff4bee`](https://github.com/sgodev2024/java-core/commit/cff4beef2ecd864cb6afd3a844c60e6c2fde8894) | Separate home from business navigation | sgodev2024 | 13 |
| 2026-08-18 | [`196f328`](https://github.com/sgodev2024/java-core/commit/196f3281995281639f2e782094411a3ab812f693) | Record CORS hotfix deployment | sgodev2024 | 1 |
| 2026-08-18 | [`f059f43`](https://github.com/sgodev2024/java-core/commit/f059f43fd4f46bb3f673fcb4437ebc87cc405f85) | Document browser origin release gate | sgodev2024 | 2 |
| 2026-08-18 | [`68bf081`](https://github.com/sgodev2024/java-core/commit/68bf0813f0c31b711449f779440df02b6b80262e) | Fix project domain CORS handling | sgodev2024 | 8 |
| 2026-08-18 | [`5ad2ec5`](https://github.com/sgodev2024/java-core/commit/5ad2ec5786997b52775c22fc879ca2a2a110539d) | Document and verify test deployment | sgodev2024 | 5 |
| 2026-08-18 | [`2f5aa74`](https://github.com/sgodev2024/java-core/commit/2f5aa74349d2c2e520e461fa4e9a0cd5bc6bf305) | Prepare isolated CRM marketing sales deployment | sgodev2024 | 7 |
| 2026-08-18 | [`b17d86e`](https://github.com/sgodev2024/java-core/commit/b17d86e0d99fcfbc69ecf05ac4e0ab208dde8a93) | Build Revenue Intelligence MVP | sgodev2024 | 22 |
| 2026-08-18 | [`0fc5048`](https://github.com/sgodev2024/java-core/commit/0fc50485b26fd3a00e5113c7d194064cf37e8018) | Initialize Revenue Intelligence from Core baseline | sgodev2024 | 200 |

## Chi tiết file theo commit

### 2026-08-23 — Document CRM green interface release

- Commit: [`b29e20476722182dee80a72e7d36956b0f2d98e9`](https://github.com/sgodev2024/java-core/commit/b29e20476722182dee80a72e7d36956b0f2d98e9)
- Tác giả: sgodev2024
- Phạm vi file:

- `M` `docs/06-deployment-runbook-test-v1.0.md`

### 2026-08-23 — Apply green transformation interface palette

- Commit: [`d452737f2023d0978364f63af8457ae086db9954`](https://github.com/sgodev2024/java-core/commit/d452737f2023d0978364f63af8457ae086db9954)
- Tác giả: sgodev2024
- Phạm vi file:

- `M` `core-platform-ba-requirements-v1.1.md`
- `M` `docs/technical-change-register.md`
- `M` `frontend/README.md`
- `M` `frontend/app/globals.css`
- `M` `frontend/tests/rendered-html.test.mjs`

### 2026-08-23 — Verify standalone home navigation in smoke test

- Commit: [`3335fafa4d50499021d3b2b54e498c2bb3beb6c6`](https://github.com/sgodev2024/java-core/commit/3335fafa4d50499021d3b2b54e498c2bb3beb6c6)
- Tác giả: sgodev2024
- Phạm vi file:

- `M` `deploy/project/smoke-test.sh`
- `M` `docs/06-deployment-runbook-test-v1.0.md`

### 2026-08-23 — Separate home from business navigation

- Commit: [`cff4beef2ecd864cb6afd3a844c60e6c2fde8894`](https://github.com/sgodev2024/java-core/commit/cff4beef2ecd864cb6afd3a844c60e6c2fde8894)
- Tác giả: sgodev2024
- Phạm vi file:

- `M` `backend/src/main/java/vn/coreplatform/kernel/KernelModule.java`
- `M` `backend/src/main/java/vn/coreplatform/kernel/NavigationRegistry.java`
- `M` `backend/src/main/java/vn/coreplatform/kernel/NavigationWorkspaceDescriptor.java`
- `M` `backend/src/main/java/vn/coreplatform/navigation/NavigationController.java`
- `M` `backend/src/test/java/vn/coreplatform/kernel/NavigationApiTest.java`
- `M` `backend/src/test/java/vn/coreplatform/kernel/NavigationRegistryTest.java`
- `M` `core-platform-ba-requirements-v1.1.md`
- `M` `docs/navigation-registry.md`
- `M` `docs/technical-change-register.md`
- `M` `frontend/README.md`
- `M` `frontend/app/globals.css`
- `M` `frontend/app/page.tsx`
- `M` `frontend/tests/rendered-html.test.mjs`

### 2026-08-18 — Record CORS hotfix deployment

- Commit: [`196f3281995281639f2e782094411a3ab812f693`](https://github.com/sgodev2024/java-core/commit/196f3281995281639f2e782094411a3ab812f693)
- Tác giả: sgodev2024
- Phạm vi file:

- `M` `docs/06-deployment-runbook-test-v1.0.md`

### 2026-08-18 — Document browser origin release gate

- Commit: [`f059f43fd4f46bb3f673fcb4437ebc87cc405f85`](https://github.com/sgodev2024/java-core/commit/f059f43fd4f46bb3f673fcb4437ebc87cc405f85)
- Tác giả: sgodev2024
- Phạm vi file:

- `M` `docs/00-core-to-project-implementation-standard-v1.0.md`
- `M` `docs/06-deployment-runbook-test-v1.0.md`

### 2026-08-18 — Fix project domain CORS handling

- Commit: [`68bf0813f0c31b711449f779440df02b6b80262e`](https://github.com/sgodev2024/java-core/commit/68bf0813f0c31b711449f779440df02b6b80262e)
- Tác giả: sgodev2024
- Phạm vi file:

- `M` `backend/src/main/java/vn/coreplatform/security/SecurityConfig.java`
- `M` `backend/src/main/resources/application.yml`
- `A` `backend/src/test/java/vn/coreplatform/security/CorsSecurityTest.java`
- `M` `deploy/project/.env.example`
- `M` `deploy/project/docker-compose.yml`
- `M` `deploy/project/smoke-test.sh`
- `M` `docs/06-deployment-runbook-test-v1.0.md`
- `M` `frontend/app/page.tsx`

### 2026-08-18 — Document and verify test deployment

- Commit: [`5ad2ec5786997b52775c22fc879ca2a2a110539d`](https://github.com/sgodev2024/java-core/commit/5ad2ec5786997b52775c22fc879ca2a2a110539d)
- Tác giả: sgodev2024
- Phạm vi file:

- `M` `.gitignore`
- `M` `README.md`
- `A` `deploy/project/smoke-test.sh`
- `M` `docs/05-implementation-status-v1.0.md`
- `A` `docs/06-deployment-runbook-test-v1.0.md`

### 2026-08-18 — Prepare isolated CRM marketing sales deployment

- Commit: [`2f5aa74349d2c2e520e461fa4e9a0cd5bc6bf305`](https://github.com/sgodev2024/java-core/commit/2f5aa74349d2c2e520e461fa4e9a0cd5bc6bf305)
- Tác giả: sgodev2024
- Phạm vi file:

- `M` `README.md`
- `A` `deploy/project/.env.example`
- `A` `deploy/project/deploy.sh`
- `A` `deploy/project/docker-compose.yml`
- `A` `deploy/project/nginx-crm-mkt-sale.conf`
- `A` `deploy/project/postgres-init/01-runtime-role.sh`
- `A` `docs/00-core-to-project-implementation-standard-v1.0.md`

### 2026-08-18 — Build Revenue Intelligence MVP

- Commit: [`b17d86e0d99fcfbc69ecf05ac4e0ab208dde8a93`](https://github.com/sgodev2024/java-core/commit/b17d86e0d99fcfbc69ecf05ac4e0ab208dde8a93)
- Tác giả: sgodev2024
- Phạm vi file:

- `M` `README.md`
- `M` `backend/src/main/java/vn/coreplatform/CorePlatformApplication.java`
- `A` `backend/src/main/java/vn/sgodata/revenueintelligence/CsvTableParser.java`
- `A` `backend/src/main/java/vn/sgodata/revenueintelligence/RevenueAnalyticsService.java`
- `A` `backend/src/main/java/vn/sgodata/revenueintelligence/RevenueImportService.java`
- `A` `backend/src/main/java/vn/sgodata/revenueintelligence/RevenueIntelligenceController.java`
- `A` `backend/src/main/java/vn/sgodata/revenueintelligence/RevenueIntelligenceModule.java`
- `A` `backend/src/main/java/vn/sgodata/revenueintelligence/RevenueOrderAdapter.java`
- `M` `backend/src/main/resources/application.yml`
- `A` `backend/src/main/resources/db/migration/V20__revenue_intelligence_baseline.sql`
- `M` `backend/src/test/java/vn/coreplatform/controlplane/ControlPlaneTest.java`
- `M` `backend/src/test/java/vn/coreplatform/kernel/ModuleBoundaryTest.java`
- `M` `backend/src/test/java/vn/coreplatform/kernel/NavigationApiTest.java`
- `A` `backend/src/test/java/vn/sgodata/revenueintelligence/CsvTableParserTest.java`
- `A` `backend/src/test/java/vn/sgodata/revenueintelligence/RevenueIntelligenceApiTest.java`
- `A` `backend/src/test/java/vn/sgodata/revenueintelligence/RevenueRulesTest.java`
- `A` `docs/04-data-and-integration-contracts-v1.0.md`
- `A` `docs/05-implementation-status-v1.0.md`
- `M` `frontend/app/components/app-icon.tsx`
- `M` `frontend/app/globals.css`
- `A` `frontend/app/modules/revenue-intelligence.tsx`
- `M` `frontend/app/page.tsx`

### 2026-08-18 — Initialize Revenue Intelligence from Core baseline

- Commit: [`0fc50485b26fd3a00e5113c7d194064cf37e8018`](https://github.com/sgodev2024/java-core/commit/0fc50485b26fd3a00e5113c7d194064cf37e8018)
- Tác giả: sgodev2024
- Phạm vi file:

- `A` `.github/CODEOWNERS`
- `A` `.github/PULL_REQUEST_TEMPLATE.md`
- `A` `.github/workflows/backend-ci.yml`
- `A` `.github/workflows/frontend-ci.yml`
- `A` `.github/workflows/technical-change-log.yml`
- `A` `.gitignore`
- `A` `CORE_BASELINE`
- `A` `README.md`
- `A` `backend/.env.example`
- `A` `backend/.mvn/wrapper/maven-wrapper.properties`
- `A` `backend/Dockerfile`
- `A` `backend/README.md`
- `A` `backend/mvnw`
- `A` `backend/mvnw.cmd`
- `A` `backend/pom.xml`
- `A` `backend/src/main/java/vn/coreplatform/CorePlatformApplication.java`
- `A` `backend/src/main/java/vn/coreplatform/audit/AuditCheckpointHandler.java`
- `A` `backend/src/main/java/vn/coreplatform/audit/AuditModule.java`
- `A` `backend/src/main/java/vn/coreplatform/audit/AuditService.java`
- `A` `backend/src/main/java/vn/coreplatform/controlplane/ActivityProjector.java`
- `A` `backend/src/main/java/vn/coreplatform/controlplane/ControlPlaneController.java`
- `A` `backend/src/main/java/vn/coreplatform/controlplane/ControlPlaneModule.java`
- `A` `backend/src/main/java/vn/coreplatform/demo/approval/ApprovalDomainModule.java`
- `A` `backend/src/main/java/vn/coreplatform/demo/approval/ApprovalRequestController.java`
- `A` `backend/src/main/java/vn/coreplatform/demo/approval/DemoApprovalMetadata.java`
- `A` `backend/src/main/java/vn/coreplatform/demo/approval/DemoApprovalProductionGuard.java`
- `A` `backend/src/main/java/vn/coreplatform/dynamicresource/DynamicResourceAdminController.java`
- `A` `backend/src/main/java/vn/coreplatform/dynamicresource/DynamicResourceController.java`
- `A` `backend/src/main/java/vn/coreplatform/dynamicresource/DynamicResourceModule.java`
- `A` `backend/src/main/java/vn/coreplatform/eventing/EventingModule.java`
- `A` `backend/src/main/java/vn/coreplatform/eventing/IntegrationEvent.java`
- `A` `backend/src/main/java/vn/coreplatform/eventing/IntegrationEventHandler.java`
- `A` `backend/src/main/java/vn/coreplatform/eventing/OutboxRelay.java`
- `A` `backend/src/main/java/vn/coreplatform/eventing/OutboxService.java`
- `A` `backend/src/main/java/vn/coreplatform/filemanagement/FileController.java`
- `A` `backend/src/main/java/vn/coreplatform/filemanagement/FileManagementModule.java`
- `A` `backend/src/main/java/vn/coreplatform/filemanagement/FileResourceMetadata.java`
- `A` `backend/src/main/java/vn/coreplatform/filemanagement/FileStorageService.java`
- `A` `backend/src/main/java/vn/coreplatform/identity/AuthController.java`
- `A` `backend/src/main/java/vn/coreplatform/identity/BootstrapAdminInitializer.java`
- `A` `backend/src/main/java/vn/coreplatform/identity/IdentityModule.java`
- `A` `backend/src/main/java/vn/coreplatform/identity/IdentityResourceMetadata.java`
- `A` `backend/src/main/java/vn/coreplatform/identity/Totp.java`
- `A` `backend/src/main/java/vn/coreplatform/jobs/JobHandler.java`
- `A` `backend/src/main/java/vn/coreplatform/jobs/JobScheduler.java`
- `A` `backend/src/main/java/vn/coreplatform/jobs/JobService.java`
- `A` `backend/src/main/java/vn/coreplatform/jobs/JobWorker.java`
- `A` `backend/src/main/java/vn/coreplatform/jobs/JobsModule.java`
- `A` `backend/src/main/java/vn/coreplatform/kernel/CoreCompatibility.java`
- `A` `backend/src/main/java/vn/coreplatform/kernel/DomainResourceAdapter.java`
- `A` `backend/src/main/java/vn/coreplatform/kernel/DomainResourceAdapterRegistry.java`
- `A` `backend/src/main/java/vn/coreplatform/kernel/KernelModule.java`
- `A` `backend/src/main/java/vn/coreplatform/kernel/MigrationCoordinator.java`
- `A` `backend/src/main/java/vn/coreplatform/kernel/ModuleContributor.java`
- `A` `backend/src/main/java/vn/coreplatform/kernel/ModuleDescriptor.java`
- `A` `backend/src/main/java/vn/coreplatform/kernel/ModuleRegistry.java`
- `A` `backend/src/main/java/vn/coreplatform/kernel/NavigationItemDescriptor.java`
- `A` `backend/src/main/java/vn/coreplatform/kernel/NavigationRegistry.java`
- `A` `backend/src/main/java/vn/coreplatform/kernel/NavigationWorkspaceDescriptor.java`
- `A` `backend/src/main/java/vn/coreplatform/kernel/ResourceDescriptor.java`
- `A` `backend/src/main/java/vn/coreplatform/kernel/ResourceRegistry.java`
- `A` `backend/src/main/java/vn/coreplatform/kernel/TenantAwareDataSource.java`
- `A` `backend/src/main/java/vn/coreplatform/kernel/TenantContext.java`
- `A` `backend/src/main/java/vn/coreplatform/kernel/TenantContextFilter.java`
- `A` `backend/src/main/java/vn/coreplatform/navigation/NavigationController.java`
- `A` `backend/src/main/java/vn/coreplatform/navigation/NavigationVisibilityPolicy.java`
- `A` `backend/src/main/java/vn/coreplatform/permission/AccessManagementController.java`
- `A` `backend/src/main/java/vn/coreplatform/permission/PermissionEnforcementInterceptor.java`
- `A` `backend/src/main/java/vn/coreplatform/permission/PermissionModule.java`
- `A` `backend/src/main/java/vn/coreplatform/permission/PermissionService.java`
- `A` `backend/src/main/java/vn/coreplatform/permission/RequirePermission.java`
- `A` `backend/src/main/java/vn/coreplatform/security/SecurityConfig.java`
- `A` `backend/src/main/java/vn/coreplatform/shared/ApiExceptionHandler.java`
- `A` `backend/src/main/java/vn/coreplatform/shared/CorrelationIdFilter.java`
- `A` `backend/src/main/java/vn/coreplatform/webhook/WebhookController.java`
- `A` `backend/src/main/java/vn/coreplatform/webhook/WebhookModule.java`
- `A` `backend/src/main/java/vn/coreplatform/webhook/WebhookService.java`
- `A` `backend/src/main/resources/application.yml`
- `A` `backend/src/main/resources/db/migration/V10__eventing_outbox_inbox_e6.sql`
- `A` `backend/src/main/resources/db/migration/V11__jobs_scheduler_e7.sql`
- `A` `backend/src/main/resources/db/migration/V12__file_lifecycle_e8.sql`
- `A` `backend/src/main/resources/db/migration/V13__dynamic_advanced_e9.sql`
- `A` `backend/src/main/resources/db/migration/V14__sample_domain_e10.sql`
- `A` `backend/src/main/resources/db/migration/V15__search_webhook_e11.sql`
- `A` `backend/src/main/resources/db/migration/V16__navigation_registry_preferences.sql`
- `A` `backend/src/main/resources/db/migration/V17__isolate_demo_approval_module.sql`
- `A` `backend/src/main/resources/db/migration/V18__remove_legacy_demo_seed_data.sql`
- `A` `backend/src/main/resources/db/migration/V19__normalize_core_module_runtime_status.sql`
- `A` `backend/src/main/resources/db/migration/V1__platform_baseline.sql`
- `A` `backend/src/main/resources/db/migration/V2__control_plane_operations.sql`
- `A` `backend/src/main/resources/db/migration/V3__tenant_permission_dynamic_resource.sql`
- `A` `backend/src/main/resources/db/migration/V4__permission_management.sql`
- `A` `backend/src/main/resources/db/migration/V5__file_storage.sql`
- `A` `backend/src/main/resources/db/migration/V6__kernel_roles_rls.sql`
- `A` `backend/src/main/resources/db/migration/V7__identity_tenant_e3.sql`
- `A` `backend/src/main/resources/db/migration/V8__resource_registry_e4.sql`
- `A` `backend/src/main/resources/db/migration/V9__audit_integrity_e5.sql`
- `A` `backend/src/test/java/vn/coreplatform/AbstractApiTest.java`
- `A` `backend/src/test/java/vn/coreplatform/audit/AuditIntegrityTest.java`
- `A` `backend/src/test/java/vn/coreplatform/boundaryfixture/dynamicresource/DynamicResourceBoundaryViolation.java`
- `A` `backend/src/test/java/vn/coreplatform/boundaryfixture/identity/IdentityBoundaryViolation.java`
- `A` `backend/src/test/java/vn/coreplatform/controlplane/ControlPlaneTest.java`
- `A` `backend/src/test/java/vn/coreplatform/controlplane/LegacySeedDataCleanupTest.java`
- `A` `backend/src/test/java/vn/coreplatform/demo/approval/ApprovalDomainTest.java`
- `A` `backend/src/test/java/vn/coreplatform/demo/approval/DemoApprovalProfileTest.java`
- `A` `backend/src/test/java/vn/coreplatform/dynamicresource/ClassificationGateTest.java`
- `A` `backend/src/test/java/vn/coreplatform/dynamicresource/DynamicResourceAdvancedTest.java`
- `A` `backend/src/test/java/vn/coreplatform/dynamicresource/DynamicResourceTest.java`
- `A` `backend/src/test/java/vn/coreplatform/eventing/EventContractTest.java`
- `A` `backend/src/test/java/vn/coreplatform/eventing/OutboxRelayTest.java`
- `A` `backend/src/test/java/vn/coreplatform/eventing/OutboxTransactionTest.java`
- `A` `backend/src/test/java/vn/coreplatform/filemanagement/FileLifecycleTest.java`
- `A` `backend/src/test/java/vn/coreplatform/filemanagement/FileManagementTest.java`
- `A` `backend/src/test/java/vn/coreplatform/identity/AuthFlowTest.java`
- `A` `backend/src/test/java/vn/coreplatform/identity/BootstrapAdminInitializerTest.java`
- `A` `backend/src/test/java/vn/coreplatform/identity/MfaDisabledLoginTest.java`
- `A` `backend/src/test/java/vn/coreplatform/identity/MfaEnrollFlowTest.java`
- `A` `backend/src/test/java/vn/coreplatform/identity/MfaEnrollmentTest.java`
- `A` `backend/src/test/java/vn/coreplatform/identity/PasswordPolicyTest.java`
- `A` `backend/src/test/java/vn/coreplatform/identity/RefreshRotationTest.java`
- `A` `backend/src/test/java/vn/coreplatform/identity/ServiceAccountTest.java`
- `A` `backend/src/test/java/vn/coreplatform/jobs/JobQueueSchedulerTest.java`
- `A` `backend/src/test/java/vn/coreplatform/kernel/DomainResourceAdapterRegistryTest.java`
- `A` `backend/src/test/java/vn/coreplatform/kernel/MigrationCoordinatorTest.java`
- `A` `backend/src/test/java/vn/coreplatform/kernel/ModuleBoundaryTest.java`
- `A` `backend/src/test/java/vn/coreplatform/kernel/ModuleRegistrationTest.java`
- `A` `backend/src/test/java/vn/coreplatform/kernel/ModuleRegistryTest.java`
- `A` `backend/src/test/java/vn/coreplatform/kernel/NavigationApiTest.java`
- `A` `backend/src/test/java/vn/coreplatform/kernel/NavigationRegistryTest.java`
- `A` `backend/src/test/java/vn/coreplatform/kernel/ResourceRegistryTest.java`
- `A` `backend/src/test/java/vn/coreplatform/kernel/RowLevelSecurityTest.java`
- `A` `backend/src/test/java/vn/coreplatform/kernel/TenantDataSourceLeakTest.java`
- `A` `backend/src/test/java/vn/coreplatform/navigation/NavigationVisibilityPolicyTest.java`
- `A` `backend/src/test/java/vn/coreplatform/permission/PepFailClosedTest.java`
- `A` `backend/src/test/java/vn/coreplatform/permission/PermissionPredicateTest.java`
- `A` `backend/src/test/java/vn/coreplatform/permission/PermissionTest.java`
- `A` `backend/src/test/java/vn/coreplatform/permission/TenantIsolationTest.java`
- `A` `backend/src/test/java/vn/coreplatform/permission/TenantOrganizationTest.java`
- `A` `backend/src/test/java/vn/coreplatform/shared/AuditCorrelationTest.java`
- `A` `backend/src/test/java/vn/coreplatform/webhook/WebhookAndSearchTest.java`
- `A` `core-platform-architecture-standard-v1.1.md`
- `A` `core-platform-ba-requirements-v1.0.md`
- `A` `core-platform-ba-requirements-v1.1.md`
- `A` `core-platform-database-architecture-v1.0.md`
- `A` `core-platform-runtime-architecture-v1.0.md`
- `A` `deploy/postgres/01-core-roles.sql`
- `A` `deploy/ubuntu20/README.md`
- `A` `deploy/ubuntu20/core-platform.env.example`
- `A` `deploy/ubuntu20/core-platform.service`
- `A` `deploy/ubuntu20/deploy.sh`
- `A` `deploy/ubuntu20/nginx-api-corejava.conf`
- `A` `deploy/ubuntu20/nginx-core-platform.conf`
- `A` `deploy/ubuntu20/nginx-frontend-corejava.conf`
- `A` `docker-compose.yml`
- `A` `docs/01-business-analysis-v1.0.md`
- `A` `docs/02-data-discovery-plan-v1.0.md`
- `A` `docs/03-business-rules-v1.0.md`
- `A` `docs/adr/adr-template.md`
- `A` `docs/backend-frontend-gap-analysis-v1.0.md`
- `A` `docs/decisions.md`
- `A` `docs/navigation-registry.md`
- `A` `docs/technical-change-register.md`
- `A` `frontend/.dockerignore`
- `A` `frontend/.env.example`
- `A` `frontend/.gitignore`
- `A` `frontend/Dockerfile`
- `A` `frontend/README.md`
- `A` `frontend/app/administration/[...path]/page.tsx`
- `A` `frontend/app/business/[...path]/page.tsx`
- `A` `frontend/app/components/app-icon.tsx`
- `A` `frontend/app/demo/approval-workspace.tsx`
- `A` `frontend/app/globals.css`
- `A` `frontend/app/home/page.tsx`
- `A` `frontend/app/layout.tsx`
- `A` `frontend/app/page.tsx`
- `A` `frontend/eslint.config.mjs`
- `A` `frontend/next.config.ts`
- `A` `frontend/package-lock.json`
- `A` `frontend/package.json`
- `A` `frontend/public/favicon.svg`
- `A` `frontend/public/og.png`
- `A` `frontend/tests/rendered-html.test.mjs`
- `A` `frontend/tsconfig.json`
- `A` `samples/input/ad-spend.csv`
- `A` `samples/input/customers.csv`
- `A` `samples/input/orders.csv`
- `A` `samples/input/touchpoints.csv`
- `A` `scripts/new-domain-module.ps1`
- `A` `scripts/update-technical-change-log.mjs`
- `A` `technical-delivery-pack-v1.0/01-technical-implementation-specification.md`
- `A` `technical-delivery-pack-v1.0/02-implementation-backlog.md`
- `A` `technical-delivery-pack-v1.0/03-delivery-and-quality-checklist.md`
- `A` `technical-delivery-pack-v1.0/04-execution-start-plan.md`
- `A` `technical-delivery-pack-v1.0/README.md`
- `A` `templates/domain-module/README.md`
- `A` `templates/domain-module/backend/DomainAdapter.java.template`
- `A` `templates/domain-module/backend/DomainModule.java.template`
- `A` `templates/domain-module/backend/V__MIGRATION_VERSION____MODULE_KEY_SQL____baseline.sql.template`
- `A` `templates/domain-module/frontend/page.tsx.template`
- `A` `templates/domain-module/module-manifest.yaml.template`
<!-- AUTO-GENERATED:END -->
