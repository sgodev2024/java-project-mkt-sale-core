# Core Platform Runtime

Java 21 / Spring Boot runtime implementing the first production slice of the approved Core Platform specification.

## Runtime profiles

- `production` (hoặc không đặt profile): chỉ nạp Core/module Production; `approval-domain` và `/api/v1/approvals` không tồn tại trong runtime manifest.
- `demo`: bật module mẫu Approval Domain, group `Nghiệp vụ mẫu` và metadata minh họa.
- `test`: bật module mẫu để chạy integration test; không dùng cho deployment khách hàng.

Module mẫu nằm riêng tại `vn.coreplatform.demo.approval`. Migration giữ schema để rollback, còn metadata demo được startup guard loại khỏi Production catalog.

## Run locally

From the workspace root:

```text
docker compose up --build
```

Backend: `http://localhost:8080`  
OpenAPI: `http://localhost:8080/swagger-ui`  
Readiness: `http://localhost:8080/actuator/health/readiness`

Demo account: `admin@core.local` / `Core@2026`; MFA code: `123456`.
`BootstrapAdminInitializer` nhận mật khẩu quản trị từ `CORE_BOOTSTRAP_ADMIN_PASSWORD`; `docker-compose.yml` chỉ truyền giá trị demo cho profile `demo`. Profile `production` fail-fast nếu biến bị trống hoặc vẫn là `Core@2026`. MFA code đến từ `CORE_BOOTSTRAP_MFA_CODE` và chỉ được chấp nhận khi deployment cho phép bootstrap MFA.

`CORE_MFA_ENABLED` mặc định là `true`. Đặt `false` chỉ khi có phê duyệt vận hành tạm thời: login sẽ cấp session ngay sau password, ghi audit `AUTH_MFA_SKIPPED_BY_CONFIGURATION` và không xóa enrollment. Đổi lại `true` rồi restart để khôi phục TOTP.

## Build and test

```text
./mvnw verify
```

Integration tests start PostgreSQL 17 via Testcontainers (Docker required).
On machines without Docker, point the tests at an external PostgreSQL first
(schema is managed by Flyway; the database must be empty or already migrated):

```text
export IT_DB_URL=jdbc:postgresql://127.0.0.1:5432/core_platform
export IT_DB_USER=core_app
export IT_DB_PASSWORD=...
./mvnw verify
```

The suite covers the auth/MFA/session cycle, permission decisions (ownerOnly scope, explicit DENY, revision bump), tenant isolation, dynamic resource CRUD/optimistic lock/history/CSV, file lifecycle with checksum, correlation-ID propagation into audit events, and Control Plane admin-only access.

## Implemented slice

- PostgreSQL/Flyway baseline and owned schemas
- Kernel & module runtime (E1): `ModuleDescriptor`/`ModuleContributor` discovery, startup validation
  (duplicate key/capability, missing dependency, semver, dependency cycle — fail before Ready),
  reproducible registration into `platform.module`, ArchUnit boundary verification
- Migration coordination (E1-S04): advisory lock serializes concurrent startups; Flyway runs
  programmatically with migration credentials (`DB_MIGRATION_USER`) before any bean starts
- Database foundation (E2): runtime role `core_app` (DML only, no DDL/owner/BYPASSRLS),
  `platform.current_tenant_id()` GUC contract, RLS ENABLE+FORCE on tenant-scoped tables,
  per-request tenant context bound to pooled connections and reset on return
- Local identity (E3): Argon2id password policy with automatic rehash of legacy bcrypt hashes,
  login lockout (5 attempts / 15 min), admin password reset with forced change,
  TOTP MFA enrollment per account + 8 one-time recovery codes (bootstrap env code only when
  `CORE_MFA_ALLOW_BOOTSTRAP=true`), refresh token rotation with reuse detection that revokes
  the whole session family, service accounts with hashed API keys (`cpa_...`, rotate/revoke,
  `ROLE_SERVICE` — can never hold an admin/human session), tenant lifecycle API provisioning
  baseline roles/policies, and organizations locked to their tenant by composite FK + RLS
- Permission & resource registry (E4): kernel Resource Registry SPI (`ResourceDescriptor`/`ResourceRegistry`
  — duplicate owner/type và descriptor drift bị chặn 409, đăng ký idempotent), PDP cache keyed theo
  permission revision (đổi role/policy có hiệu lực ngay), fail-closed mọi lỗi evaluation (Deny + security
  audit `POLICY_EVALUATION_ERROR`), PEP interceptor `@RequirePermission` chạy trước controller,
  list/search luôn lọc bằng SQL predicate (WHERE + LIMIT/OFFSET + COUNT), và classification gate:
  dynamic definition thiếu classification được phê duyệt ở trạng thái PENDING, không dùng được
  qua generic CRUD cho đến khi `POST /dynamic/{key}/classification` phê duyệt
- Audit integrity (E5): centralized AuditService — business events are written in the same
  transaction as the operation (audit failure rolls the operation back); sensitive fields are
  masked before persistence; per-tenant hash chain (`payload_hash`/`prev_hash` + chain state)
  with verification that detects tampering, deletion and sequence gaps; checkpoint before
  retention, SECURITY DEFINER `audit.purge_old` that never deletes un-checkpointed batches and
  respects legal holds; runtime role is append-only on `audit.event` (no UPDATE/DELETE)
- Eventing (E6): stable integration event envelope (`EventContractTest` gates the JSON field set
  in CI), transactional outbox published inside business transactions (crash-safe by construction),
  relay claiming batches via `FOR UPDATE SKIP LOCKED` leases with exponential backoff and DEAD
  (DLQ) after max attempts, inbox table making consumer side effects exactly-once per
  (consumer, event), audited replay that cannot double-apply, and an activity projector as the
  built-in sample consumer; relay enabled in docker via `CORE_OUTBOX_ENABLED`
- Jobs & scheduler (E7): job queue with mandatory tenant (enqueue fails closed without one),
  worker claims via `FOR UPDATE SKIP LOCKED` leases with heartbeat (dead workers' jobs are
  safely reclaimed), retry classification (non-retryable errors go straight to DEAD; retryable
  ones back off exponentially with jitter up to max attempts), cancel/requeue operations, and a
  scheduler with leader election on a database lease — two schedulers create exactly one job
  instance per slot (idempotency key); `audit.checkpoint` runs the E5 hash-chain checkpoint as
  the first real recurring job; enabled in docker via `CORE_JOBS_ENABLED`/`CORE_JOBS_SCHEDULER_ENABLED`
- BCrypt local identity, expiring MFA challenge and hashed opaque sessions
- login, MFA, current-user and logout APIs
- security audit records with correlation ID propagation
- tenant-scoped permission engine (role/policy, ownerOnly scope, explicit DENY, revision bump)
- dynamic resource definitions, generic CRUD, optimistic locking, history, CSV import/export
- file upload/download with SHA-256 checksum, classification and soft delete
- Control Plane API (Platform Admin only)
- liveness/readiness, OpenAPI, CORS and Problem Details errors
- reproducible OCI image and Docker Compose environment

## Database credentials (E2)

Two credentials are used from `docker-compose.yml`:

- `DB_MIGRATION_USER` (core_admin): DDL/Flyway, holds the advisory lock while migrating.
- `DB_USER` (core_app): runtime pool only — `NOSUPERUSER NOBYPASSRLS`, no ownership, DML grants
  via `V6__kernel_roles_rls.sql`, subject to row level security on `dynamic_resource.*` and `files.file_object`.

Fresh installs create `core_app` via `deploy/postgres/01-core-roles.sql` (docker-entrypoint-initdb.d).
After changing roles, reset the dev volume: `docker compose down -v && docker compose up -d --build`.

## Windows development notes

Docker Desktop 29 on Windows no longer exposes the legacy `docker_engine` named pipe, which
Testcontainers probes by default, and its npipe transport can silently drop the Ryuk watchdog
connection (Ryuk then kills session containers mid-run). For local test runs on Windows set:

```text
export DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine
export TESTCONTAINERS_RYUK_DISABLED=true
```

CI (Linux) needs neither. Testcontainers is pinned to 1.21.4 for Docker 29 API compatibility.
