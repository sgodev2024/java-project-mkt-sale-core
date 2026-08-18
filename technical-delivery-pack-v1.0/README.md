# Java Core Platform — Technical Delivery Pack v1.0

| Thuộc tính | Giá trị |
|---|---|
| Mã gói | `CP-DELIVERY-004` |
| Phiên bản | `1.0.0` |
| Trạng thái | Approved Baseline |
| Ngày phát hành | 2026-08-15 |
| Đối tượng | Technical Lead, Developer, QA, DevOps/Platform, Security Reviewer |

## 1. Mục đích

Đây là bộ tài liệu kỹ thuật chính thức để đội lập trình triển khai Java Core Platform. Gói hợp nhất các quyết định BA, runtime architecture và database architecture đã được phê duyệt.

## 2. Thứ tự đọc

1. `01-technical-implementation-specification.md` — nguồn triển khai chính.
2. `02-implementation-backlog.md` — dependency, epic, story và acceptance criteria.
3. `03-delivery-and-quality-checklist.md` — Definition of Ready/Done, release và bàn giao source.
4. `04-execution-start-plan.md` — kế hoạch bắt đầu triển khai theo sprint và vai trò.
5. Các tài liệu nguồn ở thư mục cha để tra cứu reasoning và sơ đồ chi tiết.

## 3. Tài liệu nguồn được phê duyệt

- `../core-platform-ba-requirements-v1.1.md` — `CP-BA-001`, baseline frontend hợp nhất FE-BA-01..13.
- `../core-platform-runtime-architecture-v1.0.md` — `CP-ARCH-002`.
- `../core-platform-database-architecture-v1.0.md` — `CP-DATA-003`.

## 4. Quy tắc ưu tiên

Khi có khác biệt diễn giải:

1. ADR được chấp nhận mới nhất.
2. `01-technical-implementation-specification.md`.
3. Runtime/Database Architecture.
4. BA Requirements.
5. Ticket hoặc trao đổi không được phê duyệt.

Không tự sửa implementation để “khớp ý hiểu”. Tạo ADR hoặc clarification ticket trước.

## 5. Baseline đã khóa

- Java 21.
- Spring Boot 3.x, exact version pin trong Platform BOM ở Sprint 0.
- Spring Modulith.
- PostgreSQL.
- Maven Wrapper và multi-module build.
- Modular monolith.
- Code-first Domain Model là mặc định.
- Dynamic Resource là standard module tùy chọn.
- PostgreSQL-backed outbox/job baseline.
- Mỗi khách hàng một deployment/database.
- Source hệ thống đầy đủ được bàn giao theo release tag.

## 6. Thay đổi tài liệu

Thay đổi breaking đối với public contract, tenant isolation, module ownership, transaction, audit, event hoặc database schema phải có ADR và approver tương ứng.
