# Unified Navigation Registry v1.1

## Mục tiêu

Navigation Registry cung cấp một cây điều hướng hợp nhất cho ứng dụng dedicated deployment. Module có thể đăng ký menu mà không sửa Core shell. Backend là nguồn quyết định cuối cùng cho manifest hiệu lực; frontend chỉ render các section/page đã được lọc.

Baseline này thay thế mô hình tách Workspace nghiệp vụ và Workspace quản trị. `NavigationWorkspaceDescriptor` tạm thời được giữ như adapter nội bộ cho contributor v1.0, nhưng API công khai dùng khái niệm **section**.

## Runtime flow

1. Mỗi `ModuleContributor` khai báo section adapter và `NavigationItemDescriptor`.
2. `NavigationRegistry` thu thập, chuẩn hóa và kiểm tra toàn bộ manifest trước khi application Ready.
3. `GET /api/v1/navigation/me` lọc theo module đang bật, authority, permission resource/action và tenant/account hiện tại.
4. Backend loại page không được phép, sau đó loại group/section rỗng.
5. Frontend render một sidebar, favorites và Command Palette từ `sections[]`.
6. `PUT /api/v1/navigation/me/preferences` lưu yêu thích và mục gần đây theo tài khoản.

Registry fail startup khi có duplicate key, namespace module sai, section/parent thiếu, parent khác section, group lồng group, route ngoài ứng dụng, parent cycle, visibility mode sai hoặc permission khai báo thiếu resource/action.

## Section chuẩn

| Key | Sort order | Đối tượng |
|---|---:|---|
| `business` | 20 | Người dùng nghiệp vụ và System Administrator |
| `system-administration` | 90 | `ROLE_PLATFORM_ADMIN` |

Section nghiệp vụ được đặt trước. Quản trị hệ thống luôn ở cuối sidebar. Không tạo một section/Workspace riêng cho từng module chỉ để chứa một vài page.

Section `business` là vùng mở rộng chuẩn chờ sẵn cho toàn bộ nghiệp vụ khách hàng. Module đăng ký group/page theo domain của mình trong section này; không đưa page nghiệp vụ vào `system-administration`. Group rỗng bị loại khỏi manifest, vì vậy Production vẫn có vùng Nghiệp vụ và Trang chủ nhưng không hiển thị menu mẫu.

## Tách module demo/test

`approval-domain` là sample module kiểm chứng kiến trúc, không thuộc Production Core. Backend module, controller và metadata initializer nằm trong package `vn.coreplatform.demo.approval` và chỉ active với Spring profile `demo` hoặc `test`. Frontend view nằm trong lazy chunk `app/demo/approval-workspace.tsx` và chỉ được tải khi manifest có view `approvals`.

Trong profile demo/test, cây đóng góp là:

```text
Nghiệp vụ
└── Nghiệp vụ mẫu
    └── Đề nghị phê duyệt
```

Production migration và startup guard loại metadata legacy của `approval-domain` khỏi catalog nhưng không drop bảng/dữ liệu, bảo đảm rollback. Module nghiệp vụ khách hàng phải dùng module key, namespace, migration và permission riêng.

## Cấu trúc ba cấp

Cây hợp lệ duy nhất:

```text
Section
├── Page
└── Group
    └── Page
```

Group không được chứa group. Giới hạn này giữ sidebar ổn định khi số module tăng và giúp tìm kiếm/điều hướng không phụ thuộc cây lồng sâu.

## Namespace

- Core platform: `core.*`
- Module tái sử dụng: `module.<module-key>.*`
- Extension khách hàng: `customer.<customer-key>.*`

Với namespace `module.*`, registry bắt buộc module sở hữu item phải trùng `<module-key>`.

## Visibility mode

| Mode | Mục đích | Admin bypass khi dựng menu |
|---|---|---:|
| `ACCESS` | Page chức năng/quản trị thông thường | Có, nếu authority section/item cho phép |
| `ASSIGNMENT` | Hộp việc, tác vụ cá nhân, hàng đợi được giao | Không |

`ASSIGNMENT` chỉ hợp lệ với `PAGE` và bắt buộc có đủ `permissionResource` và `permissionAction`. Đây là triển khai FE-BA-13: vai trò System Administrator không tự làm xuất hiện “Công việc của tôi”. `NavigationVisibilityPolicy` buộc item này đi qua exact-policy gate; wildcard `*/*` của administrator không được tính là nhiệm vụ được giao. Chỉ policy đúng resource/action mới làm page này xuất hiện.

Core shell không đăng ký hoặc hard-code task inbox. Module nghiệp vụ chỉ được đóng góp item `ASSIGNMENT` khi đã cung cấp đủ view, route, API truy vấn theo account/organization assignment và PEP tương ứng. Nếu tài khoản đã có capability nhưng hiện có `0` tác vụ, menu vẫn ổn định và view hiển thị empty state; số lượng/badge không được dùng như authorization. System Administrator muốn trực tiếp xử lý nghiệp vụ phải được gán capability assignment chính xác như người dùng khác.

## Ví dụ page module

```java
@Override public List<NavigationItemDescriptor> navigationItems() {
  return List.of(new NavigationItemDescriptor(
      "module.sales.orders",
      "business",
      "",
      "Đơn bán hàng",
      "navigation.salesOrders",
      "□",
      "sales-orders",
      "/business/sales-orders",
      20,
      "",
      "SALES_ORDER",
      "READ",
      List.of("sales", "đơn hàng")));
}
```

Ví dụ task inbox:

```java
new NavigationItemDescriptor(
    "module.approval-domain.my-work",
    "business",
    "",
    "Công việc của tôi",
    "navigation.myWork",
    "▣",
    "my-work",
    "/business/my-work",
    15,
    "",
    "WORK_ITEM",
    "READ_ASSIGNED",
    "ASSIGNMENT",
    List.of("tác vụ", "được giao"));
```

Không đăng ký task inbox cho đến khi module có endpoint, view và permission kiểm chứng nhiệm vụ thực tế. Ví dụ chỉ mô tả contract mở rộng; Core hiện tại không tự thêm `Công việc của tôi` vào manifest.

## API contract

```http
GET /api/v1/navigation/me
Authorization: Bearer <access-token>
```

```json
{
  "revision": "a1b2c3d4e5f6",
  "sections": [
    {
      "key": "business",
      "label": "Nghiệp vụ",
      "labelKey": "navigation.section.business",
      "icon": "▦",
      "sortOrder": 20,
      "items": [
        {
          "key": "core.home",
          "parentKey": "",
          "ownerModule": "kernel",
          "label": "Trang chủ",
          "type": "PAGE",
          "viewKey": "home",
          "route": "/home",
          "sortOrder": 10,
          "keywords": ["trang chủ", "tổng quan"]
        }
      ]
    }
  ],
  "favoriteKeys": [],
  "recentKeys": []
}
```

Cập nhật preference:

```http
PUT /api/v1/navigation/me/preferences
Content-Type: application/json

{
  "favoriteKeys": ["core.modules"],
  "recentKeys": ["module.sales.orders", "core.modules"]
}
```

Backend loại key không còn hiển thị, giới hạn 20 favorite và 10 recent. Cột `last_workspace_key` được giữ tương thích database và được reset thành chuỗi rỗng; API v1.1 không công bố hoặc sử dụng giá trị Workspace nữa.

## Security invariants

- Ẩn menu không thay thế authorization tại endpoint.
- Navigation permission dùng cùng `PermissionService` với API nghiệp vụ và fail closed.
- `ACCESS` có thể dùng admin bypass khi dựng menu; endpoint đích vẫn tự kiểm tra quyền.
- `ASSIGNMENT` không dùng admin bypass và không nhận wildcard `*/*` khi dựng menu.
- Số lượng nhiệm vụ là dữ liệu hiển thị; không dùng số lượng bằng `0` để thu hồi capability hoặc làm menu thay đổi liên tục.
- Module `DISABLED` không đóng góp menu.
- Frontend không có fallback menu hard-code và không suy diễn quyền từ role code.
- Page chỉ được mở nếu xuất hiện trong manifest hiệu lực của phiên hiện tại.
- Route không khớp page nào trong manifest được thay thế bằng route page hợp lệ đầu tiên; endpoint đích vẫn tự authorize.

## Checklist khi thêm module

1. Khai báo module descriptor và navigation manifest.
2. Dùng namespace đúng owner module.
3. Chọn section/group đã tồn tại hoặc đóng góp section có key duy nhất.
4. Không tạo group lồng group.
5. Gắn permission tương ứng endpoint; dùng `ASSIGNMENT` cho task inbox.
6. Dùng route chuẩn `/business/...` hoặc `/administration/...`.
7. Đóng gói frontend view cùng module.
8. Viết test manifest hợp lệ, user có quyền, user không có quyền, admin với `ASSIGNMENT`, module disabled và direct route.
