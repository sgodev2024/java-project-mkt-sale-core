# CRM Marketing & Sales — Test Deployment Runbook v1.0

- Mã tài liệu: `SGO-OPS-CRM-MKT-SALE-TEST-001`
- Môi trường: Test tích hợp, profile ứng dụng `production`
- Ngày triển khai đầu tiên: 2026-08-18
- Trạng thái: Đã triển khai và smoke test; chưa phải production khách hàng
- Repository: `git@github.com:sgodev2024/java-project-mkt-sale-core.git`
- Core baseline: `core-v1.1.0-project-baseline` (`199411e`)
- Release test: `v0.1.4-test`
- Runtime commit: `8d1592117fd3`

## 1. Điểm triển khai

| Thành phần | Giá trị |
|---|---|
| Public URL | `https://crm-mkt-sale.sgodata.com` |
| Server | Ubuntu 20.04 LTS, `14.225.8.196` |
| Source | `/home/ubuntu/crm-mkt-sale-java-core` |
| Compose project | `crm-mkt-sale` |
| PostgreSQL | container riêng, database `crm_mkt_sale`, không publish port |
| Backend | Java 21/Spring Boot, `127.0.0.1:18180` |
| Frontend | Next.js standalone, `127.0.0.1:18181` |
| Ingress | Nginx port 80, Cloudflare proxy phía ngoài |
| API | cùng origin, prefix `/api/*`; không cần API subdomain |
| Secret | `/home/ubuntu/crm-mkt-sale-java-core/.env`, quyền `0600` |

Stack dùng volume, network, image, port và database độc lập; không dùng chung tài nguyên runtime với Production Core.

## 2. Luồng request

```text
Browser HTTPS
  -> Cloudflare Flexible
  -> Nginx HTTP :80 (Host crm-mkt-sale.sgodata.com)
      -> /api/*                    -> Spring Boot 127.0.0.1:18180
      -> /actuator/health/readiness -> Spring Boot 127.0.0.1:18180
      -> /*                         -> Next.js 127.0.0.1:18181
```

Chỉ readiness được public; các actuator endpoint khác trả 404. PostgreSQL, backend và frontend không bind ra public interface.

## 3. Triển khai và nâng phiên bản

```bash
cd /home/ubuntu/crm-mkt-sale-java-core
./deploy/project/deploy.sh
```

Script chỉ nhận fast-forward từ `origin/main`, bắt buộc working tree sạch, validate Compose, build image gắn SHA, khởi động stack và chặn release nếu backend/frontend không đạt health gate. SHA đang chạy được ghi vào `.deployed-version` và file này không được commit.

Trước release chính thức phải xác nhận CI đạt và gắn semantic tag. Không chạy `git reset --hard`, không sửa migration đã phát hành và không dùng `docker compose down -v` trên môi trường có dữ liệu cần giữ.

## 4. Kiểm tra trạng thái

```bash
cd /home/ubuntu/crm-mkt-sale-java-core
docker compose --env-file .env -f deploy/project/docker-compose.yml ps
curl --fail http://127.0.0.1:18180/actuator/health/readiness
curl --fail --head http://127.0.0.1:18181/
curl --fail https://crm-mkt-sale.sgodata.com/actuator/health/readiness
cat .deployed-version
```

Log được giới hạn 3 file x 10 MB mỗi container:

```bash
docker compose --env-file .env -f deploy/project/docker-compose.yml logs --since=30m backend
docker compose --env-file .env -f deploy/project/docker-compose.yml logs --since=30m frontend
```

## 5. Smoke test

Smoke test đăng nhập bằng bootstrap administrator lấy từ `.env`, gửi `Origin` giống trình duyệt để kiểm tra CORS, không in password/token, xác minh Navigation Registry, thứ bậc menu `Trang chủ` / `Nghiệp vụ` / `Quản trị hệ thống` và dashboard, sau đó logout:

```bash
cd /home/ubuntu/crm-mkt-sale-java-core
./deploy/project/smoke-test.sh
```

Chỉ dùng lệnh sau khi cần nạp bộ CSV tổng hợp của môi trường test:

```bash
SEED_DEMO=true ./deploy/project/smoke-test.sh
```

Không chạy chế độ seed với dữ liệu khách hàng thật và không bật Spring profile `demo` trên deployment này.

## 6. Kết quả triển khai ngày 2026-08-18

| Gate | Kết quả |
|---|---|
| Docker services | PostgreSQL, backend, frontend healthy |
| Flyway | 20/20 migration thành công, schema version 20 |
| Backend readiness | `UP` qua loopback, Nginx và Cloudflare |
| Frontend | HTTP 200 qua Nginx và Cloudflare |
| Authentication | `admin@core.local`, MFA tạm tắt, session thật |
| Browser CORS | `https://crm-mkt-sale.sgodata.com` được cho phép; origin ngoài allowlist bị từ chối |
| Navigation Registry | API thành công |
| Import tổng hợp | 4 batch, 16 accepted, 0 rejected |
| Orders | 5 đơn, net revenue tổng hợp 14.180.000 VND |
| Attribution | 5 đơn được xử lý, 10 kết quả |
| Dashboard | API thật thành công |
| Production Core | không thay đổi; commit `7d1bb98` tiếp tục chạy |

### 6.1. Kết quả phát hành điều hướng ngày 2026-08-23

| Gate | Kết quả |
|---|---|
| Release | `v0.1.3-test`; runtime `64265314df02` |
| Cấu trúc cấp cao | `Trang chủ`, `Nghiệp vụ`, `Quản trị hệ thống` theo đúng thứ tự |
| Trang chủ | `core.home` thuộc section adapter `home`, không nằm trong `business` |
| Nghiệp vụ | Chỉ nhận group/page có key `module.*` từ module nghiệp vụ đang hoạt động và được cấp quyền |
| Quản trị hệ thống | Giữ các chức năng quản trị Core trong section riêng |
| Backend regression | 9/9 test Navigation Registry/API đạt với PostgreSQL 17 |
| Frontend quality | Next.js production build đạt; 6/6 test đạt; lint không có lỗi |
| Runtime smoke test | Đăng nhập, Navigation Registry, Revenue Dashboard và logout đều đạt |
| Container health | PostgreSQL, backend và frontend đều `healthy` |
| Production Core | Không triển khai lại; môi trường Production Core không thay đổi |

Tại thời điểm kiểm tra, phân vùng `/` sử dụng 85%, còn khoảng 12 GB. Phải đặt cảnh báo dung lượng và kiểm tra trước mỗi build/release; không tự động xóa volume hoặc image chưa xác minh.

### 6.2. Kết quả phát hành giao diện xanh ngày 2026-08-23

| Gate | Kết quả |
|---|---|
| Release | `v0.1.4-test`; runtime `8d1592117fd3` |
| Giao diện | Shell và trang đăng nhập dùng bộ token màu xanh chuyển đổi; Next.js chuẩn, không dùng Vinext |
| Cấu trúc điều hướng | Giữ đúng `Trang chủ` → `Nghiệp vụ` → `Quản trị hệ thống`; Trang chủ không nằm trong Nghiệp vụ |
| Nghiệp vụ | Chỉ hiển thị module đang bật và được cấp quyền; section vẫn hiển thị trạng thái trống khi không có module |
| Frontend quality | Next.js production build đạt; 7/7 test đạt; lint 0 lỗi (còn 5 cảnh báo React Hook đã biết) |
| Runtime smoke test | Đăng nhập, Navigation Registry, Revenue Dashboard và logout đều đạt |
| Container health | PostgreSQL, backend và frontend đều `healthy` |
| Public domain | `https://crm-mkt-sale.sgodata.com` trả HTTP 200 |
| Production Core | Không triển khai lại; chỉ phát hành baseline source `core-v1.1.1-project-baseline` |

## 7. Backup và restore drill

Môi trường test hiện chưa chứng minh RPO 15 phút/RTO 1 giờ. Trước production, DevOps phải cấu hình lịch backup, retention, bản sao ngoài server và chạy restore drill có biên bản.

Backup thủ công có kiểm soát:

```bash
install -d -m 0700 /home/ubuntu/backups/crm-mkt-sale
cd /home/ubuntu/crm-mkt-sale-java-core
docker compose --env-file .env -f deploy/project/docker-compose.yml exec -T postgres \
  pg_dump -U core_admin -d crm_mkt_sale -Fc \
  > /home/ubuntu/backups/crm-mkt-sale/crm_mkt_sale.dump
test -s /home/ubuntu/backups/crm-mkt-sale/crm_mkt_sale.dump
```

Restore phải thử vào database kiểm chứng riêng, tuyệt đối không ghi đè database đang chạy. Sau restore, chạy migration validate, đối soát row count, đăng nhập và critical smoke test. Chỉ xóa database kiểm chứng sau khi đã lưu kết quả drill.

File storage nằm trong volume `crm-mkt-sale_project_files`; backup database không thay thế backup volume này.

## 8. Rollback và failure recovery

- Nếu build/health gate thất bại trước khi switch: giữ stack đang chạy, đọc log và sửa trên nhánh mới.
- Nếu release mới lỗi nhưng chưa có migration phá vỡ tương thích: khởi động lại image SHA trước bằng `APP_VERSION=<sha-trước>` và cùng Compose file đã kiểm chứng.
- Nếu migration đã ghi dữ liệu: ưu tiên forward-fix. Không tự chạy SQL downgrade hoặc restore đè khi chưa chốt điểm phục hồi và phạm vi mất dữ liệu.
- Nếu database không healthy: dừng release, kiểm tra disk, log PostgreSQL và backup; không xóa volume.
- Nếu domain lỗi nhưng loopback healthy: kiểm tra DNS Cloudflare, chế độ SSL, Nginx `server_name`, `nginx -t` và error log.
- Nếu host lỗi: dựng stack trên máy thay thế từ release tag, restore database/file backup, cập nhật DNS và chạy đầy đủ smoke test.

## 9. Security và các gate còn lại

- Cloudflare đang ở Flexible theo ràng buộc hạ tầng hiện tại: traffic Cloudflare-to-origin chưa được TLS bảo vệ. Mục tiêu dài hạn là origin certificate và Full (Strict), triển khai theo từng hostname để không làm gián đoạn domain khác.
- Bootstrap password phải được đổi và bàn giao qua kênh bí mật; không ghi vào Git, issue, log hoặc tài liệu.
- MFA chỉ tạm tắt cho test. Production khách hàng cần quyết định security riêng và test enrollment/recovery.
- Log Spring Boot hiện có cảnh báo generated development password của auto-configuration; custom token security vẫn bảo vệ API, nhưng nên loại bỏ auto-configuration không dùng ở bản hardening tiếp theo.
- Cần branch protection, dependency/security scan, monitoring, alert, backup/restore drill và dữ liệu thật được BA/kế toán xác nhận trước production.

## 10. Nhật ký sự cố triển khai

### 2026-08-18 — Login trả `Invalid CORS request`

- Hiện tượng: frontend cố parse phản hồi text 403 thành JSON và hiển thị `Unexpected token 'I'`.
- Nguyên nhân: container backend chưa nhận CORS allowlist cho domain dự án; smoke test cũ không gửi header `Origin` nên không phát hiện.
- Khắc phục: cấu hình `CORE_CORS_ALLOWED_ORIGIN_PATTERNS` theo môi trường, thêm kiểm thử CORS backend, thêm `Origin` vào smoke test và fallback khi frontend nhận phản hồi không phải JSON.
- Phòng ngừa: domain, reverse proxy và browser-origin smoke test là release gate bắt buộc cho mọi dự án mới.
- Xác minh sau triển khai: đăng nhập thật, Navigation Registry và dashboard đều thành công; preflight từ domain dự án trả `200`, origin ngoài allowlist trả `403`; ba container đều healthy.
