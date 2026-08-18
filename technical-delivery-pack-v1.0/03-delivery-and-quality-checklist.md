# Java Core Platform — Delivery & Quality Checklist

| Thuộc tính | Giá trị |
|---|---|
| Mã tài liệu | `CP-DOD-007` |
| Phiên bản | `1.0.0` |
| Trạng thái | Approved Baseline |

## 1. Definition of Ready — Story

- [ ] Business/technical outcome rõ ràng.
- [ ] Owner module và data owner rõ.
- [ ] DOMAIN/DYNAMIC classification đã có nếu tạo resource.
- [ ] Public API/event impact đã xác định.
- [ ] Tenant/security/data classification đã xác định.
- [ ] Transaction và failure behavior đã xác định.
- [ ] Migration/rollback hoặc roll-forward impact đã xác định.
- [ ] Acceptance criteria kiểm thử được.
- [ ] Dependency và test fixture sẵn sàng.
- [ ] Không còn quyết định kiến trúc cần developer tự đoán.

## 2. Definition of Done — Code Change

- [ ] Code nằm đúng module/package boundary.
- [ ] Không truy cập internal repository/table module khác.
- [ ] Unit/module/integration tests xanh.
- [ ] Architecture tests xanh.
- [ ] Tenant negative tests được thêm/cập nhật.
- [ ] Permission fail-closed được kiểm chứng.
- [ ] Không network I/O trong transaction.
- [ ] Audit/outbox behavior được kiểm chứng nếu applicable.
- [ ] Retry/idempotency được kiểm chứng nếu applicable.
- [ ] Migration fresh/upgrade test xanh.
- [ ] API/event contract và docs được cập nhật.
- [ ] Log không chứa PII/secret ngoài policy.
- [ ] Static/dependency/security scan xanh.
- [ ] Reviewer con người phê duyệt.

## 3. Definition of Done — Module

- [ ] Manifest, owner, version và Core compatibility rõ.
- [ ] Public API/named interface nhỏ và version được.
- [ ] Internal package không bị truy cập ngoài module.
- [ ] Schema, migration và data ownership riêng.
- [ ] Tenant/RLS coverage đầy đủ.
- [ ] Permission, audit và observability tích hợp.
- [ ] Published/consumed event được version hóa.
- [ ] Failure/retry/dead-letter behavior rõ.
- [ ] Runbook và dashboard có owner.
- [ ] Sample/use-case documentation chạy được.

## 4. Pull Request checklist

- [ ] PR mô tả outcome và risk, không chỉ mô tả file thay đổi.
- [ ] Diff giới hạn trong scope.
- [ ] Không commit secret, binary không cần thiết hoặc generated noise.
- [ ] Dependency mới có lý do, license và vulnerability check.
- [ ] Database change có migration, không sửa migration đã apply.
- [ ] Breaking contract được đánh dấu.
- [ ] AI-generated code đã được hiểu, test và review như code con người.
- [ ] Screenshot/log/test evidence đính kèm khi cần.

## 5. Database change checklist

- [ ] Owner schema/module đúng.
- [ ] Naming/type conventions đúng.
- [ ] Tenant-owned table có `tenant_id NOT NULL`.
- [ ] RLS ENABLE + FORCE, runtime-role test.
- [ ] Constraint cưỡng chế invariant quan trọng.
- [ ] Index gắn với query/plan, không thêm theo cảm tính.
- [ ] Không cross-module FK nếu thiếu ADR.
- [ ] Expand-and-contract compatible.
- [ ] Backfill bounded, resumable và observable.
- [ ] Retention/purge/classification được khai báo.
- [ ] Backup/restore impact đã đánh giá.

## 6. Security checklist

- [ ] Authentication không tin tenant/header do client tự khai.
- [ ] Authorization ở application boundary.
- [ ] Missing/error policy trả Deny.
- [ ] Admin operation có MFA và audit.
- [ ] Password/token/secret không log/audit rõ.
- [ ] Refresh rotation/revoke được test.
- [ ] Upload/download/webhook có abuse/SSRF controls.
- [ ] Rate/request-size/query-complexity limits phù hợp.
- [ ] Data classification, masking và retention đúng.
- [ ] Security Approver ký các thay đổi boundary quan trọng.

## 7. Event/job checklist

- [ ] Outbox enqueue cùng transaction nghiệp vụ.
- [ ] Contract có ID/type/version/tenant/correlation.
- [ ] Không serialize persistence entity.
- [ ] Handler idempotent.
- [ ] Retry bounded, backoff + jitter.
- [ ] Poison message/job vào DEAD/DLQ.
- [ ] Replay/requeue có permission, reason và audit.
- [ ] Crash/duplicate test có evidence.

## 8. File checklist

- [ ] Size/type/quota validate.
- [ ] Upload stream, không giữ file lớn trong memory.
- [ ] Checksum và optional scan policy.
- [ ] Object key opaque/tenant-scoped.
- [ ] Download authorization tại request time.
- [ ] Signed URL thời hạn ngắn.
- [ ] Orphan/missing-object reconciliation.
- [ ] Retention/legal hold/purge đúng.

## 9. Release readiness

- [ ] Release scope và tag được khóa.
- [ ] CI quality gates xanh.
- [ ] Fresh install và N-1 upgrade xanh.
- [ ] API/event compatibility xanh.
- [ ] Security/RLS negative suite xanh.
- [ ] Performance regression trong ngưỡng.
- [ ] Migration preflight thành công.
- [ ] Backup trước release và restore procedure sẵn sàng.
- [ ] Dashboard/alerts/runbooks cập nhật.
- [ ] Roll-forward/rollback decision rõ.
- [ ] Service Owner phê duyệt.

## 10. Production deployment

- [ ] Artifact immutable, checksum/signature khớp.
- [ ] Config/secret đúng môi trường.
- [ ] Migration job có lock và hoàn tất.
- [ ] Readiness/smoke test đạt.
- [ ] SLI được theo dõi trong release window.
- [ ] Worker/outbox chỉ resume khi API/database consistency đạt.
- [ ] Incident/rollback authority sẵn sàng.
- [ ] Release record và audit hoàn tất.

## 11. Backup and recovery

- [ ] Backup mới nhất trong RPO.
- [ ] Restore drill còn hiệu lực theo tier.
- [ ] Database/object recovery point được hiểu rõ.
- [ ] Outbox/job/inbox lease/replay procedure sẵn sàng.
- [ ] Audit checkpoint verification thành công.
- [ ] DR contact và runbook được kiểm tra.

## 12. Customer source delivery

- [ ] Backend/frontend source đầy đủ.
- [ ] Core/standard/domain/customer module source đầy đủ.
- [ ] Maven wrapper, BOM và build configuration.
- [ ] Migration, seed hợp lệ và automated tests.
- [ ] Dockerfile, Compose và Helm tương ứng deployment.
- [ ] Configuration template không chứa secret.
- [ ] OpenAPI, event/module contracts.
- [ ] SBOM và third-party notices.
- [ ] Build/deploy/backup/restore/upgrade runbooks.
- [ ] Release notes và known limitations.
- [ ] Tag/commit/checksum khớp production.
- [ ] Không private binary/package/repository bắt buộc chưa bàn giao.
- [ ] Clean-room build/test/deploy thành công.
- [ ] Customer Technical Representative ký nghiệm thu.

## 13. Release 1.0 final acceptance

- [ ] Product Owner xác nhận scope.
- [ ] Technical Lead xác nhận architecture/module contracts.
- [ ] Security Approver xác nhận tenant/identity/permission.
- [ ] Data Architect xác nhận schema/migration/retention.
- [ ] Platform Owner xác nhận deployment/backup/SLO.
- [ ] QA xác nhận acceptance/regression evidence.
- [ ] Service Owner xác nhận vận hành.
- [ ] Customer representative xác nhận source delivery khi áp dụng.

