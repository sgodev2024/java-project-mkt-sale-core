-- E8: file lifecycle STAGING -> SCANNING -> ACTIVE | QUARANTINED, attachment link, legal hold
alter table files.file_object add column if not exists upload_session_id uuid;
alter table files.file_object add column if not exists scanned_at timestamptz;
alter table files.file_object add column if not exists scan_result varchar(20);
alter table files.file_object add column if not exists resource_type varchar(120);
alter table files.file_object add column if not exists resource_id varchar(160);
alter table files.file_object add column if not exists legal_hold boolean not null default false;
-- checksum chỉ biết sau khi stream xong — cho phép NULL ở giai đoạn staging
alter table files.file_object alter column checksum_sha256 drop not null;
update files.file_object set scan_result='CLEAN', scanned_at=updated_at where status='ACTIVE' and scanned_at is null;

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'core_app') THEN
    GRANT SELECT, INSERT, UPDATE ON files.file_object TO core_app;
    RAISE NOTICE 'E12 grants ok';
  END IF;
END $$;
