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
| Navigation | Application shell hợp nhất, section `business`/`system-administration`, menu động và cây tối đa ba cấp |
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
> Sinh tự động từ Git. Mốc mã gần nhất: `7300b4d70b4db30a1f1eb9b82befcd01b5c686d5` (2026-08-18). Không sửa trực tiếp phần này.

| Ngày | Commit | Nội dung | Tác giả | Số file |
|---|---|---|---|---:|
| 2026-08-18 | [`7300b4d`](https://github.com/sgodev2024/java-core/commit/7300b4d70b4db30a1f1eb9b82befcd01b5c686d5) | Keep business project repository independent | SGO Development | 1 |
| 2026-08-18 | [`682d300`](https://github.com/sgodev2024/java-core/commit/682d30079ccb5f52133bc374223556955f4004c2) | Add project-ready domain module contracts | SGO Development | 17 |
| 2026-08-17 | [`5c51d9b`](https://github.com/sgodev2024/java-core/commit/5c51d9b2d93eae37a0a5c0f1d49e17339065165e) | Normalize legacy core module statuses | SGO Development | 4 |
| 2026-08-17 | [`776e86b`](https://github.com/sgodev2024/java-core/commit/776e86b888e25968029c741d02541baee99366b0) | Align migration concurrency test with V18 | SGO Development | 1 |
| 2026-08-17 | [`5c1c38a`](https://github.com/sgodev2024/java-core/commit/5c1c38a1e777a9a874fb0e0adc589077d864469e) | Fix bootstrap initializer test environment | SGO Development | 1 |
| 2026-08-17 | [`4990614`](https://github.com/sgodev2024/java-core/commit/499061418cabf6557bc3ecc524b90cf8bc31c6cb) | Align ESG frontend with production APIs | SGO Development | 31 |
| 2026-08-17 | [`0f5ec51`](https://github.com/sgodev2024/java-core/commit/0f5ec51cac77974ff5f37741cc894faa07edcea9) | Isolate approval sample behind demo and test profiles | SGO Development | 22 |
| 2026-08-17 | [`ae38ab7`](https://github.com/sgodev2024/java-core/commit/ae38ab7cca381cb1bf9630da7ed2ec106915af9d) | Remove deployment summary strip from home page | SGO Development | 5 |
| 2026-08-17 | [`16e7eab`](https://github.com/sgodev2024/java-core/commit/16e7eab53bc9368d9c9a8e90f0069cc7d29939a5) | Enforce assignment-scoped personal task navigation | SGO Development | 11 |
| 2026-08-17 | [`06ddb15`](https://github.com/sgodev2024/java-core/commit/06ddb1542bd1b7bfb1cfc7eafc0adb7d7bd15eb9) | Preserve navigation preference schema compatibility | SGO Development | 3 |
| 2026-08-17 | [`018c886`](https://github.com/sgodev2024/java-core/commit/018c886578056beaf4a7c44b050aa21326f3b356) | Fix navigation module status query compilation | SGO Development | 1 |
| 2026-08-17 | [`5f298fa`](https://github.com/sgodev2024/java-core/commit/5f298fa92011c27a9c847217e94428cea92e5e89) | Unify navigation shell and implement FE-BA v1.1 | SGO Development | 21 |
| 2026-08-17 | [`e63edc0`](https://github.com/sgodev2024/java-core/commit/e63edc07782810aac19415d0a0900aa1140ace4a) | Migrate frontend to Next.js and add MFA feature flag | SGO Development | 35 |
| 2026-08-17 | [`89e36e6`](https://github.com/sgodev2024/java-core/commit/89e36e68ab5628d63bc7fafe888b48c5d120937d) | Add workspace-based dynamic navigation registry | SGO Development | 15 |
| 2026-08-16 | [`3741888`](https://github.com/sgodev2024/java-core/commit/3741888b8abde726d06438e75f5423150b737d1a) | Add sample domain, full-text search, SSRF-guarded webhooks, CSV idempotency and SBOM (E10+E11+E13) | sgodev2024 | 14 |
| 2026-08-16 | [`3e9eb18`](https://github.com/sgodev2024/java-core/commit/3e9eb18be31f3dac682450c6810f7dd152f2780f) | Add file lifecycle (staging/scan/finalize/reconcile) and dynamic resource hardening (E8+E9) | sgodev2024 | 9 |
| 2026-08-16 | [`9ede356`](https://github.com/sgodev2024/java-core/commit/9ede356a5922396f3891afe95cd2ea49b4365f88) | Add job queue leases, heartbeat, retry classification and scheduler leader election (E7) | sgodev2024 | 14 |
| 2026-08-16 | [`77f4071`](https://github.com/sgodev2024/java-core/commit/77f4071d45f9294983160fd89ead41ad46de8131) | Add transactional outbox, relay leases, inbox idempotency and replay (E6) | sgodev2024 | 17 |
| 2026-08-16 | [`c081a6b`](https://github.com/sgodev2024/java-core/commit/c081a6b1b52658b9921b704ee08300b26e70fa56) | Allow 127.0.0.1 origins in CORS for local UI access | sgodev2024 | 1 |
| 2026-08-16 | [`99d2a5b`](https://github.com/sgodev2024/java-core/commit/99d2a5bd1ea8c9ff16a250c40c086b99ac1b4cdd) | Add audit integrity: transactional audit, masking, hash chain, checkpoint/retention (E5) | sgodev2024 | 14 |
| 2026-08-16 | [`a25a057`](https://github.com/sgodev2024/java-core/commit/a25a0579d5d939ed7f2231bd6cdda288d574bd21) | Add resource registry SPI, PDP/PEP hardening and classification gate (E4) | sgodev2024 | 24 |
| 2026-08-16 | [`87e03ac`](https://github.com/sgodev2024/java-core/commit/87e03ac0df3f4b5cc369c859192220280d038f55) | Add local identity hardening: Argon2id, lockout, TOTP MFA, refresh rotation, service accounts (E3) | sgodev2024 | 12 |
| 2026-08-16 | [`f5420c2`](https://github.com/sgodev2024/java-core/commit/f5420c2c860982a261c5db7eb7891881e917bbfe) | Add database roles, RLS tenant isolation and tenant-aware pooling (E2) | sgodev2024 | 9 |
| 2026-08-16 | [`7092171`](https://github.com/sgodev2024/java-core/commit/70921719a10cbda8ad5240e24c4e7bcc90633920) | Add engineering foundation and kernel module runtime (E0+E1) | sgodev2024 | 32 |
| 2026-08-15 | [`70827f9`](https://github.com/sgodev2024/java-core/commit/70827f97865159178207433e18099d68c122a116) | Add tenant-authorized file storage and streaming | SGO Development | 4 |
| 2026-08-15 | [`de08774`](https://github.com/sgodev2024/java-core/commit/de087746d5ea63a6a10a3c07125739e2f1a03c31) | Remove external CSV runtime dependency | SGO Development | 2 |
| 2026-08-15 | [`3e5d210`](https://github.com/sgodev2024/java-core/commit/3e5d21041bc5f603eda3da5a370e980a6570dd43) | Add Dynamic Resource console and CSV operations | SGO Development | 3 |
| 2026-08-15 | [`2bd78ad`](https://github.com/sgodev2024/java-core/commit/2bd78ad47608a73a7f3f632fc7543c8edda6768a) | Enforce tenant-scoped policy and access management | SGO Development | 5 |
| 2026-08-15 | [`f7e490b`](https://github.com/sgodev2024/java-core/commit/f7e490b2b33441bce6beeec9b9f5d864222ead4d) | Add tenant permission and dynamic resource foundation | SGO Development | 3 |
| 2026-08-15 | [`996a826`](https://github.com/sgodev2024/java-core/commit/996a826daf979c1c635053f2f93f541f5292b1cd) | Implement control plane operations backed by PostgreSQL | SGO Development | 3 |
| 2026-08-15 | [`5cca3c8`](https://github.com/sgodev2024/java-core/commit/5cca3c8b1c0b0c8a5dd77b4f288e0e7bf3bfc94f) | Use compact standalone frontend runtime | SGO Development | 3 |
| 2026-08-15 | [`82f5ac3`](https://github.com/sgodev2024/java-core/commit/82f5ac3e0f201bfa2b48c1b20efaf74f004fcc0b) | Self-host frontend on corejava domain | SGO Development | 3 |
| 2026-08-15 | [`73ff468`](https://github.com/sgodev2024/java-core/commit/73ff46893ee6d0c6c72e37c37f56899bfe8a5b78) | Route production frontend through dedicated API domain | SGO Development | 3 |
| 2026-08-15 | [`b5d5d26`](https://github.com/sgodev2024/java-core/commit/b5d5d26ac2c58e61e5a4c0b84f395f3ae9b02f0b) | Connect frontend to production API | SGO Development | 1 |
| 2026-08-15 | [`6b5f782`](https://github.com/sgodev2024/java-core/commit/6b5f782f6dc519242f09fe54700bc6d34eb22b61) | Fix PostgreSQL summary alias | SGO Development | 1 |
| 2026-08-15 | [`6267349`](https://github.com/sgodev2024/java-core/commit/62673493fed5bc796e77a98021db925c9b5fb250) | Fix session expiry persistence | SGO Development | 1 |
| 2026-08-15 | [`11e6532`](https://github.com/sgodev2024/java-core/commit/11e6532fab7bdaa480d578e9f1a8ff7298b77f3b) | Secure bootstrap admin credentials | SGO Development | 4 |
| 2026-08-15 | [`9b25425`](https://github.com/sgodev2024/java-core/commit/9b254255a044698b96f3b5a39f12ac304caa3e52) | Fix security filter compilation | SGO Development | 1 |
| 2026-08-15 | [`295b0e6`](https://github.com/sgodev2024/java-core/commit/295b0e6d6b200c9cd17cfe23d11683f8d04d19e8) | Initialize Java Core Platform | SGO Development | 57 |

## Chi tiết file theo commit

### 2026-08-18 — Keep business project repository independent

- Commit: [`7300b4d70b4db30a1f1eb9b82befcd01b5c686d5`](https://github.com/sgodev2024/java-core/commit/7300b4d70b4db30a1f1eb9b82befcd01b5c686d5)
- Tác giả: SGO Development
- Phạm vi file:

- `M` `.gitignore`

### 2026-08-18 — Add project-ready domain module contracts

- Commit: [`682d30079ccb5f52133bc374223556955f4004c2`](https://github.com/sgodev2024/java-core/commit/682d30079ccb5f52133bc374223556955f4004c2)
- Tác giả: SGO Development
- Phạm vi file:

- `M` `README.md`
- `A` `backend/src/main/java/vn/coreplatform/kernel/CoreCompatibility.java`
- `A` `backend/src/main/java/vn/coreplatform/kernel/DomainResourceAdapter.java`
- `A` `backend/src/main/java/vn/coreplatform/kernel/DomainResourceAdapterRegistry.java`
- `M` `backend/src/main/java/vn/coreplatform/kernel/ModuleDescriptor.java`
- `M` `backend/src/main/java/vn/coreplatform/kernel/ModuleRegistry.java`
- `A` `backend/src/test/java/vn/coreplatform/kernel/DomainResourceAdapterRegistryTest.java`
- `M` `backend/src/test/java/vn/coreplatform/kernel/ModuleRegistryTest.java`
- `M` `docs/backend-frontend-gap-analysis-v1.0.md`
- `M` `docs/decisions.md`
- `A` `scripts/new-domain-module.ps1`
- `A` `templates/domain-module/README.md`
- `A` `templates/domain-module/backend/DomainAdapter.java.template`
- `A` `templates/domain-module/backend/DomainModule.java.template`
- `A` `templates/domain-module/backend/V__MIGRATION_VERSION____MODULE_KEY_SQL____baseline.sql.template`
- `A` `templates/domain-module/frontend/page.tsx.template`
- `A` `templates/domain-module/module-manifest.yaml.template`

### 2026-08-17 — Normalize legacy core module statuses

- Commit: [`5c51d9b2d93eae37a0a5c0f1d49e17339065165e`](https://github.com/sgodev2024/java-core/commit/5c51d9b2d93eae37a0a5c0f1d49e17339065165e)
- Tác giả: SGO Development
- Phạm vi file:

- `A` `backend/src/main/resources/db/migration/V19__normalize_core_module_runtime_status.sql`
- `M` `backend/src/test/java/vn/coreplatform/controlplane/LegacySeedDataCleanupTest.java`
- `M` `backend/src/test/java/vn/coreplatform/kernel/MigrationCoordinatorTest.java`
- `M` `docs/backend-frontend-gap-analysis-v1.0.md`

### 2026-08-17 — Align migration concurrency test with V18

- Commit: [`776e86b888e25968029c741d02541baee99366b0`](https://github.com/sgodev2024/java-core/commit/776e86b888e25968029c741d02541baee99366b0)
- Tác giả: SGO Development
- Phạm vi file:

- `M` `backend/src/test/java/vn/coreplatform/kernel/MigrationCoordinatorTest.java`

### 2026-08-17 — Fix bootstrap initializer test environment

- Commit: [`5c1c38a1e777a9a874fb0e0adc589077d864469e`](https://github.com/sgodev2024/java-core/commit/5c1c38a1e777a9a874fb0e0adc589077d864469e)
- Tác giả: SGO Development
- Phạm vi file:

- `M` `backend/src/test/java/vn/coreplatform/identity/BootstrapAdminInitializerTest.java`

### 2026-08-17 — Align ESG frontend with production APIs

- Commit: [`499061418cabf6557bc3ecc524b90cf8bc31c6cb`](https://github.com/sgodev2024/java-core/commit/499061418cabf6557bc3ecc524b90cf8bc31c6cb)
- Tác giả: SGO Development
- Phạm vi file:

- `M` `backend/README.md`
- `M` `backend/src/main/java/vn/coreplatform/controlplane/ControlPlaneController.java`
- `M` `backend/src/main/java/vn/coreplatform/controlplane/ControlPlaneModule.java`
- `M` `backend/src/main/java/vn/coreplatform/demo/approval/ApprovalDomainModule.java`
- `M` `backend/src/main/java/vn/coreplatform/filemanagement/FileController.java`
- `A` `backend/src/main/java/vn/coreplatform/filemanagement/FileResourceMetadata.java`
- `A` `backend/src/main/java/vn/coreplatform/identity/BootstrapAdminInitializer.java`
- `D` `backend/src/main/java/vn/coreplatform/identity/DemoAccountInitializer.java`
- `A` `backend/src/main/java/vn/coreplatform/identity/IdentityResourceMetadata.java`
- `M` `backend/src/main/java/vn/coreplatform/kernel/KernelModule.java`
- `M` `backend/src/main/java/vn/coreplatform/kernel/ModuleRegistry.java`
- `M` `backend/src/main/java/vn/coreplatform/kernel/ResourceRegistry.java`
- `M` `backend/src/main/java/vn/coreplatform/permission/AccessManagementController.java`
- `A` `backend/src/main/resources/db/migration/V18__remove_legacy_demo_seed_data.sql`
- `A` `backend/src/test/java/vn/coreplatform/controlplane/LegacySeedDataCleanupTest.java`
- `A` `backend/src/test/java/vn/coreplatform/identity/BootstrapAdminInitializerTest.java`
- `M` `core-platform-ba-requirements-v1.1.md`
- `A` `docs/backend-frontend-gap-analysis-v1.0.md`
- `M` `docs/decisions.md`
- `M` `frontend/README.md`
- `A` `frontend/app/components/app-icon.tsx`
- `M` `frontend/app/demo/approval-workspace.tsx`
- `M` `frontend/app/globals.css`
- `M` `frontend/app/page.tsx`
- `M` `frontend/package-lock.json`
- `M` `frontend/package.json`
- `D` `frontend/postcss.config.mjs`
- `D` `frontend/public/file.svg`
- `D` `frontend/public/globe.svg`
- `D` `frontend/public/window.svg`
- `M` `frontend/tests/rendered-html.test.mjs`

### 2026-08-17 — Isolate approval sample behind demo and test profiles

- Commit: [`0f5ec51cac77974ff5f37741cc894faa07edcea9`](https://github.com/sgodev2024/java-core/commit/0f5ec51cac77974ff5f37741cc894faa07edcea9)
- Tác giả: SGO Development
- Phạm vi file:

- `M` `README.md`
- `M` `backend/README.md`
- `A` `backend/src/main/java/vn/coreplatform/demo/approval/ApprovalDomainModule.java`
- `A` `backend/src/main/java/vn/coreplatform/demo/approval/ApprovalRequestController.java`
- `A` `backend/src/main/java/vn/coreplatform/demo/approval/DemoApprovalMetadata.java`
- `A` `backend/src/main/java/vn/coreplatform/demo/approval/DemoApprovalProductionGuard.java`
- `D` `backend/src/main/java/vn/coreplatform/domain/ApprovalDomainModule.java`
- `D` `backend/src/main/java/vn/coreplatform/domain/ApprovalRequestController.java`
- `A` `backend/src/main/resources/db/migration/V17__isolate_demo_approval_module.sql`
- `M` `backend/src/test/java/vn/coreplatform/AbstractApiTest.java`
- `A` `backend/src/test/java/vn/coreplatform/demo/approval/ApprovalDomainTest.java`
- `A` `backend/src/test/java/vn/coreplatform/demo/approval/DemoApprovalProfileTest.java`
- `D` `backend/src/test/java/vn/coreplatform/domain/ApprovalDomainTest.java`
- `M` `backend/src/test/java/vn/coreplatform/kernel/ModuleBoundaryTest.java`
- `M` `backend/src/test/java/vn/coreplatform/kernel/NavigationApiTest.java`
- `M` `core-platform-ba-requirements-v1.1.md`
- `M` `docs/navigation-registry.md`
- `M` `docs/technical-change-register.md`
- `M` `frontend/README.md`
- `A` `frontend/app/demo/approval-workspace.tsx`
- `M` `frontend/app/page.tsx`
- `M` `frontend/tests/rendered-html.test.mjs`

### 2026-08-17 — Remove deployment summary strip from home page

- Commit: [`ae38ab7cca381cb1bf9630da7ed2ec106915af9d`](https://github.com/sgodev2024/java-core/commit/ae38ab7cca381cb1bf9630da7ed2ec106915af9d)
- Tác giả: SGO Development
- Phạm vi file:

- `M` `core-platform-ba-requirements-v1.1.md`
- `M` `docs/technical-change-register.md`
- `M` `frontend/app/globals.css`
- `M` `frontend/app/page.tsx`
- `M` `frontend/tests/rendered-html.test.mjs`

### 2026-08-17 — Enforce assignment-scoped personal task navigation

- Commit: [`16e7eab53bc9368d9c9a8e90f0069cc7d29939a5`](https://github.com/sgodev2024/java-core/commit/16e7eab53bc9368d9c9a8e90f0069cc7d29939a5)
- Tác giả: SGO Development
- Phạm vi file:

- `M` `backend/src/main/java/vn/coreplatform/kernel/NavigationRegistry.java`
- `M` `backend/src/main/java/vn/coreplatform/navigation/NavigationController.java`
- `A` `backend/src/main/java/vn/coreplatform/navigation/NavigationVisibilityPolicy.java`
- `M` `backend/src/test/java/vn/coreplatform/kernel/NavigationRegistryTest.java`
- `A` `backend/src/test/java/vn/coreplatform/navigation/NavigationVisibilityPolicyTest.java`
- `M` `core-platform-ba-requirements-v1.1.md`
- `M` `docs/navigation-registry.md`
- `M` `docs/technical-change-register.md`
- `M` `frontend/README.md`
- `M` `frontend/app/page.tsx`
- `M` `frontend/tests/rendered-html.test.mjs`

### 2026-08-17 — Preserve navigation preference schema compatibility

- Commit: [`06ddb1542bd1b7bfb1cfc7eafc0adb7d7bd15eb9`](https://github.com/sgodev2024/java-core/commit/06ddb1542bd1b7bfb1cfc7eafc0adb7d7bd15eb9)
- Tác giả: SGO Development
- Phạm vi file:

- `M` `backend/src/main/java/vn/coreplatform/navigation/NavigationController.java`
- `M` `docs/navigation-registry.md`
- `M` `docs/technical-change-register.md`

### 2026-08-17 — Fix navigation module status query compilation

- Commit: [`018c886578056beaf4a7c44b050aa21326f3b356`](https://github.com/sgodev2024/java-core/commit/018c886578056beaf4a7c44b050aa21326f3b356)
- Tác giả: SGO Development
- Phạm vi file:

- `M` `backend/src/main/java/vn/coreplatform/navigation/NavigationController.java`

### 2026-08-17 — Unify navigation shell and implement FE-BA v1.1

- Commit: [`5f298fa92011c27a9c847217e94428cea92e5e89`](https://github.com/sgodev2024/java-core/commit/5f298fa92011c27a9c847217e94428cea92e5e89)
- Tác giả: SGO Development
- Phạm vi file:

- `M` `README.md`
- `M` `backend/src/main/java/vn/coreplatform/controlplane/ControlPlaneModule.java`
- `M` `backend/src/main/java/vn/coreplatform/domain/ApprovalDomainModule.java`
- `M` `backend/src/main/java/vn/coreplatform/kernel/KernelModule.java`
- `M` `backend/src/main/java/vn/coreplatform/kernel/NavigationItemDescriptor.java`
- `M` `backend/src/main/java/vn/coreplatform/kernel/NavigationRegistry.java`
- `M` `backend/src/main/java/vn/coreplatform/navigation/NavigationController.java`
- `M` `backend/src/main/java/vn/coreplatform/permission/PermissionService.java`
- `M` `backend/src/test/java/vn/coreplatform/kernel/NavigationApiTest.java`
- `M` `backend/src/test/java/vn/coreplatform/kernel/NavigationRegistryTest.java`
- `M` `backend/src/test/java/vn/coreplatform/permission/PermissionTest.java`
- `A` `core-platform-ba-requirements-v1.1.md`
- `M` `docs/navigation-registry.md`
- `M` `docs/technical-change-register.md`
- `M` `frontend/README.md`
- `A` `frontend/app/administration/[...path]/page.tsx`
- `A` `frontend/app/business/[...path]/page.tsx`
- `M` `frontend/app/globals.css`
- `A` `frontend/app/home/page.tsx`
- `M` `frontend/app/page.tsx`
- `M` `technical-delivery-pack-v1.0/README.md`

### 2026-08-17 — Migrate frontend to Next.js and add MFA feature flag

- Commit: [`e63edc07782810aac19415d0a0900aa1140ace4a`](https://github.com/sgodev2024/java-core/commit/e63edc07782810aac19415d0a0900aa1140ace4a)
- Tác giả: SGO Development
- Phạm vi file:

- `A` `.github/workflows/technical-change-log.yml`
- `M` `README.md`
- `M` `backend/.env.example`
- `M` `backend/README.md`
- `M` `backend/src/main/java/vn/coreplatform/identity/AuthController.java`
- `M` `backend/src/main/resources/application.yml`
- `M` `backend/src/test/java/vn/coreplatform/AbstractApiTest.java`
- `A` `backend/src/test/java/vn/coreplatform/identity/MfaDisabledLoginTest.java`
- `M` `deploy/ubuntu20/core-platform.env.example`
- `M` `docker-compose.yml`
- `A` `docs/technical-change-register.md`
- `M` `frontend/.dockerignore`
- `M` `frontend/.gitignore`
- `D` `frontend/.openai/hosting.json`
- `M` `frontend/Dockerfile`
- `M` `frontend/README.md`
- `D` `frontend/app/chatgpt-auth.ts`
- `M` `frontend/app/globals.css`
- `M` `frontend/app/layout.tsx`
- `M` `frontend/app/page.tsx`
- `D` `frontend/build/sites-vite-plugin.ts`
- `D` `frontend/db/index.ts`
- `D` `frontend/db/schema.ts`
- `D` `frontend/drizzle.config.ts`
- `D` `frontend/drizzle/meta/_journal.json`
- `M` `frontend/eslint.config.mjs`
- `D` `frontend/examples/d1/app/api/notes/route.ts`
- `D` `frontend/examples/d1/db/schema.ts`
- `M` `frontend/next.config.ts`
- `M` `frontend/package-lock.json`
- `M` `frontend/package.json`
- `M` `frontend/tests/rendered-html.test.mjs`
- `D` `frontend/vite.config.ts`
- `D` `frontend/worker/index.ts`
- `A` `scripts/update-technical-change-log.mjs`

### 2026-08-17 — Add workspace-based dynamic navigation registry

- Commit: [`89e36e68ab5628d63bc7fafe888b48c5d120937d`](https://github.com/sgodev2024/java-core/commit/89e36e68ab5628d63bc7fafe888b48c5d120937d)
- Tác giả: SGO Development
- Phạm vi file:

- `M` `README.md`
- `M` `backend/src/main/java/vn/coreplatform/controlplane/ControlPlaneModule.java`
- `M` `backend/src/main/java/vn/coreplatform/domain/ApprovalDomainModule.java`
- `M` `backend/src/main/java/vn/coreplatform/kernel/KernelModule.java`
- `M` `backend/src/main/java/vn/coreplatform/kernel/ModuleContributor.java`
- `A` `backend/src/main/java/vn/coreplatform/kernel/NavigationItemDescriptor.java`
- `A` `backend/src/main/java/vn/coreplatform/kernel/NavigationRegistry.java`
- `A` `backend/src/main/java/vn/coreplatform/kernel/NavigationWorkspaceDescriptor.java`
- `A` `backend/src/main/java/vn/coreplatform/navigation/NavigationController.java`
- `A` `backend/src/main/resources/db/migration/V16__navigation_registry_preferences.sql`
- `A` `backend/src/test/java/vn/coreplatform/kernel/NavigationApiTest.java`
- `A` `backend/src/test/java/vn/coreplatform/kernel/NavigationRegistryTest.java`
- `A` `docs/navigation-registry.md`
- `M` `frontend/app/globals.css`
- `M` `frontend/app/page.tsx`

### 2026-08-16 — Add sample domain, full-text search, SSRF-guarded webhooks, CSV idempotency and SBOM (E10+E11+E13)

- Commit: [`3741888b8abde726d06438e75f5423150b737d1a`](https://github.com/sgodev2024/java-core/commit/3741888b8abde726d06438e75f5423150b737d1a)
- Tác giả: sgodev2024
- Phạm vi file:

- `M` `backend/pom.xml`
- `A` `backend/src/main/java/vn/coreplatform/domain/ApprovalDomainModule.java`
- `A` `backend/src/main/java/vn/coreplatform/domain/ApprovalRequestController.java`
- `M` `backend/src/main/java/vn/coreplatform/dynamicresource/DynamicResourceController.java`
- `A` `backend/src/main/java/vn/coreplatform/webhook/WebhookController.java`
- `A` `backend/src/main/java/vn/coreplatform/webhook/WebhookModule.java`
- `A` `backend/src/main/java/vn/coreplatform/webhook/WebhookService.java`
- `A` `backend/src/main/resources/db/migration/V14__sample_domain_e10.sql`
- `A` `backend/src/main/resources/db/migration/V15__search_webhook_e11.sql`
- `M` `backend/src/test/java/vn/coreplatform/controlplane/ControlPlaneTest.java`
- `A` `backend/src/test/java/vn/coreplatform/domain/ApprovalDomainTest.java`
- `M` `backend/src/test/java/vn/coreplatform/kernel/MigrationCoordinatorTest.java`
- `M` `backend/src/test/java/vn/coreplatform/kernel/ModuleBoundaryTest.java`
- `A` `backend/src/test/java/vn/coreplatform/webhook/WebhookAndSearchTest.java`

### 2026-08-16 — Add file lifecycle (staging/scan/finalize/reconcile) and dynamic resource hardening (E8+E9)

- Commit: [`3e9eb18be31f3dac682450c6810f7dd152f2780f`](https://github.com/sgodev2024/java-core/commit/3e9eb18be31f3dac682450c6810f7dd152f2780f)
- Tác giả: sgodev2024
- Phạm vi file:

- `A` `backend/src/main/java/vn/coreplatform/dynamicresource/DynamicResourceAdminController.java`
- `M` `backend/src/main/java/vn/coreplatform/dynamicresource/DynamicResourceController.java`
- `M` `backend/src/main/java/vn/coreplatform/filemanagement/FileController.java`
- `A` `backend/src/main/java/vn/coreplatform/filemanagement/FileStorageService.java`
- `A` `backend/src/main/resources/db/migration/V12__file_lifecycle_e8.sql`
- `A` `backend/src/main/resources/db/migration/V13__dynamic_advanced_e9.sql`
- `A` `backend/src/test/java/vn/coreplatform/dynamicresource/DynamicResourceAdvancedTest.java`
- `A` `backend/src/test/java/vn/coreplatform/filemanagement/FileLifecycleTest.java`
- `M` `backend/src/test/java/vn/coreplatform/kernel/MigrationCoordinatorTest.java`

### 2026-08-16 — Add job queue leases, heartbeat, retry classification and scheduler leader election (E7)

- Commit: [`9ede356a5922396f3891afe95cd2ea49b4365f88`](https://github.com/sgodev2024/java-core/commit/9ede356a5922396f3891afe95cd2ea49b4365f88)
- Tác giả: sgodev2024
- Phạm vi file:

- `M` `backend/README.md`
- `A` `backend/src/main/java/vn/coreplatform/audit/AuditCheckpointHandler.java`
- `M` `backend/src/main/java/vn/coreplatform/controlplane/ControlPlaneController.java`
- `A` `backend/src/main/java/vn/coreplatform/jobs/JobHandler.java`
- `A` `backend/src/main/java/vn/coreplatform/jobs/JobScheduler.java`
- `A` `backend/src/main/java/vn/coreplatform/jobs/JobService.java`
- `A` `backend/src/main/java/vn/coreplatform/jobs/JobWorker.java`
- `A` `backend/src/main/java/vn/coreplatform/jobs/JobsModule.java`
- `M` `backend/src/main/resources/application.yml`
- `A` `backend/src/main/resources/db/migration/V11__jobs_scheduler_e7.sql`
- `A` `backend/src/test/java/vn/coreplatform/jobs/JobQueueSchedulerTest.java`
- `M` `backend/src/test/java/vn/coreplatform/kernel/MigrationCoordinatorTest.java`
- `M` `backend/src/test/java/vn/coreplatform/kernel/ModuleBoundaryTest.java`
- `M` `docker-compose.yml`

### 2026-08-16 — Add transactional outbox, relay leases, inbox idempotency and replay (E6)

- Commit: [`77f4071d45f9294983160fd89ead41ad46de8131`](https://github.com/sgodev2024/java-core/commit/77f4071d45f9294983160fd89ead41ad46de8131)
- Tác giả: sgodev2024
- Phạm vi file:

- `M` `backend/README.md`
- `A` `backend/src/main/java/vn/coreplatform/controlplane/ActivityProjector.java`
- `M` `backend/src/main/java/vn/coreplatform/controlplane/ControlPlaneController.java`
- `M` `backend/src/main/java/vn/coreplatform/dynamicresource/DynamicResourceController.java`
- `A` `backend/src/main/java/vn/coreplatform/eventing/EventingModule.java`
- `A` `backend/src/main/java/vn/coreplatform/eventing/IntegrationEvent.java`
- `A` `backend/src/main/java/vn/coreplatform/eventing/IntegrationEventHandler.java`
- `A` `backend/src/main/java/vn/coreplatform/eventing/OutboxRelay.java`
- `A` `backend/src/main/java/vn/coreplatform/eventing/OutboxService.java`
- `M` `backend/src/main/resources/application.yml`
- `A` `backend/src/main/resources/db/migration/V10__eventing_outbox_inbox_e6.sql`
- `A` `backend/src/test/java/vn/coreplatform/eventing/EventContractTest.java`
- `A` `backend/src/test/java/vn/coreplatform/eventing/OutboxRelayTest.java`
- `A` `backend/src/test/java/vn/coreplatform/eventing/OutboxTransactionTest.java`
- `M` `backend/src/test/java/vn/coreplatform/kernel/MigrationCoordinatorTest.java`
- `M` `backend/src/test/java/vn/coreplatform/kernel/ModuleBoundaryTest.java`
- `M` `docker-compose.yml`

### 2026-08-16 — Allow 127.0.0.1 origins in CORS for local UI access

- Commit: [`c081a6b1b52658b9921b704ee08300b26e70fa56`](https://github.com/sgodev2024/java-core/commit/c081a6b1b52658b9921b704ee08300b26e70fa56)
- Tác giả: sgodev2024
- Phạm vi file:

- `M` `backend/src/main/java/vn/coreplatform/security/SecurityConfig.java`

### 2026-08-16 — Add audit integrity: transactional audit, masking, hash chain, checkpoint/retention (E5)

- Commit: [`99d2a5bd1ea8c9ff16a250c40c086b99ac1b4cdd`](https://github.com/sgodev2024/java-core/commit/99d2a5bd1ea8c9ff16a250c40c086b99ac1b4cdd)
- Tác giả: sgodev2024
- Phạm vi file:

- `M` `backend/README.md`
- `A` `backend/src/main/java/vn/coreplatform/audit/AuditModule.java`
- `A` `backend/src/main/java/vn/coreplatform/audit/AuditService.java`
- `M` `backend/src/main/java/vn/coreplatform/controlplane/ControlPlaneController.java`
- `M` `backend/src/main/java/vn/coreplatform/dynamicresource/DynamicResourceController.java`
- `M` `backend/src/main/java/vn/coreplatform/filemanagement/FileController.java`
- `M` `backend/src/main/java/vn/coreplatform/identity/AuthController.java`
- `M` `backend/src/main/java/vn/coreplatform/permission/AccessManagementController.java`
- `M` `backend/src/main/java/vn/coreplatform/permission/PermissionService.java`
- `A` `backend/src/main/resources/db/migration/V9__audit_integrity_e5.sql`
- `A` `backend/src/test/java/vn/coreplatform/audit/AuditIntegrityTest.java`
- `M` `backend/src/test/java/vn/coreplatform/controlplane/ControlPlaneTest.java`
- `M` `backend/src/test/java/vn/coreplatform/kernel/MigrationCoordinatorTest.java`
- `M` `backend/src/test/java/vn/coreplatform/kernel/ModuleBoundaryTest.java`

### 2026-08-16 — Add resource registry SPI, PDP/PEP hardening and classification gate (E4)

- Commit: [`a25a0579d5d939ed7f2231bd6cdda288d574bd21`](https://github.com/sgodev2024/java-core/commit/a25a0579d5d939ed7f2231bd6cdda288d574bd21)
- Tác giả: sgodev2024
- Phạm vi file:

- `M` `README.md`
- `M` `backend/README.md`
- `M` `backend/src/main/java/vn/coreplatform/controlplane/ControlPlaneController.java`
- `M` `backend/src/main/java/vn/coreplatform/dynamicresource/DynamicResourceController.java`
- `A` `backend/src/main/java/vn/coreplatform/kernel/ResourceDescriptor.java`
- `A` `backend/src/main/java/vn/coreplatform/kernel/ResourceRegistry.java`
- `A` `backend/src/main/java/vn/coreplatform/permission/PermissionEnforcementInterceptor.java`
- `M` `backend/src/main/java/vn/coreplatform/permission/PermissionService.java`
- `A` `backend/src/main/java/vn/coreplatform/permission/RequirePermission.java`
- `A` `backend/src/main/resources/db/migration/V8__resource_registry_e4.sql`
- `A` `backend/src/test/java/vn/coreplatform/controlplane/ControlPlaneTest.java`
- `A` `backend/src/test/java/vn/coreplatform/dynamicresource/ClassificationGateTest.java`
- `A` `backend/src/test/java/vn/coreplatform/dynamicresource/DynamicResourceTest.java`
- `A` `backend/src/test/java/vn/coreplatform/kernel/ResourceRegistryTest.java`
- `A` `backend/src/test/java/vn/coreplatform/permission/PepFailClosedTest.java`
- `A` `backend/src/test/java/vn/coreplatform/permission/PermissionPredicateTest.java`
- `A` `backend/src/test/java/vn/coreplatform/permission/PermissionTest.java`
- `A` `backend/src/test/java/vn/coreplatform/permission/TenantIsolationTest.java`
- `A` `docs/adr/adr-template.md`
- `A` `docs/decisions.md`
- `A` `frontend/.env.example`
- `M` `frontend/.gitignore`
- `M` `frontend/app/page.tsx`
- `M` `frontend/tests/rendered-html.test.mjs`

### 2026-08-16 — Add local identity hardening: Argon2id, lockout, TOTP MFA, refresh rotation, service accounts (E3)

- Commit: [`87e03ac0df3f4b5cc369c859192220280d038f55`](https://github.com/sgodev2024/java-core/commit/87e03ac0df3f4b5cc369c859192220280d038f55)
- Tác giả: sgodev2024
- Phạm vi file:

- `M` `backend/src/main/java/vn/coreplatform/identity/AuthController.java`
- `M` `backend/src/main/java/vn/coreplatform/identity/DemoAccountInitializer.java`
- `A` `backend/src/main/java/vn/coreplatform/identity/Totp.java`
- `M` `backend/src/main/java/vn/coreplatform/permission/AccessManagementController.java`
- `M` `backend/src/main/java/vn/coreplatform/security/SecurityConfig.java`
- `A` `backend/src/main/resources/db/migration/V7__identity_tenant_e3.sql`
- `A` `backend/src/test/java/vn/coreplatform/identity/MfaEnrollFlowTest.java`
- `A` `backend/src/test/java/vn/coreplatform/identity/MfaEnrollmentTest.java`
- `A` `backend/src/test/java/vn/coreplatform/identity/PasswordPolicyTest.java`
- `A` `backend/src/test/java/vn/coreplatform/identity/RefreshRotationTest.java`
- `A` `backend/src/test/java/vn/coreplatform/identity/ServiceAccountTest.java`
- `A` `backend/src/test/java/vn/coreplatform/permission/TenantOrganizationTest.java`

### 2026-08-16 — Add database roles, RLS tenant isolation and tenant-aware pooling (E2)

- Commit: [`f5420c2c860982a261c5db7eb7891881e917bbfe`](https://github.com/sgodev2024/java-core/commit/f5420c2c860982a261c5db7eb7891881e917bbfe)
- Tác giả: sgodev2024
- Phạm vi file:

- `M` `backend/Dockerfile`
- `A` `backend/src/main/java/vn/coreplatform/kernel/TenantAwareDataSource.java`
- `A` `backend/src/main/java/vn/coreplatform/kernel/TenantContext.java`
- `A` `backend/src/main/java/vn/coreplatform/kernel/TenantContextFilter.java`
- `A` `backend/src/main/resources/db/migration/V6__kernel_roles_rls.sql`
- `A` `backend/src/test/java/vn/coreplatform/kernel/RowLevelSecurityTest.java`
- `A` `backend/src/test/java/vn/coreplatform/kernel/TenantDataSourceLeakTest.java`
- `A` `deploy/postgres/01-core-roles.sql`
- `M` `docker-compose.yml`

### 2026-08-16 — Add engineering foundation and kernel module runtime (E0+E1)

- Commit: [`70921719a10cbda8ad5240e24c4e7bcc90633920`](https://github.com/sgodev2024/java-core/commit/70921719a10cbda8ad5240e24c4e7bcc90633920)
- Tác giả: sgodev2024
- Phạm vi file:

- `A` `.github/CODEOWNERS`
- `A` `.github/PULL_REQUEST_TEMPLATE.md`
- `A` `.github/workflows/backend-ci.yml`
- `A` `.github/workflows/frontend-ci.yml`
- `A` `backend/.mvn/wrapper/maven-wrapper.properties`
- `A` `backend/mvnw`
- `A` `backend/mvnw.cmd`
- `M` `backend/pom.xml`
- `A` `backend/src/main/java/vn/coreplatform/controlplane/ControlPlaneModule.java`
- `A` `backend/src/main/java/vn/coreplatform/dynamicresource/DynamicResourceModule.java`
- `M` `backend/src/main/java/vn/coreplatform/filemanagement/FileController.java`
- `A` `backend/src/main/java/vn/coreplatform/filemanagement/FileManagementModule.java`
- `A` `backend/src/main/java/vn/coreplatform/identity/IdentityModule.java`
- `A` `backend/src/main/java/vn/coreplatform/kernel/KernelModule.java`
- `A` `backend/src/main/java/vn/coreplatform/kernel/MigrationCoordinator.java`
- `A` `backend/src/main/java/vn/coreplatform/kernel/ModuleContributor.java`
- `A` `backend/src/main/java/vn/coreplatform/kernel/ModuleDescriptor.java`
- `A` `backend/src/main/java/vn/coreplatform/kernel/ModuleRegistry.java`
- `A` `backend/src/main/java/vn/coreplatform/permission/PermissionModule.java`
- `M` `backend/src/main/java/vn/coreplatform/shared/ApiExceptionHandler.java`
- `A` `backend/src/main/java/vn/coreplatform/shared/CorrelationIdFilter.java`
- `M` `backend/src/main/resources/application.yml`
- `A` `backend/src/test/java/vn/coreplatform/AbstractApiTest.java`
- `A` `backend/src/test/java/vn/coreplatform/boundaryfixture/dynamicresource/DynamicResourceBoundaryViolation.java`
- `A` `backend/src/test/java/vn/coreplatform/boundaryfixture/identity/IdentityBoundaryViolation.java`
- `A` `backend/src/test/java/vn/coreplatform/filemanagement/FileManagementTest.java`
- `A` `backend/src/test/java/vn/coreplatform/identity/AuthFlowTest.java`
- `A` `backend/src/test/java/vn/coreplatform/kernel/MigrationCoordinatorTest.java`
- `A` `backend/src/test/java/vn/coreplatform/kernel/ModuleBoundaryTest.java`
- `A` `backend/src/test/java/vn/coreplatform/kernel/ModuleRegistrationTest.java`
- `A` `backend/src/test/java/vn/coreplatform/kernel/ModuleRegistryTest.java`
- `A` `backend/src/test/java/vn/coreplatform/shared/AuditCorrelationTest.java`

### 2026-08-15 — Add tenant-authorized file storage and streaming

- Commit: [`70827f97865159178207433e18099d68c122a116`](https://github.com/sgodev2024/java-core/commit/70827f97865159178207433e18099d68c122a116)
- Tác giả: SGO Development
- Phạm vi file:

- `A` `backend/src/main/java/vn/coreplatform/filemanagement/FileController.java`
- `M` `backend/src/main/resources/application.yml`
- `A` `backend/src/main/resources/db/migration/V5__file_storage.sql`
- `M` `frontend/app/page.tsx`

### 2026-08-15 — Remove external CSV runtime dependency

- Commit: [`de087746d5ea63a6a10a3c07125739e2f1a03c31`](https://github.com/sgodev2024/java-core/commit/de087746d5ea63a6a10a3c07125739e2f1a03c31)
- Tác giả: SGO Development
- Phạm vi file:

- `M` `backend/pom.xml`
- `M` `backend/src/main/java/vn/coreplatform/dynamicresource/DynamicResourceController.java`

### 2026-08-15 — Add Dynamic Resource console and CSV operations

- Commit: [`3e5d21041bc5f603eda3da5a370e980a6570dd43`](https://github.com/sgodev2024/java-core/commit/3e5d21041bc5f603eda3da5a370e980a6570dd43)
- Tác giả: SGO Development
- Phạm vi file:

- `M` `backend/pom.xml`
- `M` `backend/src/main/java/vn/coreplatform/dynamicresource/DynamicResourceController.java`
- `M` `frontend/app/page.tsx`

### 2026-08-15 — Enforce tenant-scoped policy and access management

- Commit: [`2bd78ad47608a73a7f3f632fc7543c8edda6768a`](https://github.com/sgodev2024/java-core/commit/2bd78ad47608a73a7f3f632fc7543c8edda6768a)
- Tác giả: SGO Development
- Phạm vi file:

- `M` `backend/src/main/java/vn/coreplatform/dynamicresource/DynamicResourceController.java`
- `A` `backend/src/main/java/vn/coreplatform/permission/AccessManagementController.java`
- `A` `backend/src/main/java/vn/coreplatform/permission/PermissionService.java`
- `M` `backend/src/main/java/vn/coreplatform/security/SecurityConfig.java`
- `A` `backend/src/main/resources/db/migration/V4__permission_management.sql`

### 2026-08-15 — Add tenant permission and dynamic resource foundation

- Commit: [`f7e490b2b33441bce6beeec9b9f5d864222ead4d`](https://github.com/sgodev2024/java-core/commit/f7e490b2b33441bce6beeec9b9f5d864222ead4d)
- Tác giả: SGO Development
- Phạm vi file:

- `A` `backend/src/main/java/vn/coreplatform/dynamicresource/DynamicResourceController.java`
- `M` `backend/src/main/java/vn/coreplatform/security/SecurityConfig.java`
- `A` `backend/src/main/resources/db/migration/V3__tenant_permission_dynamic_resource.sql`

### 2026-08-15 — Implement control plane operations backed by PostgreSQL

- Commit: [`996a826daf979c1c635053f2f93f541f5292b1cd`](https://github.com/sgodev2024/java-core/commit/996a826daf979c1c635053f2f93f541f5292b1cd)
- Tác giả: SGO Development
- Phạm vi file:

- `M` `backend/src/main/java/vn/coreplatform/controlplane/ControlPlaneController.java`
- `A` `backend/src/main/resources/db/migration/V2__control_plane_operations.sql`
- `M` `frontend/app/page.tsx`

### 2026-08-15 — Use compact standalone frontend runtime

- Commit: [`5cca3c8b1c0b0c8a5dd77b4f288e0e7bf3bfc94f`](https://github.com/sgodev2024/java-core/commit/5cca3c8b1c0b0c8a5dd77b4f288e0e7bf3bfc94f)
- Tác giả: SGO Development
- Phạm vi file:

- `M` `frontend/.dockerignore`
- `M` `frontend/Dockerfile`
- `M` `frontend/next.config.ts`

### 2026-08-15 — Self-host frontend on corejava domain

- Commit: [`82f5ac3e0f201bfa2b48c1b20efaf74f004fcc0b`](https://github.com/sgodev2024/java-core/commit/82f5ac3e0f201bfa2b48c1b20efaf74f004fcc0b)
- Tác giả: SGO Development
- Phạm vi file:

- `A` `deploy/ubuntu20/nginx-frontend-corejava.conf`
- `A` `frontend/.dockerignore`
- `A` `frontend/Dockerfile`

### 2026-08-15 — Route production frontend through dedicated API domain

- Commit: [`73ff46893ee6d0c6c72e37c37f56899bfe8a5b78`](https://github.com/sgodev2024/java-core/commit/73ff46893ee6d0c6c72e37c37f56899bfe8a5b78)
- Tác giả: SGO Development
- Phạm vi file:

- `M` `backend/src/main/java/vn/coreplatform/security/SecurityConfig.java`
- `A` `deploy/ubuntu20/nginx-api-corejava.conf`
- `M` `frontend/app/page.tsx`

### 2026-08-15 — Connect frontend to production API

- Commit: [`b5d5d26ac2c58e61e5a4c0b84f395f3ae9b02f0b`](https://github.com/sgodev2024/java-core/commit/b5d5d26ac2c58e61e5a4c0b84f395f3ae9b02f0b)
- Tác giả: SGO Development
- Phạm vi file:

- `M` `frontend/app/page.tsx`

### 2026-08-15 — Fix PostgreSQL summary alias

- Commit: [`6b5f782f6dc519242f09fe54700bc6d34eb22b61`](https://github.com/sgodev2024/java-core/commit/6b5f782f6dc519242f09fe54700bc6d34eb22b61)
- Tác giả: SGO Development
- Phạm vi file:

- `M` `backend/src/main/java/vn/coreplatform/controlplane/ControlPlaneController.java`

### 2026-08-15 — Fix session expiry persistence

- Commit: [`62673493fed5bc796e77a98021db925c9b5fb250`](https://github.com/sgodev2024/java-core/commit/62673493fed5bc796e77a98021db925c9b5fb250)
- Tác giả: SGO Development
- Phạm vi file:

- `M` `backend/src/main/java/vn/coreplatform/identity/AuthController.java`

### 2026-08-15 — Secure bootstrap admin credentials

- Commit: [`11e6532fab7bdaa480d578e9f1a8ff7298b77f3b`](https://github.com/sgodev2024/java-core/commit/11e6532fab7bdaa480d578e9f1a8ff7298b77f3b)
- Tác giả: SGO Development
- Phạm vi file:

- `M` `backend/.env.example`
- `M` `backend/src/main/java/vn/coreplatform/identity/AuthController.java`
- `M` `backend/src/main/java/vn/coreplatform/identity/DemoAccountInitializer.java`
- `M` `backend/src/main/resources/application.yml`

### 2026-08-15 — Fix security filter compilation

- Commit: [`9b254255a044698b96f3b5a39f12ac304caa3e52`](https://github.com/sgodev2024/java-core/commit/9b254255a044698b96f3b5a39f12ac304caa3e52)
- Tác giả: SGO Development
- Phạm vi file:

- `M` `backend/src/main/java/vn/coreplatform/security/SecurityConfig.java`

### 2026-08-15 — Initialize Java Core Platform

- Commit: [`295b0e6d6b200c9cd17cfe23d11683f8d04d19e8`](https://github.com/sgodev2024/java-core/commit/295b0e6d6b200c9cd17cfe23d11683f8d04d19e8)
- Tác giả: SGO Development
- Phạm vi file:

- `A` `.gitignore`
- `A` `README.md`
- `A` `backend/.env.example`
- `A` `backend/Dockerfile`
- `A` `backend/README.md`
- `A` `backend/pom.xml`
- `A` `backend/src/main/java/vn/coreplatform/CorePlatformApplication.java`
- `A` `backend/src/main/java/vn/coreplatform/controlplane/ControlPlaneController.java`
- `A` `backend/src/main/java/vn/coreplatform/identity/AuthController.java`
- `A` `backend/src/main/java/vn/coreplatform/identity/DemoAccountInitializer.java`
- `A` `backend/src/main/java/vn/coreplatform/security/SecurityConfig.java`
- `A` `backend/src/main/java/vn/coreplatform/shared/ApiExceptionHandler.java`
- `A` `backend/src/main/resources/application.yml`
- `A` `backend/src/main/resources/db/migration/V1__platform_baseline.sql`
- `A` `core-platform-architecture-standard-v1.1.md`
- `A` `core-platform-ba-requirements-v1.0.md`
- `A` `core-platform-database-architecture-v1.0.md`
- `A` `core-platform-runtime-architecture-v1.0.md`
- `A` `deploy/ubuntu20/README.md`
- `A` `deploy/ubuntu20/core-platform.env.example`
- `A` `deploy/ubuntu20/core-platform.service`
- `A` `deploy/ubuntu20/deploy.sh`
- `A` `deploy/ubuntu20/nginx-core-platform.conf`
- `A` `docker-compose.yml`
- `A` `frontend/.gitignore`
- `A` `frontend/.openai/hosting.json`
- `A` `frontend/README.md`
- `A` `frontend/app/chatgpt-auth.ts`
- `A` `frontend/app/globals.css`
- `A` `frontend/app/layout.tsx`
- `A` `frontend/app/page.tsx`
- `A` `frontend/build/sites-vite-plugin.ts`
- `A` `frontend/db/index.ts`
- `A` `frontend/db/schema.ts`
- `A` `frontend/drizzle.config.ts`
- `A` `frontend/drizzle/meta/_journal.json`
- `A` `frontend/eslint.config.mjs`
- `A` `frontend/examples/d1/app/api/notes/route.ts`
- `A` `frontend/examples/d1/db/schema.ts`
- `A` `frontend/next.config.ts`
- `A` `frontend/package-lock.json`
- `A` `frontend/package.json`
- `A` `frontend/postcss.config.mjs`
- `A` `frontend/public/favicon.svg`
- `A` `frontend/public/file.svg`
- `A` `frontend/public/globe.svg`
- `A` `frontend/public/og.png`
- `A` `frontend/public/window.svg`
- `A` `frontend/tests/rendered-html.test.mjs`
- `A` `frontend/tsconfig.json`
- `A` `frontend/vite.config.ts`
- `A` `frontend/worker/index.ts`
- `A` `technical-delivery-pack-v1.0/01-technical-implementation-specification.md`
- `A` `technical-delivery-pack-v1.0/02-implementation-backlog.md`
- `A` `technical-delivery-pack-v1.0/03-delivery-and-quality-checklist.md`
- `A` `technical-delivery-pack-v1.0/04-execution-start-plan.md`
- `A` `technical-delivery-pack-v1.0/README.md`
<!-- AUTO-GENERATED:END -->
