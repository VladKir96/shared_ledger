create extension if not exists pgcrypto with schema extensions;
create schema if not exists ledger_private;

create table public.profiles (
  id uuid primary key references auth.users on delete cascade,
  display_name text not null check (length(display_name) between 1 and 80),
  created_at bigint not null default (extract(epoch from now()) * 1000)::bigint,
  updated_at bigint not null default (extract(epoch from now()) * 1000)::bigint
);

create table public.budgets (
  id uuid primary key,
  name text not null check (length(btrim(name)) between 1 and 80),
  currency text not null check (currency ~ '^[A-Z]{3}$'),
  type text not null check (type in ('PERSONAL', 'SHARED')),
  icon text not null check (length(icon) <= 40),
  color bigint not null check (color between 0 and 4294967295),
  owner uuid not null references public.profiles on delete cascade,
  created_at bigint not null,
  updated_at bigint not null,
  deleted_at bigint,
  version bigint not null default 1,
  last_operation uuid not null,
  sync_seq bigint not null default 1
);

create table public.budget_memberships (
  budget_id uuid not null references public.budgets on delete cascade,
  user_id uuid not null references public.profiles on delete cascade,
  role text not null check (role in ('OWNER', 'EDITOR', 'VIEWER')),
  joined_at timestamptz not null default now(),
  active boolean not null default true,
  primary key (budget_id, user_id)
);
create unique index single_budget_owner on public.budget_memberships(budget_id) where role = 'OWNER';
create index budget_memberships_user on public.budget_memberships(user_id, budget_id) where active;

create table public.accounts (
  id uuid primary key,
  budget_id uuid not null references public.budgets on delete cascade,
  name text not null check (length(btrim(name)) between 1 and 80),
  type text not null check (type in ('CASH','DEBIT_CARD','CREDIT_CARD','BANK','SAVINGS','OTHER')),
  currency text not null check (currency ~ '^[A-Z]{3}$'),
  opening numeric(30,10) not null,
  icon text not null check (length(icon) <= 40),
  color bigint not null check (color between 0 and 4294967295),
  "order" integer not null check ("order" >= 0),
  archived boolean not null,
  created_at bigint not null,
  updated_at bigint not null,
  deleted_at bigint,
  version bigint not null default 1,
  last_operation uuid not null,
  unique (budget_id, id)
);

create table public.categories (
  id uuid primary key,
  budget_id uuid not null references public.budgets on delete cascade,
  name text not null check (length(btrim(name)) between 1 and 80),
  type text not null check (type in ('INCOME', 'EXPENSE')),
  icon text not null check (length(icon) <= 40),
  color bigint not null check (color between 0 and 4294967295),
  "order" integer not null check ("order" >= 0),
  archived boolean not null,
  created_at bigint not null,
  updated_at bigint not null,
  deleted_at bigint,
  version bigint not null default 1,
  last_operation uuid not null,
  unique (budget_id, id),
  unique (budget_id, id, type)
);

create table public.recurring_rules (
  id uuid primary key,
  budget_id uuid not null references public.budgets on delete cascade,
  type text not null check (type in ('INCOME','EXPENSE')),
  account_id uuid not null,
  category_id uuid not null,
  amount numeric(30,10) not null check (amount > 0),
  currency text not null check (currency ~ '^[A-Z]{3}$'),
  frequency text not null check (frequency in ('DAILY','WEEKLY','MONTHLY','YEARLY','CUSTOM')),
  interval integer not null check (interval between 1 and 366),
  start date not null,
  "end" date check ("end" >= start),
  next_index bigint not null check (next_index between 0 and 4000000),
  automatic boolean not null,
  active boolean not null,
  remind boolean not null,
  note text not null check (length(note) <= 2000),
  payer uuid references public.profiles on delete set null,
  creator uuid references public.profiles on delete set null,
  created_at bigint not null,
  updated_at bigint not null,
  deleted_at bigint,
  version bigint not null default 1,
  last_operation uuid not null,
  foreign key (budget_id, account_id) references public.accounts (budget_id, id),
  foreign key (budget_id, category_id, type) references public.categories (budget_id, id, type),
  unique (budget_id, id, type)
);
create index recurring_rules_account on public.recurring_rules(budget_id, account_id);
create index recurring_rules_category on public.recurring_rules(budget_id, category_id);

create table public.transactions (
  id uuid primary key,
  budget_id uuid not null references public.budgets on delete cascade,
  account_id uuid not null,
  category_id uuid,
  type text not null check (type in ('INCOME','EXPENSE','TRANSFER')),
  amount numeric(30,10) not null check (amount > 0),
  currency text not null check (currency ~ '^[A-Z]{3}$'),
  account_currency text not null check (account_currency ~ '^[A-Z]{3}$'),
  budget_currency text not null check (budget_currency ~ '^[A-Z]{3}$'),
  date date not null,
  account_amount numeric(30,10) check (account_amount >= 0),
  budget_amount numeric(30,10) check (budget_amount >= 0),
  account_rate numeric check (account_rate > 0),
  account_rate_date date check (account_rate_date <= date),
  account_rate_source text check (length(account_rate_source) between 1 and 120),
  budget_rate numeric check (budget_rate > 0),
  budget_rate_date date check (budget_rate_date <= date),
  budget_rate_source text check (length(budget_rate_source) between 1 and 120),
  note text not null check (length(note) <= 2000),
  creator uuid references public.profiles on delete set null,
  payer uuid references public.profiles on delete set null,
  destination_id uuid,
  destination_currency text check (destination_currency ~ '^[A-Z]{3}$'),
  destination_amount numeric(30,10) check (destination_amount > 0),
  transfer_rate numeric check (transfer_rate > 0),
  rule_id uuid,
  created_at bigint not null,
  updated_at bigint not null,
  deleted_at bigint,
  version bigint not null default 1,
  last_operation uuid not null,
  foreign key (budget_id, account_id) references public.accounts (budget_id, id),
  foreign key (budget_id, category_id, type) references public.categories (budget_id, id, type),
  foreign key (budget_id, destination_id) references public.accounts (budget_id, id),
  foreign key (budget_id, rule_id, type) references public.recurring_rules (budget_id, id, type),
  unique (rule_id, date),
  check ((account_amount is null and account_rate is null and account_rate_date is null and account_rate_source is null) or
         (account_amount is not null and account_rate is not null and account_rate_date is not null and account_rate_source is not null)),
  check ((budget_amount is null and budget_rate is null and budget_rate_date is null and budget_rate_source is null) or
         (budget_amount is not null and budget_rate is not null and budget_rate_date is not null and budget_rate_source is not null)),
  check (case type when 'TRANSFER' then
    category_id is null and rule_id is null and destination_id is not null and destination_id <> account_id and
    destination_currency is not null and destination_amount is not null and transfer_rate is not null and
    budget_amount is null and account_amount is not null and account_amount = amount and currency = account_currency
  else category_id is not null and destination_id is null and destination_currency is null and destination_amount is null and transfer_rate is null end)
);
create index transactions_budget_date on public.transactions(budget_id, date desc);
create index transactions_account on public.transactions(budget_id, account_id);
create index transactions_category on public.transactions(budget_id, category_id);
create index transactions_destination on public.transactions(budget_id, destination_id);
create index transactions_payer on public.transactions(budget_id, payer);

create table public.exchange_rate_cache (
  base_currency text not null check (base_currency ~ '^[A-Z]{3}$'),
  target_currency text not null check (target_currency ~ '^[A-Z]{3}$'),
  rate_date date not null,
  rate numeric not null check (rate > 0),
  provider text not null,
  fetched_at timestamptz not null default now(),
  primary key (base_currency, target_currency, rate_date, provider)
);

create table public.invitations (
  id uuid primary key default gen_random_uuid(),
  budget_id uuid not null references public.budgets on delete cascade,
  token_hash bytea not null unique,
  role text not null check (role in ('EDITOR','VIEWER')),
  expires_at timestamptz not null,
  max_uses integer not null default 1 check (max_uses between 1 and 20),
  use_count integer not null default 0 check (use_count >= 0 and use_count <= max_uses),
  created_by uuid not null references public.profiles on delete cascade,
  created_at timestamptz not null default now(),
  revoked_at timestamptz
);
create index invitations_budget on public.invitations(budget_id, created_at desc);

create table ledger_private.invite_attempts (
  user_id uuid primary key references auth.users on delete cascade,
  window_start timestamptz not null,
  attempts integer not null
);

create function ledger_private.profile_insert() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  insert into public.profiles (id, display_name)
  values (new.id, coalesce(nullif(left(btrim(new.raw_user_meta_data->>'display_name'), 80), ''), 'Member'))
  on conflict (id) do nothing;
  return new;
end $$;
create trigger sharedledger_new_user after insert on auth.users
for each row execute function ledger_private.profile_insert();
insert into public.profiles (id, display_name) select id,
  coalesce(nullif(left(btrim(raw_user_meta_data->>'display_name'), 80), ''), 'Member') from auth.users
  on conflict (id) do nothing;

create function ledger_private.role_for(p_budget uuid) returns text
language sql stable security definer set search_path = '' as $$
  select m.role from public.budget_memberships m join public.budgets b on b.id = m.budget_id
  where m.budget_id = p_budget and m.user_id = auth.uid() and m.active and b.deleted_at is null
$$;

create function ledger_private.shared_profile(p_user uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select p_user = auth.uid() or exists (
    select 1 from public.budget_memberships m
    where m.user_id = p_user and ledger_private.role_for(m.budget_id) is not null
  )
$$;

do $$
declare t text;
begin
  foreach t in array array['profiles','budgets','budget_memberships','accounts','categories','transactions','recurring_rules','exchange_rate_cache','invitations'] loop
    execute format('alter table public.%I enable row level security', t);
    execute format('revoke all on public.%I from public, anon, authenticated', t);
  end loop;
  foreach t in array array['budget_memberships','accounts','categories','transactions','recurring_rules'] loop
    execute format('create policy budget_member_read on public.%I for select to authenticated using (ledger_private.role_for(budget_id) is not null)', t);
    execute format('grant select on public.%I to authenticated', t);
  end loop;
end $$;
create policy budget_read on public.budgets for select to authenticated using (ledger_private.role_for(id) is not null);
create policy profile_read on public.profiles for select to authenticated using (ledger_private.shared_profile(id));
grant select on public.budgets, public.profiles to authenticated;

revoke all on schema ledger_private from public, anon, authenticated;
revoke all on all functions in schema ledger_private from public, anon, authenticated;
grant execute on function ledger_private.role_for(uuid), ledger_private.shared_profile(uuid) to authenticated;

-- Realtime is a wake-up signal. Clients always pull authorized state after a signal.
do $$
begin
  if exists (select from pg_publication where pubname = 'supabase_realtime') then
    alter publication supabase_realtime add table public.budgets;
  end if;
end $$;
