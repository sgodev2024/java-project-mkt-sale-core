# Core Platform Control Plane — Next.js

Frontend hợp nhất nghiệp vụ và quản trị của Java Core Platform. Runtime sử dụng Next.js 16.3.1 App Router chính thức; không dùng vinext, Vite, Cloudflare Worker hoặc Sites hosting.

## Yêu cầu

- Node.js 22+
- Backend Core Platform có thể truy cập từ trình duyệt

## Chạy local

```bash
npm ci
NEXT_PUBLIC_CORE_API_URL=http://localhost:8080 npm run dev
```

Mở `http://localhost:3000`.

## Build và kiểm thử

```bash
npm run lint
npm test
```

`npm test` chạy `next build`, chuẩn bị static/public asset cho standalone output, khởi động `.next/standalone/server.js` và kiểm tra SSR login shell.

## Docker production

```bash
docker build \
  --build-arg NEXT_PUBLIC_CORE_API_URL=https://api.corejava.sgodata.com \
  -t core-platform-frontend:release .
```

Image cuối chạy non-root bằng `node server.js`, lắng nghe cổng `3000` và chỉ chứa output standalone cần thiết.

## Authentication contract

- `CORE_MFA_ENABLED=true`: login trả challenge, UI hiển thị màn hình TOTP.
- `CORE_MFA_ENABLED=false`: login trả `mfaRequired=false` cùng `session`, UI vào hệ thống ngay sau password.
- Frontend không tự quyết định bỏ MFA; backend là nguồn quyết định cuối cùng.
- Access token và refresh token được lưu cùng phạm vi `remember`; khi API trả `401`, frontend xoay refresh token đúng một lần rồi gọi lại request. Refresh thất bại sẽ đưa phiên về trạng thái cần đăng nhập lại.

## Navigation contract

Frontend gọi `GET /api/v1/navigation/me` sau khi đăng nhập. Sidebar, section, favorites, recent items và Command Palette đều dựng từ `sections[]` đã được backend lọc quyền; không có Workspace switcher và không thêm menu module cố định trong UI shell.

`Trang chủ` được render thành page cấp cao độc lập, cùng cấp với section `Nghiệp vụ` và `Quản trị hệ thống`. Section `Nghiệp vụ` chỉ chứa group/page do module nghiệp vụ đóng góp; khi không có module khả dụng, section vẫn hiện với empty state thay vì lồng Trang chủ bên trong.

Route chuẩn gồm `/home`, `/business/...` và `/administration/...`. Route không xuất hiện trong manifest hiệu lực được thay thế bằng page hợp lệ đầu tiên. Menu tác vụ cá nhân phải do module nghiệp vụ đăng ký với visibility mode `ASSIGNMENT`; application shell không hard-code menu này và quyền System Administrator không tự làm nó xuất hiện.

View mẫu Approval Domain được tách tại `app/demo/approval-workspace.tsx` và lazy-load. Production backend không phát view `approvals`, vì vậy chunk demo không được mở và direct route `/business/approvals` quay về page hợp lệ trong manifest.

Các trang Người dùng, Cơ cấu tổ chức, Vai trò & phân quyền, Jobs/Outbox, file và cấu hình gọi trực tiếp API backend. Giao diện dedicated deployment không hiển thị tenant/customer switcher. Ma trận đầy đủ nằm tại `docs/backend-frontend-gap-analysis-v1.0.md`.

## Sổ thay đổi kỹ thuật

Chạy `npm run docs:changes` để tái tạo phần lịch sử trong `../docs/technical-change-register.md`. GitHub Actions tự chạy bước này sau mỗi push lên `main`.
