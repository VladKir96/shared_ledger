create table public.push_preferences (
  user_id uuid primary key references public.profiles(id) on delete cascade,
  enabled boolean not null default true,
  show_amounts boolean not null default true,
  lock_screen_details boolean not null default false
);
create table public.push_budget_preferences (
  user_id uuid not null references public.profiles(id) on delete cascade,
  budget_id uuid not null references public.budgets(id) on delete cascade,
  expense boolean not null default true,
  income boolean not null default true,
  edits boolean not null default false,
  deletions boolean not null default false,
  primary key (user_id,budget_id)
);

alter table public.push_preferences enable row level security;
alter table public.push_budget_preferences enable row level security;
revoke all on public.push_preferences, public.push_budget_preferences from public, anon, authenticated;
grant select, insert, update on public.push_preferences, public.push_budget_preferences to authenticated;
create policy own_push_preferences on public.push_preferences to authenticated
  using (user_id=auth.uid()) with check (user_id=auth.uid());
create policy own_push_budget_preferences on public.push_budget_preferences to authenticated
  using (user_id=auth.uid() and ledger_private.role_for(budget_id) is not null)
  with check (user_id=auth.uid() and ledger_private.role_for(budget_id) is not null
    and exists (select 1 from public.budgets b where b.id=budget_id and b.type='SHARED'));

create table ledger_private.push_devices (
  id uuid primary key,
  user_id uuid not null references public.profiles(id) on delete cascade,
  registration_id text not null unique check (length(registration_id) between 20 and 4096),
  seen_at timestamptz not null default now(),
  active boolean not null default true
);
create index push_devices_user_idx on ledger_private.push_devices(user_id,seen_at);
create table ledger_private.push_events (
  id uuid primary key default gen_random_uuid(),
  budget_id uuid not null references public.budgets(id) on delete cascade,
  transaction_id uuid not null references public.transactions(id) on delete cascade,
  actor uuid references public.profiles(id) on delete set null,
  kind text not null check (kind in ('expense','income','edit','delete')),
  operation uuid not null,
  transaction_version bigint not null,
  happened_at timestamptz not null default now(),
  expanded boolean not null default false,
  unique (transaction_id,kind,operation)
);
create unique index push_create_once on ledger_private.push_events(transaction_id) where kind in ('expense','income');
create index push_events_pending on ledger_private.push_events(happened_at) where not expanded;
create table ledger_private.push_deliveries (
  event_id uuid not null references ledger_private.push_events(id) on delete cascade,
  device_id uuid not null references ledger_private.push_devices(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  state text not null default 'pending' check (state in ('pending','sent','skipped','failed')),
  attempts integer not null default 0,
  due_at timestamptz not null default now(),
  lease uuid,
  provider_result text,
  provider_status integer check (provider_status between 100 and 599),
  primary key (event_id,device_id)
);
create index push_due on ledger_private.push_deliveries(due_at) where state='pending';
create index push_deliveries_device on ledger_private.push_deliveries(device_id);
alter table ledger_private.push_devices enable row level security;
alter table ledger_private.push_events enable row level security;
alter table ledger_private.push_deliveries enable row level security;

-- Hosted Postgres restricts custom settings. Suppress import pushes only for the transaction
-- executing the import wrapper, through a marker clients cannot read or write.
create table ledger_private.push_import_context (transaction_id xid8 primary key);
alter table ledger_private.push_import_context enable row level security;
revoke all on ledger_private.push_import_context from public, anon, authenticated;

create function ledger_private.queue_push() returns trigger
language plpgsql security definer set search_path='' as $$
declare
  actor_id uuid := auth.uid();
  event_kind text;
begin
  if actor_id is null or new.type='TRANSFER' or
     exists (select 1 from ledger_private.push_import_context where transaction_id=pg_current_xact_id()) or
     not exists (select 1 from public.budgets b where b.id=new.budget_id and b.type='SHARED' and b.deleted_at is null) then
    return new;
  end if;

  if tg_op='INSERT' then
    if new.deleted_at is null then event_kind := lower(new.type); end if;
  elsif old.last_operation is distinct from new.last_operation and old.deleted_at is null then
    if new.deleted_at is not null then
      event_kind := 'delete';
    elsif (old.type,old.amount,old.currency,old.category_id,old.account_id,old.date,old.payer,old.note)
      is distinct from (new.type,new.amount,new.currency,new.category_id,new.account_id,new.date,new.payer,new.note) then
      event_kind := 'edit';
    end if;
  end if;

  if event_kind is not null and exists (
    select 1 from public.budget_memberships m where m.budget_id=new.budget_id and m.active and m.user_id<>actor_id
  ) then
    insert into ledger_private.push_events(budget_id,transaction_id,actor,kind,operation,transaction_version)
      values(new.budget_id,new.id,actor_id,event_kind,new.last_operation,new.version) on conflict do nothing;
  end if;
  return new;
end $$;
create trigger queue_shared_budget_push after insert or update on public.transactions
for each row execute function ledger_private.queue_push();

-- Bulk restore is historical activity. Leave the financial import implementation intact.
alter function public.ledger_import_batch(uuid,jsonb) rename to ledger_import_batch_records;
alter function public.ledger_import_batch_records(uuid,jsonb) set schema ledger_private;
revoke all on function ledger_private.ledger_import_batch_records(uuid,jsonb) from public, anon, authenticated;
create function public.ledger_import_batch(p_budget uuid,p_changes jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare result jsonb;
begin
  insert into ledger_private.push_import_context(transaction_id) values(pg_current_xact_id());
  result := ledger_private.ledger_import_batch_records(p_budget,p_changes);
  delete from ledger_private.push_import_context where transaction_id=pg_current_xact_id();
  return result;
end $$;
revoke all on function public.ledger_import_batch(uuid,jsonb) from public, anon, authenticated;
grant execute on function public.ledger_import_batch(uuid,jsonb) to authenticated;

create function public.ledger_push_bind(p_device uuid,p_registration text) returns void
language plpgsql security definer set search_path='' as $$
begin
  if auth.uid() is null or p_device is null or p_registration is null or length(p_registration) not between 20 and 4096 then
    raise exception 'Invalid push registration' using errcode='22023';
  end if;
  -- Serialize registrations for an FCM installation and keep only one account bound to it.
  perform pg_advisory_xact_lock(hashtextextended(p_device::text,73));
  perform pg_advisory_xact_lock(hashtextextended(p_registration,74));
  delete from ledger_private.push_devices where registration_id=p_registration and id<>p_device;
  delete from ledger_private.push_devices where id=p_device and user_id<>auth.uid();
  insert into ledger_private.push_devices(id,user_id,registration_id) values(p_device,auth.uid(),p_registration)
    on conflict(id) do update set registration_id=excluded.registration_id,seen_at=now(),active=true;
end $$;

-- The random installation identifier is a private revocation capability. This also works
-- after offline logout, without retaining the prior account's access or refresh tokens.
create function public.ledger_push_unbind(p_device uuid) returns void
language sql security definer set search_path='' as $$
  delete from ledger_private.push_devices where id=p_device
$$;

create function ledger_private.push_allowed(e ledger_private.push_events, recipient uuid) returns boolean
language sql stable set search_path='' as $$
  select exists (
    select 1 from public.budget_memberships m
    join public.budgets b on b.id=m.budget_id
    left join public.push_preferences p on p.user_id=m.user_id
    left join public.push_budget_preferences bp on bp.user_id=m.user_id and bp.budget_id=m.budget_id
    where m.budget_id=e.budget_id and m.user_id=recipient and m.active and m.joined_at<=e.happened_at
      and m.user_id is distinct from e.actor and b.type='SHARED' and b.deleted_at is null
      and coalesce(p.enabled,true)
      and case e.kind
        when 'expense' then coalesce(bp.expense,true)
        when 'income' then coalesce(bp.income,true)
        when 'edit' then coalesce(bp.edits,false)
        when 'delete' then coalesce(bp.deletions,false)
      end
  )
$$;

create function public.ledger_push_claim() returns jsonb
language plpgsql security definer set search_path='' as $$
declare
  e ledger_private.push_events;
  result jsonb;
begin
  delete from ledger_private.push_events where happened_at < now()-interval '7 days';
  delete from ledger_private.push_devices where seen_at < now()-interval '35 days';
  for e in select * from ledger_private.push_events where not expanded
    order by happened_at limit 50 for update skip locked loop
    if e.happened_at >= now()-interval '1 day' then
      insert into ledger_private.push_deliveries(event_id,device_id,user_id)
        select e.id,d.id,d.user_id from ledger_private.push_devices d
        join public.budget_memberships m on m.user_id=d.user_id and m.budget_id=e.budget_id
        where d.active and ledger_private.push_allowed(e,d.user_id)
        on conflict do nothing;
    end if;
    update ledger_private.push_events set expanded=true where id=e.id;
  end loop;
  update ledger_private.push_deliveries set state='failed',provider_result='retry_limit',lease=null
    where state='pending' and due_at<=now() and attempts>=7;
  with queued as (
    select d.event_id,d.device_id from ledger_private.push_deliveries d
    where d.state='pending' and d.due_at<=now()
    order by d.due_at limit 25 for update skip locked
  ), claimed as (
    update ledger_private.push_deliveries d set attempts=d.attempts+1,
      due_at=now()+interval '5 minutes',lease=gen_random_uuid()
    from queued q where d.event_id=q.event_id and d.device_id=q.device_id
    returning d.event_id,d.device_id,d.lease
  ) select coalesce(jsonb_agg(to_jsonb(c)),'[]'::jsonb) into result from claimed c;
  return result;
end $$;

create function public.ledger_push_target(p_event uuid,p_device uuid,p_lease uuid) returns text
language sql stable security definer set search_path='' as $$
  select d.registration_id from ledger_private.push_deliveries q
  join ledger_private.push_devices d on d.id=q.device_id and d.user_id=q.user_id
  join ledger_private.push_events e on e.id=q.event_id
  where q.event_id=p_event and q.device_id=p_device and q.lease=p_lease and q.state='pending'
    and d.active and e.happened_at>=now()-interval '1 day' and ledger_private.push_allowed(e,q.user_id)
$$;

create function public.ledger_push_finish(p_event uuid,p_device uuid,p_lease uuid,p_result text,p_registration text default null,
  p_retry_seconds integer default 0,p_provider_status integer default null)
returns void language plpgsql security definer set search_path='' as $$
declare delivery ledger_private.push_deliveries;
begin
  if p_result not in ('sent','skipped','retry','permanent','unregistered') then
    raise exception 'Invalid provider result' using errcode='22023';
  end if;
  update ledger_private.push_deliveries set
    state=case p_result when 'sent' then 'sent' when 'skipped' then 'skipped' when 'retry' then 'pending' else 'failed' end,
    provider_result=p_result, provider_status=p_provider_status, lease=null,
    due_at=now()+make_interval(secs=>greatest(60,least(21600,p_retry_seconds),60*(1<<least(attempts-1,8))))
  where event_id=p_event and device_id=p_device and lease=p_lease and state='pending'
  returning * into delivery;
  if found and p_result='unregistered' then
    update ledger_private.push_devices set active=false where id=p_device and user_id=delivery.user_id and registration_id=p_registration;
  end if;
end $$;

-- The data-only push contains just the event ID. Fetch content under the current session after
-- checking recipient, membership, device binding and preferences again.
create function public.ledger_push_details(p_event uuid,p_device uuid) returns jsonb
language sql stable security definer set search_path='' as $$
  select jsonb_build_object('event_id',e.id,'budget_id',b.id,'transaction_id',e.transaction_id,
    'kind',e.kind,'budget',b.name,'category',case when current_event.details_match then c.name end,
    'entry_exists',t.deleted_at is null,
    'amount',case when current_event.details_match and coalesce(p.show_amounts,true)
      then trim_scale(coalesce(t.budget_amount,t.amount))::text end,
    'currency',case when t.budget_amount is null then t.currency else t.budget_currency end,
    'pending_conversion',t.budget_amount is null and t.currency<>t.budget_currency,
    'lock_screen_details',coalesce(p.lock_screen_details,false))
  from ledger_private.push_deliveries q join ledger_private.push_events e on e.id=q.event_id
  join ledger_private.push_devices d on d.id=q.device_id and d.user_id=q.user_id
  join public.budgets b on b.id=e.budget_id
  join public.transactions t on t.id=e.transaction_id
  cross join lateral (
    select t.version=e.transaction_version or not exists (
      select 1 from ledger_private.push_events later where later.transaction_id=t.id
        and later.transaction_version>e.transaction_version
    ) as details_match
  ) current_event
  left join public.categories c on c.id=t.category_id and c.budget_id=b.id
  left join public.push_preferences p on p.user_id=q.user_id
  where e.id=p_event and q.device_id=p_device and q.user_id=auth.uid()
    and d.active and q.state in ('pending','sent') and q.attempts>0 and ledger_private.push_allowed(e,q.user_id)
$$;

revoke all on function ledger_private.queue_push(), ledger_private.push_allowed(ledger_private.push_events,uuid)
  from public, anon, authenticated;
revoke all on function public.ledger_push_bind(uuid,text), public.ledger_push_unbind(uuid),
  public.ledger_push_details(uuid,uuid), public.ledger_push_claim(), public.ledger_push_target(uuid,uuid,uuid),
  public.ledger_push_finish(uuid,uuid,uuid,text,text,integer,integer) from public, anon, authenticated;
grant execute on function public.ledger_push_bind(uuid,text), public.ledger_push_details(uuid,uuid) to authenticated;
grant execute on function public.ledger_push_unbind(uuid) to anon, authenticated;
grant execute on function public.ledger_push_claim(), public.ledger_push_target(uuid,uuid,uuid),
  public.ledger_push_finish(uuid,uuid,uuid,text,text,integer,integer) to service_role;

create function public.ledger_push_settings(p_budget uuid default null,p_field text default null,p_enabled boolean default null)
returns jsonb language plpgsql security definer set search_path='' as $$
declare actor_id uuid := auth.uid();
begin
  if actor_id is null then raise exception 'Sign in first' using errcode='42501'; end if;
  if p_field is not null then
    if p_enabled is null then raise exception 'Invalid preference' using errcode='22023'; end if;
    if p_budget is null and p_field in ('enabled','show_amounts','lock_screen_details') then
      insert into public.push_preferences as p(user_id,enabled,show_amounts,lock_screen_details)
        values(actor_id,case when p_field='enabled' then p_enabled else true end,
          case when p_field='show_amounts' then p_enabled else true end,
          case when p_field='lock_screen_details' then p_enabled else false end)
      on conflict (user_id) do update set
        enabled=case when p_field='enabled' then p_enabled else p.enabled end,
        show_amounts=case when p_field='show_amounts' then p_enabled else p.show_amounts end,
        lock_screen_details=case when p_field='lock_screen_details' then p_enabled else p.lock_screen_details end;
    elsif p_budget is not null and p_field in ('expense','income','edits','deletions')
      and ledger_private.role_for(p_budget) is not null
      and exists (select 1 from public.budgets where id=p_budget and type='SHARED') then
      insert into public.push_budget_preferences as p(user_id,budget_id,expense,income,edits,deletions)
        values(actor_id,p_budget,case when p_field='expense' then p_enabled else true end,
          case when p_field='income' then p_enabled else true end,
          case when p_field='edits' then p_enabled else false end,
          case when p_field='deletions' then p_enabled else false end)
      on conflict (user_id,budget_id) do update set
        expense=case when p_field='expense' then p_enabled else p.expense end,
        income=case when p_field='income' then p_enabled else p.income end,
        edits=case when p_field='edits' then p_enabled else p.edits end,
        deletions=case when p_field='deletions' then p_enabled else p.deletions end;
    else
      raise exception 'Invalid preference or budget access' using errcode='42501';
    end if;
  end if;
  return jsonb_build_object(
    'global',coalesce((select to_jsonb(p)-'user_id' from public.push_preferences p where p.user_id=actor_id),'{}'::jsonb),
    'budgets',coalesce((select jsonb_object_agg(p.budget_id,to_jsonb(p)-array['user_id','budget_id'])
      from public.push_budget_preferences p where p.user_id=actor_id and ledger_private.role_for(p.budget_id) is not null),'{}'::jsonb)
  );
end $$;
revoke all on function public.ledger_push_settings(uuid,text,boolean) from public, anon, authenticated;
grant execute on function public.ledger_push_settings(uuid,text,boolean) to authenticated;
