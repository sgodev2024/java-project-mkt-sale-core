# Revenue Intelligence

Revenue Intelligence là dự án nghiệp vụ độc lập dùng để đo lường chi phí quảng cáo, nguồn khách hàng, cơ cấu doanh thu, tỷ lệ mua lại và hiệu quả phân bổ kênh. Dự án chứa toàn bộ source có thể bàn giao và triển khai độc lập cho khách hàng.

## Core baseline

- Upstream: `git@github.com:sgodev2024/java-core.git`
- Tag: `core-v1.1.0-project-baseline`
- Commit: `199411e`
- Chính sách đồng bộ: chỉ nhận thay đổi Core qua pull request có kiểm tra tương thích, migration và hồi quy; không ghi nghiệp vụ dự án ngược về Core.

Tài liệu nghiệp vụ nằm tại `docs/01-business-analysis-v1.0.md`, kế hoạch khám phá dữ liệu tại `docs/02-data-discovery-plan-v1.0.md`, quy tắc tính toán tại `docs/03-business-rules-v1.0.md`, hợp đồng dữ liệu/API tại `docs/04-data-and-integration-contracts-v1.0.md`, biên bản hoàn thành tại `docs/05-implementation-status-v1.0.md` và runbook môi trường test tại `docs/06-deployment-runbook-test-v1.0.md`.

Tiêu chuẩn bắt buộc để tạo, phát triển, nâng Core và bàn giao các dự án tiếp theo nằm tại `docs/00-core-to-project-implementation-standard-v1.0.md`. Stack triển khai độc lập của dự án nằm tại `deploy/project/`.

## Platform foundation

Java Core Platform is a business-neutral modular application foundation for building independent customer solutions. The repository contains the approved architecture documents, a Java 21/Spring Boot runtime slice, PostgreSQL migrations, the Control Plane frontend and Ubuntu deployment assets.

## Repository layout

- `backend/` — Spring Boot runtime, local identity, MFA, hashed sessions, audit and Control Plane API
- `frontend/` — Core Platform Control Plane UI
- `backend/src/main/java/vn/coreplatform/demo/` và `frontend/app/demo/` — module/chunk minh họa chỉ bật bằng profile `demo`/`test`, không thuộc Production Core
- `deploy/ubuntu20/` — systemd, Nginx, environment and deployment/rollback assets
- `technical-delivery-pack-v1.0/` — implementation specification, backlog and quality gates
- `core-platform-ba-requirements-v1.1.md` — approved BA and unified frontend baseline (FE-BA-01..13)
- `core-platform-runtime-architecture-v1.0.md`, `core-platform-database-architecture-v1.0.md` — approved runtime and database architecture
- `docs/navigation-registry.md` — unified section/menu manifest, assignment visibility, security and extension contract
- `docs/technical-change-register.md` — sổ thay đổi kỹ thuật và lịch sử tự động từ Git
- `templates/domain-module/` và `scripts/new-domain-module.ps1` — template tạo module code-first tách khỏi package Core

## Local start

```text
docker compose up --build
```

Ba service sẽ chạy: PostgreSQL 17 (`:5432`), backend (`:8080`), Control Plane UI (`:3000`).

Sau khi đăng nhập, mở **Nghiệp vụ → Marketing & Doanh thu**. Có thể nạp lần lượt bốn file trong `samples/input/`: customers, orders, ad-spend và touchpoints; sau đó chạy lại attribution để xem dashboard và đối soát.

Database dùng hai credential (E2): `core_admin` cho migration/DDL (`DB_MIGRATION_USER`) và `core_app` cho runtime (`DB_USER`, chỉ DML + chịu RLS theo tenant). Thay đổi roles/seed xong cần `docker compose down -v` để tạo lại volume.

- UI: `http://localhost:3000` — đăng nhập `admin@core.local` / `Core@2026`, mã MFA `123456` khi `CORE_MFA_ENABLED=true` (chỉ dùng cho môi trường demo local).
- Backend readiness: `http://localhost:8080/actuator/health/readiness`
- Backend OpenAPI: `http://localhost:8080/swagger-ui`

Frontend dùng Next.js standalone chuẩn. Có thể build trực tiếp hoặc truyền API URL khi build Docker:

```text
cd frontend
NEXT_PUBLIC_CORE_API_URL=http://localhost:8080 npm run build
docker build --build-arg NEXT_PUBLIC_CORE_API_URL=http://localhost:8080 -t core-platform-frontend ./frontend
```

## Build and test

Backend (Java 21, PostgreSQL qua Testcontainers; máy không có Docker xem `backend/README.md`):

```text
cd backend && ./mvnw verify
```

Frontend (Node 22+, build + SSR smoke test):

```text
cd frontend && npm test
```

CI chạy cả hai trên mọi pull request (`.github/workflows/`).

## Test deployment

- URL: `https://crm-mkt-sale.sgodata.com`
- Server path: `/home/ubuntu/crm-mkt-sale-java-core`
- Triển khai: `./deploy/project/deploy.sh`
- Smoke test không nạp lại dữ liệu: `./deploy/project/smoke-test.sh`
- Nạp bộ dữ liệu tổng hợp lần đầu: `SEED_DEMO=true ./deploy/project/smoke-test.sh`

Secret chỉ nằm trong `.env` quyền `0600`; không được commit hoặc ghi vào tài liệu.

Frontend:

```text
cd frontend
npm install
npm run dev
```

Set `NEXT_PUBLIC_CORE_API_URL=http://localhost:8080` for local API integration.

Demo-only credentials are documented in `backend/README.md`. Never enable the `demo` Spring profile for customer production environments.
