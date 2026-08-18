# Core-to-Project Implementation Standard v1.0

- Mã tài liệu: `SGO-ENG-CORE-PROJECT-001`
- Trạng thái: Baseline bắt buộc
- Phạm vi: mọi dự án khách hàng phát triển từ Java Core Platform
- Mục tiêu: bàn giao đầy đủ source chạy độc lập, đồng thời giữ Core trung tính để tái sử dụng

## 1. Mô hình repository

Mỗi dự án khách hàng phải có repository độc lập và chứa đủ backend, frontend, migration, deployment assets, test và tài liệu. Không phát triển nghiệp vụ khách hàng trực tiếp trên repository Core.

```text
java-core (origin của nền tảng)
    |
    |  baseline tag + compatibility contract
    v
customer-project (origin riêng, full source bàn giao)
    +-- vn.coreplatform        snapshot Core đã khóa
    +-- vn.<company>.<domain>  module nghiệp vụ dự án
    +-- docs                   BA, rules, contracts, runbook
    +-- deploy                 compose, Nginx, init và rollback
```

Quy ước remote trong repository dự án:

- `origin`: repository riêng của dự án khách hàng; là nơi duy nhất nhận nghiệp vụ.
- `upstream`: repository Java Core; chỉ dùng để theo dõi/nâng baseline.
- `CORE_BASELINE`: lưu tag và commit Core đã dùng để sinh dự án.

Mọi hợp đồng bàn giao phải lấy source từ `origin` dự án. Khách hàng không cần tách biệt Core và tùy chỉnh khi vận hành.

## 2. Phân loại thay đổi

| Loại thay đổi | Nơi thực hiện | Ví dụ |
|---|---|---|
| Nền tảng dùng chung | Core | security, permission engine, module contract, audit, outbox, file, observability |
| Nghiệp vụ khách hàng | Dự án | CRM, bán hàng, marketing, kho, kế toán đặc thù |
| Sửa lỗi chỉ xuất hiện ở dự án | Dự án trước | hotfix nghiệp vụ hoặc integration riêng |
| Sửa lỗi Core có ảnh hưởng nhiều dự án | Core trước | CVE, tenant leak, migration framework, public API contract |

Không merge schema, menu, workflow hoặc thuật ngữ nghiệp vụ khách hàng ngược vào Core. Nếu một năng lực có khả năng dùng chung, phải có ADR chứng minh nó trung tính trước khi đưa vào Core.

## 3. Khóa baseline và nâng cấp Core

Trước khi bắt đầu dự án:

1. Core `main` phải sạch và CI đạt.
2. Tạo semantic tag, ví dụ `core-v1.2.0-project-baseline`.
3. Ghi tag, SHA và ngày chốt vào `CORE_BASELINE`.
4. Tạo project repository từ đúng tag, không từ working tree chưa commit.
5. Chạy regression Core trong project trước khi thêm nghiệp vụ.

Nâng Core cho dự án chỉ được thực hiện bằng pull request chuyên biệt. PR phải có compatibility report, migration dry-run, regression, backup và rollback plan. Không tự động kéo `upstream/main` vào production.

## 4. Ranh giới mã nguồn

- Core giữ namespace `vn.coreplatform`.
- Nghiệp vụ dùng namespace riêng, ví dụ `vn.sgodata.crm`.
- Module chỉ sử dụng public contracts được duyệt: kernel/module SPI, permission, audit, eventing và shared error contract.
- Cấm gọi trực tiếp controller/repository/private service của module Core khác.
- ArchUnit phải kiểm tra dependency boundary trên mọi pull request.
- Frontend module phải được nạp qua Navigation Registry; không hard-code menu khách hàng vào shell Core.

Mỗi module nghiệp vụ tối thiểu phải cung cấp:

- module descriptor và version;
- Core compatibility range;
- navigation contribution;
- permission/resource descriptors;
- Flyway migrations riêng;
- API contract và error codes;
- audit/outbox events;
- unit, integration và architecture tests.

## 5. Cơ sở dữ liệu

- Một khách hàng/một deployment/một database ở giai đoạn hiện tại.
- Mỗi domain có schema riêng; bảng nghiệp vụ không đặt trong `public`.
- Mọi dữ liệu thuộc tổ chức phải có `tenant_id`, forced RLS và tenant policy.
- Migration dùng role DDL riêng; runtime dùng role DML không có `BYPASSRLS`, `CREATEDB`, `CREATEROLE` hay quyền owner.
- Flyway migration đã phát hành là bất biến; sửa bằng migration mới.
- Mọi unique key nghiệp vụ phải kèm tenant/source phù hợp.
- Dữ liệu cá nhân phải được phân loại, tối thiểu hóa, che hoặc băm khi không cần lưu bản rõ.
- RPO mục tiêu 15 phút, RTO mục tiêu 1 giờ phải được kiểm chứng bằng restore drill.

## 6. API, transaction và event

- API version theo `/api/v1/...`; lỗi trả `application/problem+json` và correlation ID.
- Permission được kiểm tra ở server; ẩn menu không thay thế authorization.
- Một use case ghi dữ liệu phải bao trùm transaction nghiệp vụ, audit và outbox.
- Integration bất đồng bộ dùng transactional outbox; consumer phải idempotent.
- Import phải có checksum/idempotency key, batch status, lỗi theo dòng và reconciliation.
- Thay đổi contract không tương thích phải tạo version mới và thời hạn deprecation.

## 7. Frontend

- Dùng Next.js chuẩn; production build phải là standalone image.
- Ưu tiên frontend/API cùng origin: domain dự án phục vụ UI, `/api/*` reverse proxy về backend.
- Mọi domain trình duyệt phải nằm trong CORS allowlist cấu hình theo môi trường; không hard-code domain dự án vào Java Core.
- Smoke test đăng nhập phải gửi header `Origin` giống trình duyệt và xác minh cả origin hợp lệ lẫn origin không được tin cậy.
- Không hard-code domain Core, credential, menu hay quyền trong source.
- Giao diện chỉ render tính năng do Navigation Registry và capability/permission cho phép.
- Mỗi module có loading, empty, error, retry và responsive states.
- Build, type-check và smoke test SSR là quality gate bắt buộc.

## 8. Cấu hình và secrets

- Commit duy nhất `.env.example`; `.env`, key, certificate, dump và token phải bị ignore.
- Secret production sinh ngẫu nhiên, lưu quyền `0600`, không xuất hiện trong log/commit/tài liệu công khai.
- Tách password migration, runtime database và bootstrap administrator.
- Bootstrap password chỉ dùng lần đầu và phải đổi sau khi bàn giao.
- MFA có thể tắt trong giai đoạn test theo quyết định BA; production chính thức phải có quyết định security riêng.

## 9. Chuẩn triển khai một máy chủ

Mỗi dự án phải có namespace độc lập cho:

- Compose project name;
- container/image/volume/network;
- database và database roles;
- loopback host ports;
- Nginx server block;
- file storage và backup path;
- log rotation.

PostgreSQL không public ra Internet. Backend và frontend chỉ bind `127.0.0.1`; Nginx là ingress duy nhất. Khi dùng Cloudflare Flexible, origin HTTP port 80 được phép tạm thời nhưng phải ghi nhận risk; đích dài hạn vẫn là Full (Strict) với origin certificate.

## 10. Pipeline và Git

Nhánh chuẩn:

- `main`: trạng thái có thể release;
- `codex/*` hoặc `feature/*`: thay đổi có review;
- `hotfix/*`: lỗi production có incident reference.

Pull request bắt buộc chạy secret scan, backend test, migration test trên PostgreSQL sạch, architecture test, frontend build/test và dependency scan. Release gắn tag semantic, tạo changelog và lưu commit SHA đang chạy.

## 11. Quy trình release

1. Xác nhận BA/acceptance criteria và phạm vi release.
2. Chốt commit/tag; working tree và CI phải sạch.
3. Sao lưu database và file metadata; xác minh backup đọc được.
4. Pull đúng repository dự án và fast-forward only.
5. Render/validate Compose; build image theo commit SHA.
6. Chạy migration bằng role DDL.
7. Start stack; readiness backend và health frontend phải đạt.
8. Kiểm thử login, navigation, permission và critical business flow qua domain.
9. Ghi deployment report: version, thời gian, migration, test, container, domain và vấn đề.
10. Giữ tối thiểu một image/source release trước; không tự rollback database migration phá hủy.

## 12. Definition of Done

Một dự án chỉ được gọi là sẵn sàng production khi:

- BA, business rules, data/API contract và acceptance criteria đã duyệt;
- không còn mock/fixed data trên luồng chính;
- backend/frontend tích hợp qua API thật;
- migration chạy được trên database sạch và database nâng cấp;
- permission, tenant isolation, audit và outbox được kiểm thử;
- backup/restore drill đạt RPO/RTO;
- domain/TLS, monitoring, alert, log rotation và dung lượng được xác minh;
- repository origin, tag, runbook và full source bàn giao đầy đủ;
- credential mặc định đã được thay và có biên bản bàn giao.

## 13. Bộ tài liệu bắt buộc trong mỗi dự án

```text
docs/
  00-core-to-project-implementation-standard-v1.0.md
  01-business-analysis-v1.0.md
  02-data-discovery-plan-v1.0.md
  03-business-rules-v1.0.md
  04-data-and-integration-contracts-v1.0.md
  05-implementation-status-v1.0.md
  06-deployment-runbook-<environment>-v1.0.md
  adr/
CORE_BASELINE
README.md
```

Tài liệu thay đổi cùng pull request với code. Deployment report cập nhật sau mỗi lần triển khai; không ghi password/token vào tài liệu.
