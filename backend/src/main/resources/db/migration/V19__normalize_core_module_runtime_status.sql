-- Các trạng thái DISABLED dưới đây từng là dữ liệu trình diễn ở baseline.
-- Bốn module vẫn được nạp và chạy trong runtime nên registry production phải phản ánh HEALTHY.
update platform.module
set status = 'HEALTHY'
where module_key in ('event-outbox', 'job-queue', 'file-management', 'local-identity')
  and status = 'DISABLED'
  and metric = '';
