-- Loại dữ liệu minh họa từng được seed ở baseline đầu tiên.
-- Điều kiện xóa dùng đồng thời khóa/ngữ nghĩa/checksum cố định để không chạm dữ liệu thật.
delete from async.outbox_event where event_type = 'demo.pending.v1';
delete from async.job where job_type like 'demo.%';

delete from platform.activity
where (name = 'file.reconcile' and metadata = 'attempt 1/3')
   or (name = 'identity.session-revoked.v1' and metadata = 'local-identity · security')
   or (name = 'audit.checkpoint' and metadata = '12,400 events')
   or (name = 'approval-request.approved.v1' and metadata = 'sample-domain · tenant acme-vn');

delete from files.file_object
where (name = 'architecture-standard-v1.1.pdf' and checksum_sha256 = repeat('a', 64))
   or (name = 'customer-import-2026-08.csv' and checksum_sha256 = repeat('b', 64))
   or (name = 'audit-checkpoint-20260814.sig' and checksum_sha256 = repeat('c', 64))
   or (name = 'module-manifest.yaml' and checksum_sha256 = repeat('d', 64));

delete from identity.role_summary
where (name = 'Platform Administrator' and user_count = 3 and policy_count = 12)
   or (name = 'Module Maintainer' and user_count = 8 and policy_count = 7)
   or (name = 'Security Auditor' and user_count = 2 and policy_count = 5)
   or (name = 'Application User' and user_count = 184 and policy_count = 9);

-- Các descriptor legacy dưới đây chưa có source-of-truth và mang record_count minh họa.
-- Startup metadata sẽ đăng ký lại File Object và Service Account bằng resource_type chuẩn.
delete from platform.resource_descriptor
where (name = 'Customer Preference' and record_count = 8492)
   or (name = 'Notification Template' and record_count = 86)
   or (name = 'Service Account' and record_count = 42)
   or (name = 'File Object' and record_count = 24903)
   or (name = 'Approval Request' and owner_module = 'sample-domain');

update platform.module set status = 'HEALTHY'
where (module_key = 'event-outbox' and status = 'ATTENTION' and metric = '12 pending')
   or (module_key = 'webhook' and status = 'DISABLED' and metric = 'Optional');

update platform.module set metric = ''
where metric in ('4 contracts','6 policies','12.4k records','12 pending','3 running','84.2 GB','24 definitions','Optional');

update identity.account set display_name = 'Quản trị viên hệ thống'
where email = 'admin@core.local' and display_name = 'Minh Nguyễn';
