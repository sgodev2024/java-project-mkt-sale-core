# Business Analysis & System Requirements — Marketing & Revenue Intelligence

| Thuộc tính | Giá trị |
|---|---|
| Mã tài liệu | `MRI-BA-001` |
| Phiên bản | `1.0.0-draft` |
| Ngày lập | 2026-08-18 |
| Trạng thái | Draft for Product Owner approval |
| Core baseline | Java Core Platform BA v1.1.4 |
| Mô hình triển khai | Dedicated deployment, một database cho một khách hàng |

## 1. Tóm tắt điều hành

Doanh nghiệp đang gặp tình trạng chi phí quảng cáo tăng trong khi doanh thu giảm, nhưng chưa có dữ liệu thống nhất để xác định nguyên nhân. Dữ liệu quảng cáo, khách hàng và đơn hàng đang nằm ở nhiều nguồn; doanh thu chưa được phân tách đáng tin cậy theo kênh thu hút, vòng đời khách hàng và mô hình bán buôn/bán lẻ.

Hệ thống Marketing & Revenue Intelligence (MRI) được xây dựng trên Java Core Platform nhằm tạo một nguồn số liệu có thể đối soát để trả lời:

- tiền quảng cáo đang được chi ở đâu và mang lại kết quả gì;
- khách hàng được thu hút từ kênh nào;
- đơn hàng thuộc khách mới, khách quay lại hay khách tái kích hoạt;
- doanh thu thuộc bán buôn hay bán lẻ;
- doanh thu khách mới và khách cũ đóng góp bao nhiêu;
- tỷ lệ mua lại trong 30, 60 và 90 ngày;
- tỷ lệ doanh thu chưa xác định được nguồn và nguyên nhân thiếu dữ liệu.

MRI không tự khẳng định nguyên nhân doanh thu giảm. Hệ thống cung cấp dữ liệu, rule và khả năng drill-down để người điều hành kiểm chứng các giả thuyết kinh doanh.

## 2. Vấn đề kinh doanh

| ID | Vấn đề | Hệ quả |
|---|---|---|
| BP-001 | Chi phí quảng cáo tăng nhưng không đối soát được với doanh thu | Không biết nên tăng, giảm hay chuyển ngân sách |
| BP-002 | Không biết khách đến từ kênh nào | CAC và hiệu quả channel/campaign không đáng tin cậy |
| BP-003 | Không có định danh khách hàng thống nhất | Trùng khách, sai khách mới/cũ và sai tỷ lệ mua lại |
| BP-004 | Không phân biệt doanh thu bán buôn/bán lẻ | Không đánh giá được cơ cấu doanh thu và biên lợi nhuận |
| BP-005 | Không phân tách doanh thu khách mới/khách cũ | Không biết tăng trưởng phụ thuộc acquisition hay retention |
| BP-006 | Không đo được cohort mua lại | Không phát hiện suy giảm giữ chân khách hàng |
| BP-007 | Chưa đo chất lượng dữ liệu attribution | Báo cáo có thể đẹp nhưng không truy nguyên được |

## 3. Mục tiêu kinh doanh

| ID | Mục tiêu | Cách đo |
|---|---|---|
| BG-001 | Tạo nguồn doanh thu thống nhất | Đối soát theo ngày với nguồn bán hàng/kế toán |
| BG-002 | Minh bạch hiệu quả quảng cáo | Spend, attributed revenue, ROAS, CAC theo channel/campaign |
| BG-003 | Phân loại vòng đời khách hàng | New, Returning, Reactivated, Unknown-history tại thời điểm đơn hàng |
| BG-004 | Minh bạch cơ cấu bán hàng | Doanh thu Wholesale/Retail và tỷ trọng theo kỳ |
| BG-005 | Đo giữ chân khách hàng | Repeat rate và cohort repurchase 30/60/90 ngày |
| BG-006 | Quản trị chất lượng dữ liệu | Coverage, missing identifiers, unknown channel và lỗi import |

Mục tiêu tăng doanh thu hoặc giảm chi phí theo phần trăm chưa được đặt ở phiên bản này vì chưa có baseline dữ liệu thật. Product Owner chỉ chốt target sau tối thiểu một chu kỳ dữ liệu đã đối soát.

## 4. Phạm vi MVP

### 4.1 Trong phạm vi

- một pháp nhân và một tiền tệ chính cho mỗi deployment;
- import CSV cho customer, order, ad spend và touchpoint;
- connector framework để bổ sung Meta, Google, TikTok, CRM, POS hoặc ERP sau MVP;
- chuẩn hóa customer identity bằng external ID, phone và email;
- first-touch và last-non-direct-touch attribution;
- phân loại New, Returning, Reactivated và Unknown-history;
- phân loại order-level Wholesale/Retail;
- gross revenue, discount, refund và net revenue;
- dashboard điều hành, channel/campaign, customer lifecycle, business model và cohort;
- drill-down từ KPI đến dữ liệu nguồn;
- data quality report và import error report;
- CSV export cho dữ liệu đã lọc;
- audit, permission, tenant isolation, job và event kế thừa từ Core.

### 4.2 Ngoài phạm vi MVP

- tự động điều chỉnh ngân sách hoặc bid quảng cáo;
- marketing mix modeling và attribution xác suất;
- view-through attribution từ nền tảng quảng cáo;
- AI dự báo doanh thu;
- real-time CDP và marketing automation;
- gửi email/SMS/push campaign;
- shared SaaS deployment;
- dashboard lợi nhuận khi chưa có cost-of-goods đáng tin cậy.

## 5. Stakeholder và vai trò

| Vai trò | Trách nhiệm |
|---|---|
| Executive/Owner | Theo dõi xu hướng và phê duyệt mục tiêu |
| Marketing Manager | Quản lý channel/campaign, spend và attribution |
| Sales Manager | Chịu trách nhiệm order channel, wholesale/retail và khách hàng |
| Finance/Accounting | Chốt công thức doanh thu và đối soát refund |
| Data Steward | Xử lý dữ liệu thiếu, trùng và mapping nguồn |
| System Administrator | Tài khoản, permission, connector và vận hành |
| Technical Lead | Module boundary, data contract và chất lượng kỹ thuật |
| Security/Privacy Approver | Dữ liệu cá nhân, masking, retention và audit |

## 6. Khái niệm nghiệp vụ

| Khái niệm | Định nghĩa |
|---|---|
| Acquisition Channel | Kênh touchpoint hợp lệ đầu tiên đã biết của khách hàng |
| Conversion Channel | Kênh non-direct gần nhất trước một đơn hàng trong attribution window |
| Order Channel | Nơi đơn hàng được tạo: Website, POS, Telesales, Marketplace... |
| Business Model | Phân loại từng đơn hàng là Wholesale hoặc Retail |
| New Customer | Khách có đơn hàng hợp lệ đầu tiên trong lịch sử đủ |
| Returning Customer | Khách đã có đơn hàng hợp lệ trước đơn hàng đang xét |
| Reactivated Customer | Khách quay lại sau số ngày không mua được cấu hình |
| Unknown-history | Không đủ lịch sử để kết luận New/Returning |
| Attribution Coverage | Tỷ lệ net revenue được gán kênh có bằng chứng |
| Net Revenue | Gross revenue trừ discount và refund theo rule đã phê duyệt |

## 7. Giả thuyết cần kiểm chứng

- H-001: CAC tăng do channel mix chuyển sang kênh có conversion thấp.
- H-002: Revenue giảm chủ yếu do số khách mới giảm.
- H-003: Repeat rate giảm làm doanh thu khách cũ suy giảm.
- H-004: Doanh thu wholesale giảm nhưng bị che bởi doanh thu retail.
- H-005: Refund/cancel tăng làm gross revenue không phản ánh net revenue.
- H-006: Attribution coverage thấp khiến doanh thu bị gán sai hoặc nằm ở Unknown.
- H-007: Chi phí tăng do campaign trùng audience hoặc frequency cao; cần dữ liệu nền tảng quảng cáo để kiểm chứng.

## 8. Yêu cầu chức năng

| ID | Yêu cầu | Ưu tiên |
|---|---|---|
| FR-001 | Import CSV idempotent theo source system và external ID | Must |
| FR-002 | Ghi nhận import batch, số dòng thành công/lỗi và lỗi từng dòng | Must |
| FR-003 | Chuẩn hóa phone/email/external identity và phát hiện customer trùng | Must |
| FR-004 | Cho phép merge customer có audit và chặn merge vòng lặp | Must |
| FR-005 | Import order/refund và tính net revenue nhất quán | Must |
| FR-006 | Business model là thuộc tính bắt buộc ở cấp order | Must |
| FR-007 | Phân loại lifecycle tại thời điểm order | Must |
| FR-008 | Lưu first-touch và last-non-direct-touch attribution riêng | Must |
| FR-009 | Không tự phân bổ dữ liệu Unknown sang channel khác | Must |
| FR-010 | Import ad spend theo ngày/channel/campaign và chống trùng | Must |
| FR-011 | Dashboard theo date range, channel, campaign, lifecycle và business model | Must |
| FR-012 | Drill-down KPI đến order/import source | Must |
| FR-013 | Tính repeat rate và cohort repurchase 30/60/90 ngày | Must |
| FR-014 | Đối soát tổng order, refund và net revenue với nguồn | Must |
| FR-015 | Báo cáo data coverage và lỗi identifier/channel | Must |
| FR-016 | Export dữ liệu đã lọc ra CSV | Should |
| FR-017 | Connector API có checkpoint và retry | Should |
| FR-018 | Cho phép cấu hình reactivation days và attribution window | Should |

## 9. Dashboard và báo cáo

### 9.1 Executive Overview

- ad spend, gross revenue, net revenue;
- ROAS, MER, CAC và AOV;
- số khách mới, khách quay lại và khách tái kích hoạt;
- new/returning revenue share;
- wholesale/retail revenue share;
- repeat rate và attribution coverage;
- xu hướng theo ngày/tuần/tháng.

### 9.2 Channel & Campaign

- spend, impressions, clicks, orders, new customers;
- first-touch revenue và last-touch revenue;
- ROAS/CAC theo channel/campaign;
- Unknown và Direct hiển thị thành nhóm riêng.

### 9.3 Customer Lifecycle

- new, returning, reactivated, unknown-history;
- revenue và AOV theo lifecycle;
- cohort repurchase 30/60/90 ngày;
- danh sách khách/đơn hàng tạo nên số liệu.

### 9.4 Business Model

- wholesale/retail revenue, order và customer;
- phân tích theo order channel;
- không suy diễn business model từ giá trị đơn.

### 9.5 Data Quality

- thiếu external order ID;
- thiếu customer identity;
- thiếu business model/order channel;
- unknown acquisition/conversion channel;
- duplicate/conflict và import row errors;
- lần đồng bộ gần nhất của từng source.

## 10. Yêu cầu phi chức năng

- Dedicated deployment và PostgreSQL theo Core baseline.
- Dữ liệu cập nhật T+1 trong MVP; connector có thể nâng lên hourly theo hợp đồng.
- Tất cả bảng tenant-owned phải có `tenant_id`, RLS ENABLE + FORCE.
- API authorization fail-closed; dữ liệu drill-down tôn trọng permission.
- Phone/email là dữ liệu cá nhân, phải masking trong list/export theo quyền.
- Import phải stream/batch, không giữ file lớn toàn bộ trong memory.
- Job import có idempotency key, retry bounded và audit.
- Dashboard query phải phân trang/drill-down; không tải toàn bộ dataset vào frontend.
- Mọi công thức KPI phải version hóa và hiển thị data freshness.
- RPO/RTO kế thừa service tier của deployment; restore drill là release gate.

## 11. KPI và công thức

| KPI | Công thức baseline |
|---|---|
| ROAS | Attributed net revenue / Ad spend |
| MER | Total net revenue / Total ad spend |
| CAC | Attributed acquisition spend / New customers |
| AOV | Net revenue / Valid orders |
| New Revenue Rate | New-customer net revenue / Total net revenue |
| Returning Revenue Rate | Returning/reactivated net revenue / Total net revenue |
| Repeat Customer Rate | Customers with >=2 valid lifetime orders / Customers with valid orders |
| Cohort Repurchase N | New customers with second order within N days / New customers in cohort |
| Attribution Coverage | Revenue with evidence-backed channel / Total net revenue |
| Wholesale Revenue Rate | Wholesale net revenue / Total net revenue |

Tỷ lệ có mẫu số bằng 0 trả `null`, không trả 0 để tránh diễn giải sai.

## 12. Acceptance criteria MVP

- Tổng New + Returning + Reactivated + Unknown-history revenue bằng tổng net revenue hợp lệ.
- Tổng Wholesale + Retail revenue bằng tổng net revenue hợp lệ.
- Một external order chỉ được ghi nhận một lần cho mỗi source system.
- Refund điều chỉnh đúng order, lifecycle, business model và channel.
- Mỗi order có channel có bằng chứng hoặc lý do Unknown.
- Có thể truy ngược mọi KPI đến order và import batch.
- Customer first order được tính ổn định khi import lại cùng dữ liệu.
- Cohort 30/60/90 ngày cho kết quả kiểm chứng được bằng fixture.
- Audit tồn tại cho import, merge identity, sửa mapping và cấu hình rule.
- Dashboard không chứa dữ liệu demo khi chạy profile Production.
- Revenue reconciliation sai lệch vượt tolerance phải hiển thị trạng thái thất bại.

## 13. Rủi ro và phụ thuộc

| Rủi ro | Xử lý |
|---|---|
| Thiếu lịch sử order | Gắn Unknown-history, không tự nhận là New |
| Không dùng UTM/click ID | Ghi Unknown, triển khai tracking từ thời điểm cut-over |
| Phone/email thiếu hoặc sai | Data quality queue, không fuzzy-merge tự động |
| Revenue source không thống nhất | Finance phê duyệt source-of-truth và tolerance |
| Khác biệt báo cáo Meta/Google | Lưu raw platform metrics và attribution nội bộ riêng |
| Sửa Core trực tiếp trong dự án | CI boundary và quy trình upstream-first |

## 14. Phê duyệt còn yêu cầu

- Product Owner: scope và ưu tiên MVP.
- Finance: net revenue, refund, tax, shipping và reconciliation tolerance.
- Marketing: channel taxonomy, attribution window và Direct handling.
- Sales: wholesale/retail và order status hợp lệ.
- Privacy/Security: identity, masking, export và retention.

