# Business Rules Baseline — Marketing & Revenue Intelligence

| Thuộc tính | Giá trị |
|---|---|
| Mã tài liệu | `MRI-RULE-001` |
| Phiên bản | `1.0.0-proposed` |
| Hiệu lực | Dùng cho development/test; cần Product Owner ký trước production |

## 1. Revenue

- `gross_revenue`: tổng tiền hàng trước discount/refund.
- `net_revenue = gross_revenue - discount_amount - refund_amount`.
- Tax và shipping không nằm trong net revenue baseline.
- Chỉ order `COMPLETED` hoặc `PAID` là valid order.
- Order `CANCELLED`, `DRAFT`, `PENDING` không tạo doanh thu hợp lệ.
- Refund phải tham chiếu order gốc; không tạo một sale mới.
- Currency phải thống nhất theo deployment MVP; dòng khác currency bị từ chối.
- Import lại cùng `(source_system, external_order_id)` thực hiện upsert có kiểm soát, không cộng dồn.

## 2. Customer lifecycle

- `NEW`: order hợp lệ đầu tiên khi lịch sử customer được đánh dấu đầy đủ.
- `RETURNING`: có order hợp lệ trước đó và khoảng cách không vượt reactivation threshold.
- `REACTIVATED`: có order trước đó nhưng không mua trong ít nhất 90 ngày; giá trị 90 cấu hình được.
- `UNKNOWN_HISTORY`: lịch sử trước cut-over không đầy đủ hoặc thiếu identity đủ tin cậy.
- Lifecycle được snapshot trên từng order và chỉ được tính lại bằng job có audit.
- Refund không thay đổi lifecycle của order gốc.

## 3. Customer identity

Độ ưu tiên deterministic:

1. `(source_system, external_customer_id)`;
2. phone đã chuẩn hóa E.164/Việt Nam;
3. email lowercase/trim;
4. loyalty/member ID.

- Không tự động merge theo tên hoặc địa chỉ.
- Conflict giữa các định danh tạo Data Quality issue.
- Phone/email lưu normalized hash để match và masked value để hiển thị.
- Merge customer cần quyền, reason và audit; source customer không bị xóa vật lý.

## 4. Wholesale/Retail

- `business_model` bắt buộc trên order: `WHOLESALE` hoặc `RETAIL`.
- Customer segment và order business model là hai thuộc tính khác nhau.
- Không suy luận chỉ từ order amount.
- Mapping tự động chỉ được dùng khi source owner duyệt rule theo contract/price-list/order-type.
- Dòng không xác định bị từ chối import hoặc đưa vào error queue; không tự gán Retail.

## 5. Channel và attribution

- Channel taxonomy chuẩn tối thiểu: `PAID_SOCIAL`, `PAID_SEARCH`, `ORGANIC`, `DIRECT`, `REFERRAL`, `OFFLINE`, `MARKETPLACE`, `UNKNOWN`.
- First touch là touchpoint hợp lệ sớm nhất đã biết của customer.
- Last non-direct touch là touchpoint gần nhất không phải Direct trong 30 ngày trước order.
- Nếu chỉ có Direct và không có non-direct touch trong window, conversion channel là Direct.
- Nếu không có bằng chứng, channel là Unknown.
- View-through attribution không thuộc MVP.
- First-touch và last-touch revenue được báo cáo riêng, không cộng hai lần vào total revenue.
- Attribution result lưu rule version và evaluated time.

## 6. KPI

- Mẫu số bằng 0 trả `null`.
- ROAS dùng last-touch attributed net revenue trong báo cáo conversion.
- CAC dùng first-touch spend và New customer trong báo cáo acquisition.
- MER dùng total net revenue và total ad spend, không phụ thuộc attribution coverage.
- Repeat customer rate dùng lifetime valid-order count tại ngày cuối kỳ.
- Cohort repurchase N ngày chỉ tính customer có first valid order trong cohort.
- Mọi dashboard hiển thị timezone, currency, freshness và rule version.

## 7. Reconciliation

- Control key mặc định: `business_date + source_system + currency`.
- So sánh source row count, valid order count, gross, discount, refund và net.
- Tolerance mặc định development: `0.01` đơn vị tiền tệ cho rounding.
- Production tolerance do Finance phê duyệt; vượt tolerance đặt batch `RECONCILIATION_FAILED`.
- Batch fail không bị xóa; sửa mapping/dữ liệu tạo lần xử lý mới có liên kết batch gốc.

## 8. Data quality

- Unknown là trạng thái hợp lệ có cảnh báo, không phải giá trị bị ẩn.
- Thiếu external order ID, currency, ordered_at hoặc business_model là lỗi chặn.
- Thiếu customer identity cho phép import order dạng anonymous nhưng lifecycle là Unknown-history.
- Tỷ lệ attribution coverage và identity coverage phải hiển thị ở dashboard.
- Không tự sửa dữ liệu nguồn mà không lưu original value và transformation rule.

