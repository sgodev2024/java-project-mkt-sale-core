-- E5-S03: chuỗi hash per-tenant cho audit event
alter table audit.event add column if not exists sequence_no bigint;
alter table audit.event add column if not exists payload_hash char(64);
alter table audit.event add column if not exists prev_hash char(64);
create index if not exists audit_event_chain_idx on audit.event(tenant_key, sequence_no) where sequence_no is not null;

create table if not exists audit.chain_state(
  tenant_key varchar(80) primary key,
  last_sequence bigint not null default 0,
  last_hash char(64) not null default repeat('0', 64),
  updated_at timestamptz not null default now()
);
-- E5-S04: checkpoint (mốc đã verify) và legal hold (cấm purge)
create table if not exists audit.checkpoint(
  tenant_key varchar(80) primary key,
  verified_sequence bigint not null,
  chain_hash char(64) not null,
  created_at timestamptz not null default now()
);
create table if not exists audit.legal_hold(
  tenant_key varchar(80) primary key,
  reason varchar(400) not null,
  held_by varchar(254) not null,
  created_at timestamptz not null default now()
);

-- Purge duy nhất qua hàm này: tôn trọng legal hold, chỉ xóa batch đã qua checkpoint (E5-S04)
create or replace function audit.purge_old(p_tenant varchar(80), p_older_than timestamptz) returns bigint
language plpgsql security definer set search_path = audit, public as $$
declare
  v_checkpoint bigint;
  v_deleted bigint;
begin
  if exists(select 1 from audit.legal_hold where tenant_key = p_tenant) then
    raise exception 'LEGAL_HOLD_ACTIVE: tenant % đang bị giữ dữ liệu', p_tenant;
  end if;
  select verified_sequence into v_checkpoint from audit.checkpoint where tenant_key = p_tenant;
  if v_checkpoint is null then
    return 0;
  end if;
  delete from audit.event
   where tenant_key = p_tenant and sequence_no is not null
     and sequence_no <= v_checkpoint and occurred_at < p_older_than;
  get diagnostics v_deleted = row_count;
  return v_deleted;
end $$;

-- Append-only cho runtime role: chỉ được INSERT/SELECT trên audit.event (tamper-evidence ở tầng DB)
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'core_app') THEN
    REVOKE UPDATE, DELETE ON audit.event FROM core_app;
    GRANT SELECT, INSERT, UPDATE ON audit.chain_state TO core_app;
    GRANT SELECT, INSERT ON audit.checkpoint TO core_app;
    GRANT SELECT, INSERT, DELETE ON audit.legal_hold TO core_app;
    GRANT EXECUTE ON FUNCTION audit.purge_old(varchar(80), timestamptz) TO core_app;
    RAISE NOTICE 'audit append-only applied to core_app';
  ELSE
    RAISE NOTICE 'role core_app not present; skipping E5 grants';
  END IF;
END $$;
