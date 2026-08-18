# Business Analysis & System Requirements Specification — Java Core Platform

| Thuộc tính | Giá trị |
|---|---|
| Mã tài liệu | `CP-BA-001` |
| Phiên bản | `1.0.0` |
| Trạng thái | Approved |
| Ngày lập | 2026-08-14 |
| Sản phẩm | Java Core Platform |
| Giai đoạn | 1 — Business Analysis |
| Giai đoạn tiếp theo | Runtime Flow & Architecture |

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
