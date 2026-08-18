# Dependency & Version Decision Register

Quy tắc (technical-delivery-pack-v1.0/01-technical-implementation-specification.md):
không thêm dependency mới nếu chưa ghi lý do, license và operational impact tại đây.
Version được pin qua `backend/pom.xml`; cấm dynamic version.

| Ngày | Dependency | Version | Scope | Lý do | License | Ghi chú vận hành |
|---|---|---|---|---|---|---|
| 2026-08-14 | Spring Boot (parent) | 3.5.4 | runtime | Baseline framework đã chốt trong CP-ARCH-002 | EPL-2.0/ORR | — |
| 2026-08-14 | PostgreSQL JDBC | quản lý bởi Boot BOM | runtime | Database chuẩn của platform | BSD-2 | — |
| 2026-08-14 | Flyway core + postgresql | quản lý bởi Boot BOM | runtime | Migration có version theo CP-DATA-003 | Apache-2.0 | — |
| 2026-08-14 | springdoc-openapi-webmvc-ui | 2.8.9 | runtime | API contract/Swagger cho developer (CAP-022) | Apache-2.0 | Tắt ở production nếu không cần |
| 2026-08-15 | spring-boot-testcontainers | quản lý bởi Boot BOM | test | `@ServiceConnection` cho integration test | Apache-2.0 | Cần Docker khi chạy test |
| 2026-08-15 | testcontainers junit-jupiter + postgresql | 1.21.4 (pin qua property `testcontainers.version`) | test | Integration test với PostgreSQL thật; 1.21.3 lỗi thương lượng API với Docker Engine 29 (400 trên /info) | MIT | Windows cần `DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine` + `TESTCONTAINERS_RYUK_DISABLED=true` (Ryuk qua npipe có thể giết container giữa chừng); CI Linux không cần |
| 2026-08-16 | ArchUnit (archunit-junit5) | 1.4.1 | test | Boundary verification E1-S02: kernel/shared trung tính, module không chạm nội bộ nhau, fixture vi phạm cố ý làm rule fail | Apache-2.0 | Spring Modulith hoãn đến khi tách Maven multi-module (sprint sau) — ghi ở đây để không quên |
| 2026-08-16 | Flyway programmatic qua `MigrationCoordinator` | quản lý bởi Boot BOM | runtime | E1-S04: advisory lock (`pg_advisory_lock`) serialize migration khi nhiều instance bật đồng thời; chạy BeanFactoryPostProcessor để thấy đủ Environment (kể cả DynamicPropertySource của test) trước khi bean tạo | Apache-2.0 | Tắt `spring.flyway` auto; credential migration tách biệt `DB_MIGRATION_USER` |
| 2026-08-16 | RLS ENABLE+FORCE + GUC `core.tenant_id` | PostgreSQL 17 built-in | runtime | E2-S03: isolation ở tầng database cho `dynamic_resource.*` và `files.file_object`; app gắn GUC mỗi lần lease connection và reset khi trả pool (`TenantAwareDataSource`) | PostgreSQL License | Role runtime `core_app` không DDL/owner/BYPASSRLS; thiếu GUC → 0 dòng (fail-closed) |
| 2026-08-16 | BouncyCastle bcprov-jdk18on | 1.81 | runtime | E3-S02: `Argon2PasswordEncoder` của spring-security-crypto cần BC để chạy Argon2id; Boot không quản lý version nên pin tay | MIT | Hash mới`{argon2}`; hash `{bcrypt}` cũ verify được và tự rehash khi login |
| 2026-08-15 | Maven Wrapper | 3.9.11 | build | `mvnw verify` chuẩn hóa mọi máy/CI (E0-S01) | Apache-2.0 | distributionUrl trỏ repo.maven.apache.org |
| 2026-08-18 | Domain Resource Adapter SPI + history contract | Core API 1.1.0 | runtime | Domain module tham gia Resource Registry/read/history mà không dùng generic repository hoặc generic command | Internal | Adapter phải `storageMode=DOMAIN`, unique resource type; module vẫn sở hữu invariant/transaction/persistence |
| 2026-08-18 | Core semver range gate | Core API 1.1.0 | runtime | Module khai báo exact/range (`>=1.1.0 <2.0.0`); startup fail-fast khi không tương thích | Internal | Chưa thay thế compatibility matrix và install/upgrade/rollback workflow |
| 2026-08-17 | Next.js + eslint-config-next | 16.3.1 | frontend runtime/build | App Router chính thức, standalone OCI; cập nhật khỏi 16.2.6 để xử lý security advisories và đồng bộ lint contract | MIT | Pin exact; Node.js 22; `npm audit` = 0; không dùng vinext/Vite |
| 2026-08-17 | SVG icon nội bộ | source-owned | frontend | Icon theo ngữ nghĩa module, không thêm runtime dependency/icon font | Nội bộ dự án | Render inline, kế thừa `currentColor`, tương thích Navigation Registry |

## Quy ước chạy test không có Docker

Test mặc định khởi PostgreSQL 17 bằng Testcontainers. Khi máy không có Docker
(Windows không WSL, môi trường hạn chế), đặt các biến sau trước khi chạy `./mvnw verify`:

```text
IT_DB_URL=jdbc:postgresql://127.0.0.1:55432/core_platform
IT_DB_USER=core_app
IT_DB_PASSWORD=core_app_dev
```

Database đích phải trống hoặc đã đúng schema do Flyway quản lý; mọi test dùng
suffix ngẫu nhiên nên chạy lại nhiều lần không xung đột dữ liệu.
