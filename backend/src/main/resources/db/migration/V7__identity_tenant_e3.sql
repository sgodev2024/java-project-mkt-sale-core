-- E3-S01: organization thuộc đúng tenant; account chỉ được gắn org cùng tenant (composite FK).
create table identity.organization(
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  parent_id uuid references identity.organization(id),
  code varchar(80) not null,
  name varchar(160) not null,
  status varchar(20) not null default 'ACTIVE',
  created_at timestamptz not null default now(),
  unique(tenant_id, code)
);
create unique index organization_id_tenant_uidx on identity.organization(id, tenant_id);
alter table identity.account add column org_id uuid;
alter table identity.account add constraint account_org_fk foreign key (org_id, tenant_id) references identity.organization(id, tenant_id);

-- E3-S02: password policy metadata + lockout + loại tài khoản
alter table identity.account alter column password_hash type varchar(200);
alter table identity.account add column password_algo varchar(20) not null default 'BCRYPT';
alter table identity.account add column password_changed_at timestamptz;
alter table identity.account add column must_change_password boolean not null default false;
alter table identity.account add column failed_attempts int not null default 0;
alter table identity.account add column locked_until timestamptz;
alter table identity.account add column account_type varchar(20) not null default 'HUMAN';
alter table identity.account add constraint account_type_chk check (account_type in ('HUMAN','SERVICE'));
-- Hash legacy không prefix -> ghi danh mục {bcrypt} cho DelegatingPasswordEncoder, login sẽ tự rehash Argon2id
update identity.account set password_hash = '{bcrypt}' || password_hash where password_hash not like '{%';

-- E3-S03: session family cho refresh rotation + reuse detection
alter table identity.session add column family_id uuid;
alter table identity.session add column rotated_from uuid references identity.session(id);
create index session_family_idx on identity.session(family_id);
create table identity.refresh_token(
  id uuid primary key default gen_random_uuid(),
  session_id uuid not null references identity.session(id),
  token_hash char(64) not null unique,
  expires_at timestamptz not null,
  used_at timestamptz,
  revoked_at timestamptz,
  created_at timestamptz not null default now()
);
create index refresh_lookup_idx on identity.refresh_token(token_hash, expires_at);

-- E3-S04: MFA enrollment per-account (TOTP) + recovery code (chỉ lưu hash SHA-256)
create table identity.mfa_enrollment(
  account_id uuid primary key references identity.account(id),
  tenant_id uuid not null references platform.tenant(id),
  secret_base32 varchar(64) not null,
  confirmed_at timestamptz,
  recovery_code_hashes text[] not null default '{}',
  created_at timestamptz not null default now()
);

-- E3-S05: API key cho service account; secret chỉ lưu hash SHA-256
create table identity.api_key(
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  account_id uuid not null references identity.account(id),
  name varchar(160) not null,
  prefix varchar(12) not null unique,
  key_hash char(64) not null,
  status varchar(20) not null default 'ACTIVE' check (status in ('ACTIVE','ROTATED','REVOKED')),
  last_used_at timestamptz,
  expires_at timestamptz,
  created_at timestamptz not null default now()
);

-- E2/S03: organization cũng là bảng tenant-scoped
alter table identity.organization enable row level security;
alter table identity.organization force row level security;
drop policy if exists tenant_isolation on identity.organization;
create policy tenant_isolation on identity.organization
  using (tenant_id = platform.current_tenant_id())
  with check (tenant_id = platform.current_tenant_id());

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'core_app') THEN
    GRANT USAGE ON SCHEMA identity TO core_app;
    GRANT SELECT, INSERT, UPDATE, DELETE ON identity.organization, identity.refresh_token, identity.mfa_enrollment, identity.api_key TO core_app;
    GRANT SELECT, UPDATE ON identity.session, identity.account TO core_app;
    RAISE NOTICE 'granted E3 tables to core_app';
  ELSE
    RAISE NOTICE 'role core_app not present; skipping E3 grants';
  END IF;
END $$;
