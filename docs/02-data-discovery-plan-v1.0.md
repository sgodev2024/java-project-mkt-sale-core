# Data Discovery Plan — Marketing & Revenue Intelligence

| Thuộc tính | Giá trị |
|---|---|
| Mã tài liệu | `MRI-DATA-001` |
| Phiên bản | `1.0.0` |
| Ngày lập | 2026-08-18 |
| Trạng thái hiện tại | Discovery pack ready; chưa nhận dữ liệu thật |

## 1. Nguyên tắc

Dữ liệu synthetic trong `samples/` chỉ dùng phát triển và kiểm thử. Không được dùng để kết luận thực trạng doanh nghiệp. Data Discovery chỉ hoàn tất khi từng nguồn có owner, sample thật, data profile và biên bản đối soát.

## 2. Danh mục nguồn cần thu thập

| Source | Owner cần xác định | Dữ liệu | Cách lấy ưu tiên | Trạng thái |
|---|---|---|---|---|
| Order/ERP | Sales/Finance | order, status, amount, discount, refund | Read-only export/API | Chờ dữ liệu |
| CRM | Sales/CS | customer, lead source, identity | Read-only export/API | Chờ dữ liệu |
| POS | Retail | receipt, store, customer | CSV/API | Chờ dữ liệu |
| Wholesale | B2B Sales | contract/customer/order type | CSV/API | Chờ dữ liệu |
| Website | Digital | UTM, click ID, anonymous ID, conversion | Event export/API | Chờ dữ liệu |
| Meta Ads | Marketing | spend, impressions, clicks, campaign | API/CSV export | Chờ dữ liệu |
| Google Ads | Marketing | spend, impressions, clicks, campaign | API/CSV export | Chờ dữ liệu |
| TikTok Ads | Marketing | spend, impressions, clicks, campaign | API/CSV export | Chờ dữ liệu |
| Accounting | Finance | recognized revenue, refund, tax | Read-only export | Chờ dữ liệu |

## 3. Checklist cho từng nguồn

- owner nghiệp vụ và owner kỹ thuật;
- source system code ổn định;
- timezone và currency;
- thời gian lưu lịch sử;
- primary/external key;
- trạng thái tạo/cập nhật/xóa;
- API/export format và giới hạn;
- số bản ghi/ngày và peak;
- cơ chế incremental checkpoint;
- trường chứa phone/email/customer ID;
- UTM, click ID, referrer và order channel;
- order status, payment status, cancel/refund;
- gross, discount, tax, shipping, refund;
- dữ liệu cá nhân và cơ sở xử lý;
- retention và quyền export;
- sample tối thiểu 100 dòng, đã masking nếu cần;
- tổng control theo ngày để đối soát.

## 4. Data profiling bắt buộc

Mỗi file/sample thật phải có:

- row count, duplicate count và null rate từng trường quan trọng;
- min/max date và timezone;
- distinct source/channel/status/business model;
- tỷ lệ order có customer identity;
- tỷ lệ customer trùng phone/email;
- tỷ lệ order có UTM/click ID/channel;
- gross/discount/refund/net control total;
- giá trị âm, currency không hợp lệ và outlier;
- referential integrity giữa order/customer/campaign;
- sample lỗi có mã và hướng xử lý.

## 5. Definition of Ready cho nguồn dữ liệu

Nguồn chỉ được đưa vào connector production khi:

- [ ] Source owner xác nhận schema và ý nghĩa trường.
- [ ] Có external ID idempotent.
- [ ] Có timestamp và timezone rõ.
- [ ] Có sample đã profile.
- [ ] Có control total để đối soát.
- [ ] Có rule cancel/refund/delete.
- [ ] Có mapping channel/status/business model.
- [ ] Có privacy classification và quyền truy cập.
- [ ] Có retry/checkpoint strategy.
- [ ] Có acceptance test cho dữ liệu lỗi.

## 6. Kết quả synthetic hiện có

Các fixture được cung cấp:

- `samples/input/customers.csv`;
- `samples/input/orders.csv`;
- `samples/input/ad-spend.csv`;
- `samples/input/touchpoints.csv`.

Fixture bao gồm khách mới, khách quay lại, wholesale, retail, direct, paid media và một trường hợp thiếu lịch sử để kiểm thử Unknown-history.

## 7. Cổng hoàn thành Discovery

Data Discovery production chưa hoàn thành cho đến khi có tối thiểu:

1. order source-of-truth;
2. customer identity source;
3. ít nhất một ad spend source;
4. source chứa acquisition/touchpoint hoặc quyết định chấp nhận Unknown;
5. biên bản chốt công thức doanh thu với Finance.

