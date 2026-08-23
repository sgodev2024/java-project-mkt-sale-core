# Business Analysis & System Requirements Specification — Java Core Platform

| Thuộc tính | Giá trị |
|---|---|
| Mã tài liệu | `CP-BA-001` |
| Phiên bản | `1.1.4` |
| Trạng thái | Approved |
| Ngày lập | 2026-08-17 |
| Ngày cập nhật | 2026-08-17 |
| Sản phẩm | Java Core Platform |
| Giai đoạn | 1 — Business Analysis + Frontend Baseline |
| Baseline thay thế | `core-platform-ba-requirements-v1.0.md` |

## 1. Tóm tắt điều hành

Tổ chức cần xây dựng một Java Core Platform mới, độc lập, không chứa nghiệp vụ của ERP, CRM, MES hoặc ngành cụ thể. Nền tảng được dùng nội bộ để đội phát triển xây dựng các hệ thống cho nhiều khách hàng và nhiều lĩnh vực.

Frappe Framework chỉ là nguồn tham khảo để nghiên cứu các ưu điểm đã được kiểm chứng. Sản phẩm không đặt mục tiêu sao chép, tương thích hoặc trở thành một phiên bản Java của Frappe.

Nền tảng phải:

- phục vụ đội 5 kỹ sư hiện tại và khoảng 7 kỹ sư sau 12 tháng;
- phù hợp với đội ngũ trình độ cơ bản, có AI hỗ trợ;
- phát hành production hai tuần một lần;
- hỗ trợ cloud riêng và on-premise;
- mặc định mỗi khách hàng có deployment và database riêng;
- có đường tiến hóa sang SaaS trong tương lai;
- chuyển giao đầy đủ mã nguồn theo hợp đồng;
- tuân thủ yêu cầu bảo vệ dữ liệu cá nhân tại Việt Nam;
- hỗ trợ nhiều service tier thay vì áp một mức hạ tầng đắt tiền cho mọi khách hàng.

## 2. Bối cảnh và vấn đề cần giải quyết

Nếu mỗi dự án khách hàng tự xây lại authentication, permission, audit, file, job, API, event và extension mechanism thì sẽ phát sinh:

- trùng lặp mã nguồn;
- chất lượng không đồng đều;
- khó vá bảo mật;
- khó nâng cấp đồng loạt;
- phụ thuộc vào từng lập trình viên;
- tăng chi phí chuyển giao và bảo trì;
- không tạo được tài sản kỹ thuật tái sử dụng.

Java Core Platform phải cung cấp các capability kỹ thuật dùng chung, nhưng không áp đặt một mô hình nghiệp vụ chung cho mọi ngành.

## 3. Mục tiêu kinh doanh

### BG-01 — Tái sử dụng

Giảm việc xây lại capability nền tảng giữa các dự án khách hàng.

### BG-02 — Chuẩn hóa chất lượng

Mọi dự án phải sử dụng cùng baseline về bảo mật, audit, API, migration, kiểm thử và vận hành.

### BG-03 — Rút ngắn thời gian triển khai

Đội phát triển phải có thể khởi tạo một dự án mới từ Core và tập trung vào module nghiệp vụ.

### BG-04 — Chuyển giao độc lập

Khách hàng phải có thể nhận đầy đủ source package, build, triển khai và vận hành hệ thống theo phạm vi hợp đồng mà không phụ thuộc vào môi trường nội bộ của nhà cung cấp.

### BG-05 — Tiến hóa dài hạn

Core phải hỗ trợ cloud riêng, on-premise và khả năng phát triển SaaS sau này mà không yêu cầu viết lại toàn bộ nền tảng.

## 4. Ngoài mục tiêu

Phiên bản Core ban đầu không nhằm:

- cung cấp sẵn nghiệp vụ ERP, CRM, MES hoặc ngành cụ thể;
- sao chép API, database schema, UI hoặc naming của Frappe;
- cung cấp public marketplace;
- cho khách hàng tự phát triển module trên nền tảng ở giai đoạn đầu;
- triển khai microservices mặc định;
- bắt buộc Kafka, Kubernetes hoặc Data Lake cho mọi khách hàng;
- xây một low-code platform hoàn chỉnh ngay trong MVP;
- tự xây identity provider, workflow engine hoặc search engine enterprise nếu sản phẩm sẵn có đáp ứng yêu cầu.

## 5. Stakeholder và vai trò

| Stakeholder | Trách nhiệm |
|---|---|
| Product Owner | Chốt phạm vi và ưu tiên capability |
| Technical Lead | Chịu trách nhiệm thiết kế và chất lượng kỹ thuật |
| Development Team | Xây dựng Core và module khách hàng |
| QA/Quality Owner | Xây acceptance test và kiểm soát regression |
| Platform/DevOps Engineer | CI/CD, deployment, backup, monitoring và phục hồi |
| Security Approver | Phê duyệt authentication, authorization và bảo vệ dữ liệu |
| Customer Technical Representative | Nghiệm thu source package và khả năng triển khai độc lập |
| Legal/Commercial Owner | Chốt quyền sở hữu, quyền tái sử dụng và nghĩa vụ chuyển giao mã nguồn |

## 6. Persona chính

### P-01 — Platform Developer

Lập trình viên nội bộ sử dụng Core để tạo module và hệ thống khách hàng. Cần contract rõ ràng, ví dụ chạy được, lỗi dễ chẩn đoán và hạn chế tối đa cấu hình ngầm.

### P-02 — Technical Lead

Quản lý kiến trúc, dependency, version, migration và chất lượng của Core cùng các module.

### P-03 — System Administrator

Cài đặt, cấu hình, backup, nâng cấp, theo dõi và phục hồi deployment của khách hàng.

### P-04 — Customer Technical Team

Nhận source package, tài liệu và artifact để build hoặc triển khai trong hạ tầng riêng.

### P-05 — Application User

Sử dụng sản phẩm nghiệp vụ được xây trên Core; không trực tiếp thao tác với Core như một development platform.

## 7. Mô hình khái niệm

Các thuật ngữ bắt buộc được phân biệt:

| Thuật ngữ | Định nghĩa |
|---|---|
| Customer | Pháp nhân hoặc đơn vị ký hợp đồng |
| Deployment | Một hệ thống được cài đặt và vận hành độc lập |
| Tenant | Ranh giới cô lập dữ liệu logic trong deployment |
| Organization | Cơ cấu công ty/đơn vị/phòng ban bên trong tenant |
| Core | Capability kỹ thuật dùng chung, không chứa nghiệp vụ ngành |
| Module | Gói capability có boundary, version và owner riêng |
| Customer Extension | Module/configuration chỉ áp dụng cho một khách hàng |
| Source Package | Toàn bộ source, cấu hình, migration, build và deployment asset thuộc phạm vi bàn giao |

## 8. Nguyên tắc sản phẩm

### PR-01 — Một Core codebase chuẩn

Core phải có một dòng mã nguồn chuẩn. Không fork Core theo từng khách hàng trong quy trình phát triển thông thường.

### PR-02 — Tùy chỉnh qua module và configuration

Khác biệt khách hàng phải được triển khai bằng module, extension point, policy hoặc configuration có version.

### PR-03 — Mỗi khách hàng triển khai độc lập

Baseline hiện tại là một deployment và một database riêng cho mỗi khách hàng.

### PR-04 — SaaS-ready, không SaaS-first

Tenant context và data ownership phải được thiết kế từ đầu, nhưng SaaS control plane và shared-customer deployment không thuộc MVP.

### PR-05 — Modular monolith trước

Core và module chạy theo modular monolith ở giai đoạn đầu. Việc tách microservice cần bằng chứng về scale, ownership, availability hoặc security boundary.

### PR-06 — Metadata không phải mô hình domain mặc định

Core không dùng `DocType/DocField` hoặc một Document Engine làm abstraction trung tâm. Code-first Domain Model là mặc định cho aggregate nghiệp vụ. Dynamic Resource là module tùy chọn; presentation và policy metadata phải độc lập với persistence model.

## 9. Quyết định BA về mô hình tài nguyên

### 9.1 Quyết định được phê duyệt

Core sử dụng **Three-Plane Resource Architecture**. Ba mặt phẳng có trách nhiệm độc lập và không được hợp nhất thành một generic data engine toàn hệ thống.

#### Plane 1 — Domain Model

Code-first Java aggregate và relational persistence là lựa chọn mặc định cho:

- dữ liệu có invariant nghiệp vụ;
- transaction nhiều bước hoặc nhiều entity;
- dữ liệu cần foreign key, unique/check constraint hoặc locking;
- workload có query/index chuyên biệt;
- dữ liệu mà sai lệch có thể gây thiệt hại tài chính hoặc vận hành.

Mỗi domain module sở hữu aggregate, repository, migration, application service và domain event của mình. Core không chứa tên hoặc logic của domain cụ thể.

#### Plane 2 — Dynamic Resource

Dynamic Resource là module chuẩn tùy chọn dành cho:

- form và cấu hình động;
- danh mục hoặc custom entity đơn giản;
- dữ liệu cần thêm field thường xuyên;
- tài nguyên không có transaction hoặc invariant phức tạp.

Module này cung cấp `ResourceDefinition`, `FieldDefinition`, schema version, generic CRUD, generic validation và dynamic form description. Dynamic Resource không được dùng cho ledger, tồn kho, thanh toán, giao dịch sản xuất hoặc aggregate critical.

#### Plane 3 — Presentation & Policy

Mặt phẳng này mô tả form, list, label, localization, permission mapping, masking, audit, export và data classification. Nó không sở hữu dữ liệu nghiệp vụ. Thay đổi bố cục hoặc label không được tạo database migration.

### 9.2 Unified Resource Contract

Domain aggregate và Dynamic Resource có thể đăng ký capability chung qua `ResourceDescriptor`, nhưng không bắt buộc dùng chung repository, table, transaction hoặc validation implementation.

`ResourceDescriptor` tối thiểu mô tả:

- resource type và owner module;
- storage mode;
- schema/version;
- supported actions;
- permission và audit policy;
- data classification;
- presentation descriptor.

### 9.3 Vị trí của Dynamic Resource

Dynamic Resource không nằm trong platform kernel. Kernel chỉ cung cấp Resource Registry SPI và các contract dùng chung. Dynamic Resource được đóng gói thành standard module có thể bật/tắt theo dự án.

### 9.4 Custom field trên code-first aggregate

Code-first aggregate có thể hỗ trợ `custom_attributes` cùng `CustomFieldDefinition` khi hợp đồng yêu cầu. Custom field không được thay thế domain field chuẩn hoặc chứa invariant critical. Field cần tìm kiếm phải có index strategy; field nhạy cảm phải có classification và masking.

### 9.5 Quy tắc phân loại

Nếu một entity có invariant, transaction phức tạp, constraint mạnh, tải cao hoặc rủi ro tài chính/vận hành thì bắt buộc dùng Domain Model. Dynamic Resource chỉ được chọn khi nhóm phát triển chứng minh entity phù hợp. Khi chưa chắc chắn, mặc định dùng Domain Model.

## 10. Phạm vi capability

### 10.1 MVP — Platform Kernel

| ID | Capability | Mức ưu tiên |
|---|---|---|
| CAP-001 | Three-Plane Resource Architecture và Resource Registry SPI | Must |
| CAP-002 | Generic CRUD cho dynamic resource phù hợp | Must |
| CAP-003 | Domain Resource Adapter cho code-first aggregate | Must |
| CAP-004 | Validation engine | Must |
| CAP-005 | Authentication bằng tài khoản nội bộ | Must |
| CAP-006 | Role và record-level permission | Must |
| CAP-007 | Tenant context và isolation foundation | Must |
| CAP-008 | Audit log | Must |
| CAP-009 | Document/resource history | Must |
| CAP-010 | Hook/extension system | Must |
| CAP-011 | Domain event và integration event | Must |
| CAP-012 | Background job và scheduler | Must |
| CAP-013 | File/attachment management | Must |
| CAP-014 | CSV import/export | Must |
| CAP-015 | Search cơ bản | Must |
| CAP-016 | Localization/i18n foundation | Must |
| CAP-017 | Naming/numbering series | Must |
| CAP-018 | Archive/soft delete policy | Must |
| CAP-019 | Module packaging và compatibility | Must |
| CAP-020 | Service account/API key | Must |
| CAP-021 | Webhook | Must |
| CAP-022 | Developer documentation và sample module | Must |
| CAP-023 | Reproducible source build và delivery package | Must |

### 10.2 Giai đoạn 2

- Field-level permission.
- Organization hierarchy.
- OIDC/SSO và LDAP/Active Directory integration.
- Workflow/BPMN integration.
- Email notification.
- Excel import/export.
- Full-text search chuyên dụng.
- Feature flags.
- Integration adapter framework.
- Report/query builder có guardrail.

### 10.3 Giai đoạn 3 hoặc theo hợp đồng

- SMS/push notification.
- Dashboard metadata.
- AI extension capability.
- Low-code UI builder nâng cao.
- Public developer portal/marketplace.
- SaaS control plane.
- Cross-customer operation console.

## 11. Functional requirements

### FR-001 — Module lifecycle

Hệ thống phải cho phép đăng ký, kiểm tra compatibility, bật/tắt và nâng cấp module có kiểm soát.

### FR-002 — Resource model

Hệ thống phải hỗ trợ cả dynamic resource và code-first aggregate qua public contract thống nhất ở mức cần thiết, không buộc dùng cùng một persistence strategy.

### FR-003 — Validation

Hệ thống phải thực hiện validation tại server và trả lỗi có cấu trúc, có mã lỗi và correlation ID.

### FR-004 — Permission

Hệ thống phải kiểm tra quyền theo tenant, subject, resource, action và context; không chỉ dựa vào việc ẩn chức năng ở UI.

### FR-005 — Audit

Hệ thống phải ghi actor, tenant, action, resource, thời điểm, kết quả và correlation ID cho thao tác quan trọng.

### FR-006 — Extension

Module phải có thể mở rộng lifecycle qua contract được công bố mà không sửa source Core.

### FR-007 — Event

Thay đổi trạng thái quan trọng phải có khả năng phát event tin cậy; consumer phải có cơ chế chống xử lý trùng lặp.

### FR-008 — Background processing

Tác vụ dài phải chạy ngoài request transaction, có retry, trạng thái và khả năng quan sát.

### FR-009 — File

File phải có metadata, ownership, authorization, checksum, size/type limit và storage abstraction.

### FR-010 — Migration

Core, module, metadata và database change phải có version, migration history và compatibility validation.

### FR-011 — Source delivery

Hệ thống phải tạo được source package đầy đủ theo Mục 16.

## 12. Deployment requirements

### DEP-001 — Môi trường

Core phải hỗ trợ:

- cloud riêng;
- private cloud của khách hàng;
- on-premise trên VM/máy chủ vật lý;
- môi trường hạn chế hoặc không có Internet khi hợp đồng yêu cầu.

### DEP-002 — Packaging

- Development baseline: Docker Compose.
- Production nhỏ: container trên VM hoặc Docker Compose được hardening.
- Production lớn/HA: Kubernetes.
- Artifact baseline: OCI container image.
- Kubernetes packaging: Helm chart khi capability này được kích hoạt.

### DEP-003 — Tối thiểu hóa hạ tầng

Deployment tối thiểu phải có thể chạy bằng application, PostgreSQL và object/file storage. Các thành phần như Kafka, Redis, workflow engine và search engine phải được thêm theo capability hoặc service tier, không bắt buộc cho mọi khách hàng.

## 13. Capacity baseline

Khi chưa có hợp đồng cụ thể, Core được thiết kế và kiểm thử theo tier Medium:

| Chỉ số | Medium baseline |
|---|---:|
| Người dùng đăng ký | 5.000 |
| Người dùng đồng thời | 500 |
| Peak API requests/second | 200 |
| Tổng document/resource | 20 triệu |
| File đính kèm | 2 TB |
| Database | 500 GB |

Large tier chỉ được cam kết sau benchmark và capacity review.

### Performance target ban đầu

| Tác vụ | Mục tiêu |
|---|---|
| CRUD đọc đơn | p95 ≤ 300 ms |
| CRUD ghi đơn | p95 ≤ 500 ms |
| Search có phân trang | p95 ≤ 1 giây |
| Báo cáo nhẹ | ≤ 5 giây |
| Notification gần thời gian thực | ≤ 3 giây |

Kết quả không bao gồm network latency ngoài phạm vi deployment; điều kiện benchmark phải được ghi rõ.

## 14. Service tier

| Tier | Availability | RPO | RTO | Đối tượng |
|---|---:|---:|---:|---|
| Pilot | 99% | 24 giờ | 8 giờ | Pilot/hệ thống không critical |
| Standard | 99,5% | 1 giờ | 4 giờ | Production thông thường |
| Critical | 99,9% | 15 phút | 1 giờ | Hệ thống vận hành quan trọng |

Core phải có khả năng triển khai theo cả ba tier. Từng hợp đồng chỉ cam kết tier tương ứng với hạ tầng và dịch vụ vận hành đã mua.

## 15. Security, privacy và compliance

- Core mặc định xử lý dữ liệu cá nhân do có tài khoản, email, IP và audit.
- Mọi resource/field phải có khả năng phân loại dữ liệu.
- TLS, secret protection, backup encryption, audit, monitoring và restore test là baseline production.
- MFA bắt buộc cho administrator ở production.
- Authentication phải có extension contract để hỗ trợ OIDC/SSO và LDAP sau MVP.
- Authorization phải fail closed.
- Dữ liệu khách hàng không được xuất hiện trong log phát triển hoặc AI prompt nếu chưa có cơ sở và biện pháp bảo vệ phù hợp.
- Mã do AI hỗ trợ phải qua cùng review, test và security gate như mã do con người viết.
- Mỗi dự án phải có privacy/compliance assessment riêng theo loại dữ liệu và lĩnh vực.

## 16. Chuyển giao toàn bộ mã nguồn

### 16.1 Phạm vi kỹ thuật bắt buộc

Mỗi hợp đồng phải bàn giao source đầy đủ của **một hệ thống khách hàng chạy hoàn chỉnh**. Gói bàn giao không được yêu cầu khách hàng phân biệt Core với phần tùy chỉnh để có thể build hoặc vận hành. Phạm vi tối thiểu gồm:

- toàn bộ source backend và frontend của hệ thống production;
- toàn bộ source module, thư viện nội bộ và phần mở rộng được sử dụng;
- database migration và seed/reference data được phép bàn giao;
- source cấu hình build;
- Dockerfile, Docker Compose và deployment manifest thuộc phạm vi;
- Helm chart nếu deployment dùng Kubernetes;
- API và event contract;
- cấu hình mẫu không chứa secret;
- automated test và test report thuộc phạm vi sản phẩm;
- SBOM và danh sách third-party dependency/license;
- hướng dẫn build, cấu hình, triển khai, backup, restore và upgrade;
- release note và known limitations;
- checksum/tag/commit xác định chính xác source tương ứng artifact production.

Không được tồn tại private binary, private package repository, build service hoặc thành phần runtime bắt buộc nằm ngoài phạm vi bàn giao, trừ dịch vụ third-party đã được công bố rõ trong hợp đồng.

### 16.2 Acceptance criteria chuyển giao

Khách hàng hoặc nhóm nghiệm thu độc lập phải có thể:

1. Clone/extract source package trong môi trường sạch.
2. Build mà không phụ thuộc repository hoặc máy cá nhân không được bàn giao.
3. Chạy automated test theo tài liệu.
4. Tạo artifact có thể truy nguyên tới commit/tag.
5. Triển khai môi trường mới từ tài liệu và asset bàn giao.
6. Restore database/file từ backup mẫu hoặc bài kiểm thử được thống nhất.
7. Xác định third-party license và dependency.

### 16.3 Quyền tái sử dụng Core và ranh giới hợp đồng

Công ty duy trì một canonical Core codebase và giữ quyền sử dụng, phát triển, sửa đổi, cấp phép và tái sử dụng Core cho các khách hàng khác. Gói source bàn giao cho khách hàng là snapshot đầy đủ của hệ thống tương ứng với production release, không phải một fork làm thay đổi canonical Core.

Hợp đồng phải quy định rõ:

- khách hàng nhận đầy đủ source cần thiết để build, triển khai và vận hành hệ thống của họ;
- công ty tiếp tục sở hữu và tái sử dụng tài sản Core dùng chung;
- dữ liệu, secret và nghiệp vụ riêng của khách hàng không được tái sử dụng;
- quyền sửa đổi/phân phối của khách hàng và phạm vi hỗ trợ sau khi khách hàng tự sửa;
- nghĩa vụ cung cấp hoặc không cung cấp các phiên bản Core tương lai;
- quyền đối với module được phát triển riêng theo hợp đồng;
- nghĩa vụ và giới hạn của open-source/third-party dependency.

Legal/Commercial Owner phải chuyển các nguyên tắc này thành điều khoản hợp đồng chuẩn trước khi ký kết.

## 17. Release và chất lượng

- Production release mục tiêu: hai tuần một lần.
- Mọi pull request phải chạy automated test và quality gate.
- Branch phải ngắn hạn; artifact release phải immutable.
- AI-generated code không được miễn review.
- Database migration phải được kiểm tra compatibility.
- Hotfix security có release flow riêng.
- Source package bàn giao phải được tạo từ đúng release tag.

Quality gate tối thiểu:

- unit test;
- integration test;
- architecture boundary test;
- tenant-isolation negative test;
- API/event compatibility test;
- migration test;
- static analysis;
- dependency/security scan;
- reproducible clean-build test.

## 18. Ràng buộc và giả định

### Ràng buộc

- Đội hiện tại có 5 người, dự kiến 7 người sau 12 tháng.
- Trình độ đội ngũ ở mức cơ bản.
- Chưa có DevOps/SRE chuyên trách.
- Ngân sách hiện tại hạn chế và chưa xác định.
- Deployment phải hỗ trợ nhiều loại hạ tầng khách hàng.
- Mọi hợp đồng yêu cầu chuyển giao toàn bộ mã nguồn.

### Giả định

- Một Technical Lead sẽ được chỉ định.
- DevOps/Platform Engineer sẽ được tuyển hoặc thuê trước production pilot.
- Khách hàng chấp nhận module/configuration thay vì fork Core mặc định.
- Service tier và hạ tầng được ghi rõ trong hợp đồng.
- Yêu cầu ngành đặc thù được phân tích ở cấp dự án/module.

## 19. Rủi ro BA

| ID | Rủi ro | Mức | Biện pháp |
|---|---|---:|---|
| R-01 | Phạm vi Core quá lớn so với đội ngũ | Cao | Chia MVP/GĐ2/GĐ3, có exit criteria |
| R-02 | Dynamic Resource bị lạm dụng cho aggregate phức tạp | Cao | Three-Plane model, Domain Model mặc định và classification gate |
| R-03 | Fork source theo khách hàng | Cao | Một Core codebase, extension module và release snapshot |
| R-04 | Chuyển giao nhưng không build độc lập | Cao | Clean-room build là acceptance test |
| R-05 | Không có DevOps nhưng cam kết RTO thấp | Cao | Service tier và tuyển/thuê trước production |
| R-06 | Chưa biết dữ liệu ngành | Trung bình | Data classification và security profile theo module |
| R-07 | AI tạo mã không nhất quán | Trung bình | Contract, quality gate và human approval |
| R-08 | Hỗ trợ quá nhiều mô hình hạ tầng | Trung bình | OCI artifact chung và deployment profiles |
| R-09 | Quyền sở hữu Core không rõ trong hợp đồng | Cao | Legal clause chuẩn trước ký hợp đồng |

## 20. Acceptance criteria giai đoạn BA

Giai đoạn BA hoàn tất khi:

- [x] Mục tiêu và ngoài mục tiêu được thống nhất.
- [x] Đối tượng sử dụng chính được xác định.
- [x] Quy mô đội và release cadence được xác định.
- [x] Deployment model và database isolation baseline được xác định.
- [x] Capacity baseline được chấp nhận.
- [x] Service tier được chấp nhận.
- [x] Capability được chia theo giai đoạn.
- [x] Chuyển giao toàn bộ source được ghi thành requirement.
- [x] Three-Plane Resource Architecture được phê duyệt để chuyển sang thiết kế kiến trúc.
- [x] Khách hàng nhận source đầy đủ của hệ thống hoàn chỉnh; công ty duy trì và tái sử dụng Core chuẩn.
- [x] Product Owner phê duyệt tài liệu BA.

## 21. Requirement traceability tóm tắt

| Business goal | Requirement liên quan | Kiểm chứng dự kiến |
|---|---|---|
| BG-01 Tái sử dụng | PR-01, PR-02, FR-001, FR-006 | Sample module, boundary test |
| BG-02 Chuẩn hóa | FR-003–FR-010, Mục 17 | CI quality gates |
| BG-03 Tăng tốc | CAP-001–CAP-023 | Thời gian tạo sample project/module |
| BG-04 Chuyển giao | FR-011, Mục 16 | Clean-room build/deployment |
| BG-05 Tiến hóa | PR-03–PR-05, Mục 12–14 | Deployment profile và architecture test |

## 22. Đầu vào bắt buộc cho giai đoạn 2

Sau khi BA được phê duyệt, giai đoạn Runtime Flow & Architecture phải tạo:

1. Context và container architecture.
2. Module/component decomposition.
3. ADR chi tiết hóa Three-Plane Resource Architecture và classification gate.
4. Luồng application bootstrap và module loading.
5. Luồng authentication, request, CRUD, permission và audit.
6. Hook lifecycle và transaction boundary.
7. Event/outbox và background-job flow.
8. File, search, notification và workflow integration boundaries.
9. Deployment architecture cho Pilot, Standard và Critical.
10. Security, observability, failure và recovery architecture.
11. API/event/module compatibility contracts.
12. Architecture acceptance criteria trước khi thiết kế database.

## 23. Phê duyệt

| Vai trò | Tên | Quyết định | Ngày |
|---|---|---|---|
| Product Owner | Project Sponsor | Approved | 2026-08-14 |
| Technical Lead | Chưa gán | Pending | |
| Security Approver | Chưa gán | Pending | |
| Legal/Commercial Owner | Chưa gán | Pending | |

## 24. Baseline frontend và mô hình vận hành v1.1

### 24.1 Bối cảnh quyết định

Phiên bản 1.1 làm rõ sản phẩm ở giai đoạn hiện tại là hệ thống phần mềm theo yêu cầu được cài đặt độc lập tại hạ tầng khách hàng. Đây không phải giao diện vận hành SaaS. Mỗi deployment có một khách hàng, một database và một source package hoàn chỉnh. Khái niệm tenant vẫn tồn tại như ranh giới kỹ thuật để bảo toàn khả năng tiến hóa, nhưng không được trình bày thành chức năng quản trị nhiều khách hàng trong giao diện mặc định.

Tài khoản có quyền cao nhất được hiển thị bằng thuật ngữ **Quản trị viên hệ thống (System Administrator)**. Mã vai trò legacy `PLATFORM_ADMIN` được giữ trong backend để tương thích dữ liệu và API hiện tại; frontend không dùng tên này làm nhãn nghiệp vụ.

### 24.2 Mười sáu quyết định frontend đã phê duyệt

| ID | Quyết định baseline | Hệ quả bắt buộc |
|---|---|---|
| FE-BA-01 | Một application shell thống nhất | Không tách giao diện thành Workspace nghiệp vụ và Workspace quản trị Core. |
| FE-BA-02 | System Administrator là tài khoản cao nhất trong deployment khách hàng | Tài khoản này thấy toàn bộ capability quản trị và các module nghiệp vụ mà deployment đã cài đặt; API vẫn kiểm tra quyền ở backend. |
| FE-BA-03 | Giai đoạn hiện tại là dedicated deployment, không phải SaaS | Không có customer switcher, tenant switcher hoặc màn hình cross-customer trong frontend mặc định. |
| FE-BA-04 | Tenant là khái niệm kỹ thuật nội bộ | Không đưa quản trị tenant vào menu sản phẩm dành cho khách hàng; SaaS Control Plane là phạm vi tương lai riêng. |
| FE-BA-05 | Navigation Registry là nguồn duy nhất của menu | Frontend không hard-code danh sách module; backend tổng hợp, kiểm tra và lọc menu theo module, quyền và trạng thái. |
| FE-BA-06 | Cây điều hướng tối đa ba cấp | Cấu trúc chuẩn là Section → Group → Page; registry phải fail startup khi contributor khai báo sâu hơn. |
| FE-BA-07 | Nghiệp vụ đặt trước, quản trị hệ thống đặt cuối | Các section nghiệp vụ có thứ tự ưu tiên cao hơn; section Quản trị hệ thống luôn ở cuối sidebar và chỉ hiện cho người có quyền. |
| FE-BA-08 | Module tự sở hữu menu của mình | Module đăng ký page/group qua contract công bố; không sửa Core shell khi cài thêm module. |
| FE-BA-09 | Một trang chủ chung theo ngữ cảnh | Người dùng thấy lối vào nghiệp vụ được cấp quyền; System Administrator thấy tổng quan vận hành và vẫn truy cập module nghiệp vụ từ menu chung. |
| FE-BA-10 | Điều hướng dùng URL chuẩn | Route dùng `/home`, `/business/...`, `/administration/...`; hỗ trợ truy cập trực tiếp, refresh và lịch sử trình duyệt. |
| FE-BA-11 | Quản trị tổ chức và truy cập là capability của deployment | Frontend có màn hình Người dùng, Cơ cấu tổ chức, Vai trò & phân quyền nối API thật; không dùng số liệu mẫu cố định làm dữ liệu vận hành. |
| FE-BA-12 | Trải nghiệm phải theo quyền và đáp ứng thiết bị | Sidebar, tìm kiếm lệnh, yêu thích, trạng thái rỗng/lỗi/tải và mobile layout dùng cùng manifest đã được backend lọc. |
| FE-BA-13 | Menu tác vụ cá nhân hiển thị theo capability tham gia xử lý nhiệm vụ được giao | Core không đăng ký sẵn `Công việc của tôi`. Module có hộp việc thật mới được đăng ký item `ASSIGNMENT`; item này luôn qua Permission Decision Point bằng policy đúng resource/action, kể cả System Administrator. Quyền wildcard quản trị không làm phát sinh menu tác vụ cá nhân. |
| FE-BA-14 | Nghiệp vụ tách khỏi Production Core và tập trung trong section Nghiệp vụ | Section `business` là vùng mở rộng chuẩn, tương đương vai trò phân khu của `system-administration`. Module mẫu `approval-domain` chỉ được nạp ở profile `demo`/`test`, nằm trong group `Nghiệp vụ mẫu`; Production không đăng ký menu, API hoặc metadata của module này. |
| FE-BA-15 | Giao diện ESG tối giản và mọi trạng thái vận hành phải có nguồn dữ liệu thật | Frontend dùng Next.js App Router chính thức, nền “chuyển đổi xanh” theo bộ token dùng chung `transition-green-*`, SVG icon theo ngữ nghĩa module; nút/số liệu không có API hoặc telemetry phải được nối thật, loại bỏ hoặc ghi vào gap backlog. Production không giữ dữ liệu seed minh họa. |
| FE-BA-16 | Trang chủ là điểm điều hướng cấp cao độc lập | `Trang chủ`, `Nghiệp vụ` và `Quản trị hệ thống` cùng nằm ở cấp cao nhất. `Trang chủ` không thuộc section `business`; bên trong `Nghiệp vụ` chỉ chứa group/page do các module nghiệp vụ đang hoạt động và được cấp quyền đóng góp. |

### 24.3 Kiến trúc thông tin được phê duyệt

Sidebar được dựng theo thứ tự:

1. Yêu thích của người dùng, nếu có.
2. **Trang chủ** là page cấp cao độc lập.
3. Section **Nghiệp vụ** chứa group/page của các module nghiệp vụ.
4. Section **Quản trị hệ thống** ở cuối, chỉ dành cho tài khoản được cấp quyền.

Cấu trúc tham chiếu:

```text
Ứng dụng
├── Trang chủ
├── Nghiệp vụ
│   ├── Nghiệp vụ mẫu [chỉ demo/test]
│   │   └── Đề nghị phê duyệt
│   └── [Group/Page do module nghiệp vụ đăng ký]
└── Quản trị hệ thống
    ├── Nền tảng
    │   ├── Quản lý module
    │   └── Tài nguyên mở rộng
    ├── Tổ chức & truy cập
    │   ├── Người dùng
    │   ├── Cơ cấu tổ chức
    │   └── Vai trò & phân quyền
    ├── Vận hành
    │   ├── Events & Jobs
    │   └── Tệp tin
    └── Cấu hình hệ thống
```

Không được tạo một Workspace riêng cho từng module. Khi số module tăng, chúng được phân nhóm theo domain/capability, có sort order, tìm kiếm toàn cục và khả năng yêu thích.

### 24.4 Ma trận hiển thị theo persona

| Khu vực | System Administrator | Quản lý/Approver | Nhân viên |
|---|---:|---:|---:|
| Trang chủ và module được cấp quyền | Có | Có | Có |
| Công việc của tôi | Chỉ khi có capability/nhiệm vụ | Chỉ khi có capability/nhiệm vụ | Chỉ khi có capability/nhiệm vụ |
| Đề nghị phê duyệt | Chỉ demo/test + policy | Chỉ demo/test + policy | Chỉ demo/test + policy |
| Quản trị hệ thống | Có | Không mặc định | Không |
| Người dùng/tổ chức/vai trò | Có | Chỉ khi được cấp quyền quản trị | Không |
| Tenant/customer switcher | Không | Không | Không |

Menu chỉ là cơ chế khám phá. Việc không hiển thị menu không thay thế authorization tại API. Mọi endpoint nhạy cảm tiếp tục fail closed.

### 24.4.1 Quy tắc nghiệp vụ chi tiết cho tác vụ cá nhân

| ID | Quy tắc đã duyệt |
|---|---|
| FE-BR-013-01 | Application shell và Core không hard-code `Công việc của tôi`; menu này thuộc module nghiệp vụ có capability hộp việc. |
| FE-BR-013-02 | Module chỉ đăng ký item `ASSIGNMENT` sau khi có đủ view, route, API lấy nhiệm vụ được giao và cặp permission resource/action tương ứng. |
| FE-BR-013-03 | Tài khoản chỉ nhận item khi module đang bật, thỏa authority của section/item và có policy chính xác cho capability assignment. `ROLE_PLATFORM_ADMIN` hoặc policy wildcard `*/*` không được tính là nhiệm vụ được giao. |
| FE-BR-013-04 | System Administrator vẫn có thể thấy hộp việc khi được gán thêm capability assignment chính xác để trực tiếp tham gia quy trình nghiệp vụ. |
| FE-BR-013-05 | Số nhiệm vụ đang mở bằng `0` không làm menu biến mất sau khi tài khoản đã có capability; view hiển thị empty state. Cách này giữ điều hướng ổn định và cho phép xem lịch sử/bộ lọc. Badge số lượng là dữ liệu nghiệp vụ, không phải quyết định authorization. |
| FE-BR-013-06 | API hộp việc phải lọc theo account/organization assignment và kiểm tra permission độc lập. Ẩn menu, badge hoặc kiểm tra tại frontend không thay thế PEP ở backend. |
| FE-BR-013-07 | Truy cập trực tiếp một route không có trong navigation manifest hiệu lực phải được frontend đưa về page hợp lệ đầu tiên; API đích vẫn trả `403` nếu bị gọi trực tiếp. |

### 24.4.2 Ranh giới module nghiệp vụ mẫu và Production Core

| ID | Quy tắc đã duyệt |
|---|---|
| FE-BR-014-01 | `business` là section chuẩn luôn tồn tại cùng Trang chủ; mọi module nghiệp vụ khách hàng đăng ký group/page vào section này, không đặt trong `system-administration`. |
| FE-BR-014-02 | Module mẫu nằm trong package/chunk riêng và phải gắn profile `demo`/`test`; profile `production` hoặc không khai báo profile không được nạp module, controller hay navigation contributor mẫu. |
| FE-BR-014-03 | `approval-domain` đóng góp group `Nghiệp vụ mẫu` và page `Đề nghị phê duyệt`; group tự biến mất khi module không hoạt động hoặc người dùng không có page được phép. |
| FE-BR-014-04 | Production migration/guard loại metadata `approval-domain` cũ khỏi module/resource catalog nhưng giữ bảng và dữ liệu để rollback an toàn. |
| FE-BR-014-05 | Frontend demo được tách thành lazy chunk và chỉ render khi backend trả view `approvals` trong manifest hiệu lực. Direct URL không được tự kích hoạt module mẫu. |
| FE-BR-014-06 | Module nghiệp vụ thật của khách hàng có lifecycle, permission, migration và ownership riêng; không kế thừa namespace hoặc dữ liệu của module demo. |

### 24.4.3 Phân cấp Trang chủ và section module

| ID | Quy tắc đã duyệt |
|---|---|
| FE-BR-016-01 | `core.home` thuộc adapter section kỹ thuật `home`, có route `/home` và được frontend render thành một page cấp cao; không hiển thị một nhãn section `home` bao quanh page. |
| FE-BR-016-02 | Thứ tự cấp cao cố định là `Trang chủ` (10), `Nghiệp vụ` (20), `Quản trị hệ thống` (90); section quản trị chỉ xuất hiện theo quyền. |
| FE-BR-016-03 | Không module nào được đăng ký item vào `home`; registry phải fail startup nếu `core.home` nằm trong `business` hoặc nếu `home` chứa item khác. |
| FE-BR-016-04 | `business` là vùng chứa module nghiệp vụ, không chứa page Core Trang chủ; module tự đóng góp group/page và backend loại item không hoạt động hoặc không được cấp quyền. |
| FE-BR-016-05 | `Nghiệp vụ` vẫn hiển thị khi chưa có module được cấp quyền và trình bày empty state rõ ràng; không tự đưa Trang chủ vào để tránh section rỗng. |
| FE-BR-016-06 | Frontend chỉ mở một section cấp cao tại một thời điểm; khi mở page bằng URL, yêu thích hoặc Command Palette, section sở hữu page được mở tự động. |

### 24.5 Yêu cầu chức năng frontend v1.1

| ID | Yêu cầu |
|---|---|
| FE-FR-001 | Sau đăng nhập, frontend gọi `GET /api/v1/navigation/me` và dựng toàn bộ sidebar từ `sections[]`. |
| FE-FR-002 | Frontend lưu yêu thích và lịch sử gần đây qua `PUT /api/v1/navigation/me/preferences`; không còn lưu Workspace cuối. |
| FE-FR-003 | Page chỉ được mở khi xuất hiện trong manifest hiệu lực của phiên hiện tại. |
| FE-FR-004 | System Administrator quản lý được tài khoản nội bộ, trạng thái tài khoản, đặt lại mật khẩu, cơ cấu tổ chức và vai trò từ dữ liệu thật. |
| FE-FR-005 | Màn hình quản trị không hiển thị số liệu demo dưới nhãn dữ liệu Production. Chỉ số chưa có telemetry phải bị loại bỏ hoặc ghi rõ nguồn. |
| FE-FR-006 | Frontend hỗ trợ trạng thái loading, empty, error, success và thao tác lặp lại an toàn cho các luồng chính. |
| FE-FR-007 | MFA được giữ trong source nhưng có thể tắt bằng cấu hình deployment; khi backend yêu cầu MFA, frontend tiếp tục luồng challenge. |
| FE-FR-008 | Đăng xuất phải thu hồi phiên ở backend khi có thể, xóa token cục bộ và trở về màn hình đăng nhập. |
| FE-FR-009 | Tải trực tiếp mọi route đã đăng ký phải trả về application shell hợp lệ. |
| FE-FR-010 | Giao diện dùng thuật ngữ Quản trị viên hệ thống; `PLATFORM_ADMIN` chỉ là mã tương thích kỹ thuật. |
| FE-FR-011 | Trang chủ không hiển thị dải thông tin tĩnh về tên môi trường, phiên bản Core, loại database và mô hình deployment; thông tin vận hành chi tiết phải đặt tại capability quản trị phù hợp khi có nhu cầu. |
| FE-FR-012 | Production Core hiển thị Trang chủ độc lập và section Nghiệp vụ với module thực được đóng gói/bật; `approval-domain` cùng API `/api/v1/approvals` chỉ tồn tại khi chạy profile `demo` hoặc `test`. |
| FE-FR-013 | Login sử dụng thông điệp “Giải pháp tối ưu hóa vận hành doanh nghiệp”, chữ trắng trên nền ESG; mô tả là “Quản trị vận hành, tài nguyên, phân quyền từ một trung tâm duy nhất.” |
| FE-FR-014 | Access/refresh token phải cùng phạm vi lưu trữ; frontend dùng refresh-token rotation của backend khi access token hết hạn và xóa toàn bộ token khi refresh/logout thất bại. |
| FE-FR-015 | Sidebar render `Trang chủ` độc lập cùng cấp với `Nghiệp vụ` và `Quản trị hệ thống`; `Nghiệp vụ` chỉ render menu module từ Navigation Registry và có empty state khi không có module khả dụng. |

### 24.6 Contract Navigation v1.1

Response công khai:

```json
{
  "revision": "string",
  "sections": [
    {
      "key": "home",
      "label": "Trang chủ",
      "sortOrder": 10,
      "items": [{"key": "core.home", "type": "PAGE", "route": "/home"}]
    },
    {
      "key": "business",
      "label": "Nghiệp vụ",
      "sortOrder": 20,
      "items": []
    }
  ],
  "favoriteKeys": [],
  "recentKeys": []
}
```

Quy tắc:

- section key và item key là duy nhất toàn deployment;
- `home` chỉ chứa page `core.home`; `core.home` không được nằm trong `business`;
- route phải là đường dẫn nội bộ bắt đầu bằng `/`;
- item `GROUP` không có route/view; item `PAGE` phải có route/view;
- group không được chứa group;
- item `ACCESS` dùng quyền truy cập chức năng thông thường;
- chỉ item `PAGE` được dùng `ASSIGNMENT`, đồng thời bắt buộc khai báo `permissionResource` và `permissionAction`;
- System Administrator không bypass đánh giá permission đối với item `ASSIGNMENT`; policy wildcard `*/*` không được xem là nhiệm vụ được giao;
- item `ASSIGNMENT` không được đăng ký nếu module chưa cung cấp view, API và authorization cho hộp việc thực;
- backend loại item của module bị tắt và group rỗng trước khi trả response;
- frontend không tự suy diễn hoặc mở rộng quyền từ role code.

### 24.7 Acceptance criteria v1.1

- [x] Product Owner duyệt FE-BA-01 đến FE-BA-16 làm baseline.
- [x] Không còn Workspace switcher trên application shell.
- [x] Sidebar được dựng từ Navigation Registry động.
- [x] Quản trị hệ thống nằm cuối menu và không xuất hiện với người dùng thường.
- [x] Menu `Công việc của tôi` không xuất hiện chỉ vì tài khoản là System Administrator.
- [x] Application shell không hard-code menu tác vụ cá nhân; quyết định hiển thị nằm ở manifest đã lọc của backend.
- [x] Route không có trong manifest hiệu lực được đưa về page được phép thay vì render view ẩn.
- [x] Registry từ chối cây menu sâu hơn Section → Group → Page.
- [x] Route chuẩn hỗ trợ direct navigation và refresh.
- [x] Người dùng, cơ cấu tổ chức, vai trò và policy dùng API thật.
- [x] Tenant/SaaS control plane không xuất hiện trong frontend dedicated deployment.
- [x] MFA có feature flag deployment và đang được tắt tạm thời theo quyết định vận hành hiện tại.
- [x] Trang chủ không còn dải thông tin tĩnh về môi trường, phiên bản Core, database và mô hình deployment.
- [x] `approval-domain` được tách package/profile và không xuất hiện trong navigation/module/resource catalog của Production Core.
- [x] Section Nghiệp vụ được giữ làm vùng mở rộng; trong demo, Đề nghị phê duyệt nằm dưới group Nghiệp vụ mẫu.
- [x] Trang chủ là page cấp cao độc lập; section Nghiệp vụ chỉ chứa module nghiệp vụ và có empty state khi rỗng.
- [x] Frontend dùng Next.js 16.3.1 App Router/standalone, bảng màu ESG và icon SVG theo module; không dùng vinext.
- [x] Jobs/Outbox, bộ lọc module/resource/file, policy create và refresh-token được nối API thật; UI giả không có nguồn đã bị loại bỏ.
- [x] Migration loại dữ liệu seed legacy khỏi Production và bộ đếm File/Service Account lấy từ database thật.
- [ ] Kiểm thử nghiệm thu trên Production sau khi triển khai release v1.1.
- [ ] Technical Lead và Security Approver ký xác nhận release.

### 24.8 Traceability bổ sung

| Quyết định | Thành phần triển khai | Kiểm chứng |
|---|---|---|
| FE-BA-01, 05–08 | Navigation Registry + application shell | Registry/API/frontend tests |
| FE-BA-02–04 | Security filter + terminology + dedicated UI | Role matrix và negative test |
| FE-BA-09–12 | Next.js App Router + responsive shell | Build, direct-route và browser acceptance |
| FE-BA-13, FE-BR-013-01–07 | `visibilityMode=ASSIGNMENT` + `NavigationVisibilityPolicy` + PDP + route guard | Registry validation, admin-bypass negative test, frontend no-hardcode test và direct-route fallback |
| FE-FR-004–005 | Access Management API + admin pages | Integration test và Production smoke test |
| FE-FR-011 | Loại bỏ deployment environment summary strip khỏi Trang chủ | Frontend source guard test và Production visual smoke test |
| FE-BA-14, FE-BR-014-01–06, FE-FR-012 | Profile-gated demo backend + business section/group + lazy frontend chunk + metadata cleanup | Profile test, navigation test, Production API/menu negative smoke test |
| FE-BA-15, FE-FR-013–014 | ESG design tokens + semantic SVG registry + session rotation + V18 data hygiene + API gap matrix | Next build/test, dependency audit, backend integration và Production smoke test |
| FE-BA-16, FE-BR-016-01–06, FE-FR-015 | Home adapter dành riêng + section accordion + business-only module registry | Registry invariant test, Navigation API order test và frontend source/render test |

## 25. Phê duyệt thay đổi v1.1

| Vai trò | Quyết định | Ngày |
|---|---|---|
| Product Owner / Project Sponsor | Approved FE-BA-01 đến FE-BA-16 | 2026-08-23 |
| Technical Lead | Pending release verification | |
| Security Approver | Pending release verification | |
