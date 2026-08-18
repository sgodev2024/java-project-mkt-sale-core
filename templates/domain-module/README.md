# Domain Module Template

Template này tạo module nghiệp vụ code-first mà không sửa package nội bộ của Core.

## Contract

- Module key: kebab-case, duy nhất toàn deployment.
- Java package nằm ngoài `vn.coreplatform` cho dự án khách hàng.
- Module khai báo `coreVersionRange`; runtime fail-fast nếu không tương thích.
- Domain aggregate sở hữu repository, migration, transaction và invariant.
- `DomainResourceAdapter` chỉ expose read/history; không đưa generic CRUD vào domain.
- Migration dùng version dạng `VyyyyMMddHHmm` để tránh trùng migration Core/module khác.
- Navigation được đóng góp qua `ModuleContributor`; frontend không hard-code sidebar.
- Mọi bảng tenant-owned có `tenant_id NOT NULL`, RLS ENABLE + FORCE và runtime grant.

## Tạo module

```powershell
./scripts/new-domain-module.ps1 `
  -ModuleKey revenue-intelligence `
  -ModuleName "Revenue Intelligence" `
  -BasePackage vn.sgodata.revenueintelligence `
  -OutputRoot C:\work\revenue-intelligence
```

Sau khi sinh template, developer phải hoàn thiện migration, permission, audit, event và test; placeholder cố ý làm build thất bại nếu chưa được xử lý.

