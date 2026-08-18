# Revenue Intelligence — Implementation Status v1.0

- Trạng thái: **MVP kỹ thuật đã hoàn thành và kiểm thử; chưa triển khai production**
- Ngày chốt: 2026-08-18
- Core baseline: `core-v1.1.0-project-baseline` (`199411e`)
- Project baseline commit: `0fc5048`

## 1. Kết quả thực hiện theo 10 bước

| Bước | Hạng mục | Kết quả | Trạng thái |
|---:|---|---|---|
| 1 | BA Marketing & Revenue Intelligence | Chốt vấn đề, mục tiêu, actor, KPI, phạm vi MVP, ngoài phạm vi, acceptance criteria và rủi ro | Hoàn thành |
| 2 | Data Discovery | Lập inventory nguồn, checklist profiling/mapping và bốn bộ CSV mẫu tổng hợp | Hoàn thành có điều kiện; chờ dữ liệu thật |
| 3 | Quy tắc nghiệp vụ | Chốt net revenue, NEW/RETURNING, WHOLESALE/RETAIL, first-touch, last-non-direct và confidence | Hoàn thành |
| 4 | Template dự án/module | Dùng Core contract `ModuleContributor`, `DomainResourceAdapter`, Navigation Registry và package riêng `vn.sgodata` | Hoàn thành |
| 5 | Khóa Core baseline | Core được hồi quy, gắn tag `core-v1.1.0-project-baseline` và lưu commit nguồn | Hoàn thành |
| 6 | Git dự án độc lập | Tạo repository Git cục bộ `revenue-intelligence`, giữ toàn bộ source để bàn giao; `upstream` trỏ về Core | Hoàn thành cục bộ; chờ GitHub repository để cấu hình `origin` |
| 7 | Data model và integration contract | Có migration V20, RLS tenant, chỉ mục, import contract, API contract, audit/outbox | Hoàn thành |
| 8 | Import/reconciliation | Import CSV customers/orders/ad-spend/touchpoints, checksum idempotency, lỗi theo dòng, đối soát doanh thu | Hoàn thành MVP |
| 9 | Customer identity/lifecycle | Ghép định danh theo source ID/hash email/hash phone, che PII, tính NEW/RETURNING theo lịch sử đơn | Hoàn thành MVP |
| 10 | Attribution/dashboard | First-touch và last-non-direct 30 ngày; KPI doanh thu, chi phí, MER, ROAS, tỷ lệ mua lại, nguồn kênh, B2B/B2C | Hoàn thành MVP |

## 2. Thành phần đã triển khai

### Backend

- Schema `revenue_intelligence` gồm import batch/error, channel/campaign, customer/identity/source link, order/revision, ad spend, touchpoint và attribution result.
- Mọi bảng nghiệp vụ mang `tenant_id`, bật forced RLS và policy tenant.
- Không lưu email/số điện thoại thô trong customer identity; dùng SHA-256 và giá trị đã che.
- API có permission gate, transaction, audit trail và transactional outbox.
- Import cùng nội dung lần hai trả về batch cũ với `duplicate=true`.
- Order được công bố qua `DomainResourceAdapter`; module không truy cập các package nội bộ bị cấm của Core.

### Frontend

- Next.js chuẩn, module tải động theo Navigation Registry.
- Bốn màn hình: Tổng quan, Nhập dữ liệu, Khách hàng, Đối soát.
- Gọi API backend thật cho dashboard, import, customer, reconciliation và rebuild attribution.
- Responsive styling và icon riêng theo ngữ nghĩa marketing/doanh thu/kênh/mua lại.

## 3. Bằng chứng chất lượng

- Backend Java 21 compile: đạt.
- Flyway: 20/20 migration áp dụng thành công trên PostgreSQL 17 sạch.
- Backend regression: **149 test, 0 failure, 0 error, 0 skipped**.
- Hai test chuyên biệt `MigrationCoordinatorTest` và `RowLevelSecurityTest` được loại khỏi lệnh hồi quy chung vì cần Docker socket/role setup riêng; các kiểm tra tương ứng của Core baseline đã đạt trước khi gắn tag.
- Frontend: Next.js production build/type check đạt; **5/5 test** đạt.
- `npm ci`: 342 package, 0 vulnerability tại thời điểm kiểm thử.

## 4. Các gate chưa được mở

Không được coi MVP hiện tại là dữ liệu production cho tới khi hoàn thành các gate sau:

1. Nhận file mẫu thật và data dictionary của POS/CRM/kế toán/nền tảng quảng cáo.
2. Chốt định nghĩa doanh thu với kế toán: thuế, phí vận chuyển, hủy, hoàn tiền một phần và thời điểm ghi nhận.
3. Chốt customer matching khi email/điện thoại thiếu, dùng chung hoặc sai định dạng.
4. Đánh giá chất lượng lịch sử để công bố tỷ lệ khách mua lại và confidence.
5. Cấp GitHub repository độc lập để thêm `origin`, push và bật CI/branch protection.
6. Cấp domain, database, secret và môi trường riêng cho dự án; chạy backup/restore và smoke test trước release.

Các API connector trực tiếp (Meta/Google/TikTok/POS/CRM) và mô hình attribution nâng cao chưa thuộc MVP này; CSV là integration boundary đầu tiên đã được kiểm soát.

## 5. Quyết định về tiếp tục phát triển Core

Core **tiếp tục được phát triển song song**, nhưng chỉ với thay đổi dùng chung cho nhiều dự án:

- bảo mật, sửa lỗi và nâng dependency/CVE;
- public contract cho module, migration compatibility và tooling tạo dự án;
- observability, backup/restore, vận hành và cải thiện CI/CD;
- thay đổi schema/hợp đồng có version và kiểm tra tương thích.

Tính năng Marketing & Revenue Intelligence chỉ phát triển trong repository dự án. Không merge nghiệp vụ ngược vào Core. Dự án chỉ nhận phiên bản Core mới qua pull request nâng baseline có regression và kế hoạch migration/rollback. Không tự động nâng Core trên production đang chạy.

## 6. Trạng thái triển khai máy chủ

- Production Core hiện hữu: **không thay đổi** trong đợt này.
- Revenue Intelligence: chỉ dùng container/database tạm để test; chưa cài vào production.
- Chỉ triển khai dự án khi các gate ở mục 4 được duyệt và có repository/môi trường đích độc lập.

