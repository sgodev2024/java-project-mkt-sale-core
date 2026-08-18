-- approval-domain là sample module, không thuộc Production Core.
-- Giữ bảng/data để rollback; chỉ loại metadata từng làm nó xuất hiện ở runtime Production.
delete from platform.activity
where name like 'approval-request.%' and metadata like 'sample-domain%';

delete from platform.resource_descriptor
where resource_type = 'approval-request';

delete from platform.module
where module_key = 'approval-domain';
