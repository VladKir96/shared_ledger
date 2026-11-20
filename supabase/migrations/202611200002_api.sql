-- Convert the transport's camelCase into the normalized relation's snake_case.
create function ledger_private.to_columns(p_body jsonb) returns jsonb
language sql immutable set search_path = '' as $$
  select coalesce(jsonb_object_agg(lower(regexp_replace(key, '([A-Z])', '_\1', 'g')), value), '{}'::jsonb)
  from jsonb_each(p_body)
$$;

create function ledger_private.to_client(p_body jsonb) returns jsonb
language sql immutable set search_path = '' as $$
  select coalesce(jsonb_object_agg(
    split_part(key, '_', 1) || replace(initcap(substr(key, length(split_part(key, '_', 1)) + 1)), '_', ''),
    case when jsonb_typeof(value) = 'number' and key = any(array[
      'opening','amount','account_amount','budget_amount','account_rate','budget_rate','destination_amount','transfer_rate'
    ]) then to_jsonb(value #>> '{}') else value end
  ), '{}'::jsonb)
  from jsonb_each(p_body - array['version','last_operation','sync_seq'])
$$;

create function public.ledger_apply(p_kind text, p_budget uuid, p_id uuid, p_base bigint, p_operation uuid, p_body jsonb)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  actor uuid := auth.uid();
  access_role text;
  relation_name text;
  current_row jsonb;
  body jsonb;
  owner_only boolean;
  result_row jsonb;
  names text;
  patch text;
  next_version bigint;
  local_now bigint := (extract(epoch from now()) * 1000)::bigint;
begin
  if actor is null or p_operation is null or p_id is null or p_budget is null or p_base < 0 or
     p_base is null or jsonb_typeof(p_body) is distinct from 'object' or pg_column_size(p_body) > 16000 then
    raise exception 'Invalid ledger operation' using errcode='22023';
  end if;
  relation_name := case p_kind when 'budget' then 'budgets' when 'account' then 'accounts'
    when 'category' then 'categories' when 'rule' then 'recurring_rules' when 'entry' then 'transactions' end;
  if relation_name is null or (p_kind = 'budget' and p_id <> p_budget) then
    raise exception 'Unknown record kind' using errcode='22023';
  end if;
  owner_only := p_kind in ('budget','account','category');
  perform pg_advisory_xact_lock(hashtextextended(p_budget::text, 63));
  access_role := ledger_private.role_for(p_budget);
  if access_role is null then
    if p_kind = 'budget' and p_base = 0 and not exists (select from public.budgets where id = p_budget) then
      access_role := 'OWNER';
    elsif p_kind = 'budget' and exists (select from public.budgets b where b.id=p_budget and b.owner=actor and b.last_operation=p_operation) then
      select to_jsonb(b) into current_row from public.budgets b where b.id=p_budget;
      return jsonb_build_object('status','ok','version',current_row->'version','body',ledger_private.to_client(current_row));
    else
      raise exception 'Budget access required' using errcode='42501';
    end if;
  end if;
  if access_role = 'VIEWER' or owner_only and access_role <> 'OWNER' then
    raise exception 'This role cannot write this record' using errcode='42501';
  end if;

  execute format('select to_jsonb(r) from public.%I r where id=$1%s', relation_name,
    case when p_kind = 'budget' then '' else ' and budget_id=$2' end)
    into current_row using p_id, p_budget;
  if current_row->>'last_operation' = p_operation::text then
    return jsonb_build_object('status','ok','version',current_row->'version','body',ledger_private.to_client(current_row));
  end if;
  if coalesce((current_row->>'version')::bigint, 0) <> p_base then
    return jsonb_build_object('status','conflict','version',coalesce((current_row->>'version')::bigint, 0),
      'body', case when current_row is null then null else ledger_private.to_client(current_row) end);
  end if;
  next_version := p_base + 1;
  body := ledger_private.to_columns(p_body) || jsonb_build_object('id', p_id, 'version', next_version,
    'last_operation', p_operation, 'updated_at', local_now, 'created_at',
    coalesce((current_row->>'created_at')::bigint, local_now));
  if p_kind = 'budget' then
    body := body || jsonb_build_object('owner', actor, 'sync_seq', coalesce((current_row->>'sync_seq')::bigint,0) + 1);
    if current_row is not null and current_row->>'type' is distinct from body->>'type' then
      raise exception 'The budget type is immutable' using errcode='22023';
    end if;
  else
    body := body || jsonb_build_object('budget_id', p_budget);
  end if;
  if p_kind in ('entry','rule') then
    body := body || jsonb_build_object('creator', case when current_row is null then to_jsonb(actor) else current_row->'creator' end);
    if body->>'payer' is not null and body->>'payer' is distinct from current_row->>'payer' and
      not exists (select 1 from public.budget_memberships m where m.budget_id = p_budget and m.user_id = (body->>'payer')::uuid and m.active) then
      raise exception 'Payer must be an active budget member' using errcode='22023';
    end if;
  end if;
  if p_kind = 'account' and current_row is not null and body->>'currency' is distinct from current_row->>'currency'
    and exists (select 1 from public.transactions t where t.budget_id=p_budget and (t.account_id=p_id or t.destination_id=p_id)) then
    raise exception 'Account currency is locked by transaction history' using errcode='22023';
  end if;
  if p_kind = 'entry' then
    if not exists (select 1 from public.accounts a where a.budget_id=p_budget and a.id=(body->>'account_id')::uuid
      and a.currency=body->>'account_currency') or
      (body->>'destination_id' is not null and not exists (select 1 from public.accounts a
      where a.budget_id=p_budget and a.id=(body->>'destination_id')::uuid and a.currency=body->>'destination_currency')) then
      raise exception 'Account currency mismatch' using errcode='22023';
    end if;
    if body->>'rule_id' is not null and exists (select 1 from public.transactions t where t.budget_id=p_budget
      and t.rule_id=(body->>'rule_id')::uuid and t.date=(body->>'date')::date and t.id<>p_id) then
      raise exception 'A recurring occurrence already exists' using errcode='23505';
    end if;
  end if;

  select string_agg(format('%I', column_name), ', ' order by ordinal_position),
         string_agg(format('%1$I = excluded.%1$I', column_name), ', ' order by ordinal_position)
  into names, patch from information_schema.columns
  where table_schema = 'public' and table_name = relation_name and column_name <> 'id';
  execute format('insert into public.%1$I as r (id, %2$s)
    select id, %2$s from jsonb_populate_record(null::public.%1$I, $1)
    on conflict (id) do update set %3$s where r.version=$2 %4$s returning to_jsonb(r)',
    relation_name, names, patch, case when p_kind='budget' then '' else 'and r.budget_id=$3' end)
  into result_row using body, p_base, p_budget;
  if result_row is null then raise exception 'Record scope mismatch' using errcode='42501'; end if;
  if p_kind = 'budget' and current_row is null then
    insert into public.budget_memberships (budget_id,user_id,role) values(p_budget,actor,'OWNER');
  elsif p_kind <> 'budget' then
    update public.budgets set sync_seq=sync_seq+1 where id=p_budget;
  end if;
  return jsonb_build_object('status','ok','version',next_version,'body',ledger_private.to_client(result_row));
end $$;

create function public.ledger_manifest() returns jsonb
language sql stable security definer set search_path = '' as $$
  select coalesce(jsonb_agg(jsonb_build_object('id',b.id,'seq',b.sync_seq,'role',m.role)), '[]'::jsonb)
  from public.budgets b join public.budget_memberships m on b.id=m.budget_id
  where m.user_id=auth.uid() and m.active and b.deleted_at is null
$$;

create function public.ledger_snapshot(p_budget uuid) returns jsonb
language sql stable security definer set search_path = '' as $$
  select jsonb_build_object(
    'seq', b.sync_seq,
    'records', (select jsonb_agg(jsonb_build_object('kind',kind,'version',version,'operation',j->>'last_operation','body',ledger_private.to_client(j))
     order by priority, j->>'id') from (
      select 0 priority, 'budget' kind, b.version version, to_jsonb(b) j
      union all select 1, 'account', a.version, to_jsonb(a) from public.accounts a where a.budget_id=p_budget
      union all select 2, 'category', c.version, to_jsonb(c) from public.categories c where c.budget_id=p_budget
      union all select 3, 'rule', r.version, to_jsonb(r) from public.recurring_rules r where r.budget_id=p_budget
      union all select 4, 'entry', t.version, to_jsonb(t) from public.transactions t where t.budget_id=p_budget
    ) records),
    'members', (select coalesce(jsonb_agg(jsonb_build_object('budgetId',m.budget_id,'userId',m.user_id,
      'name',p.display_name,'role',m.role,'active',m.active)), '[]'::jsonb)
      from public.budget_memberships m join public.profiles p on p.id=m.user_id where m.budget_id=p_budget)
  )
  from public.budgets b where b.id=p_budget and ledger_private.role_for(p_budget) is not null
$$;

create function public.ledger_invite(p_budget uuid, p_role text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare token text; invitation uuid;
begin
  if ledger_private.role_for(p_budget) is distinct from 'OWNER' or p_role is null or p_role not in ('EDITOR','VIEWER')
     or not exists (select 1 from public.budgets where id=p_budget and type='SHARED') then
    raise exception 'Shared budget owner required' using errcode='42501';
  end if;
  perform pg_advisory_xact_lock(hashtextextended(p_budget::text,63));
  if ledger_private.role_for(p_budget) is distinct from 'OWNER' then
    raise exception 'Shared budget owner required' using errcode='42501';
  end if;
  if (select count(*) from public.invitations where budget_id=p_budget and created_at>now()-interval '1 hour') >= 20 then
    raise exception 'Invitation creation limit reached' using errcode='22023';
  end if;
  token := encode(extensions.gen_random_bytes(32),'hex');
  insert into public.invitations (budget_id,token_hash,role,expires_at,created_by)
  values(p_budget,extensions.digest(token,'sha256'),p_role,now()+interval '24 hours',auth.uid()) returning id into invitation;
  return jsonb_build_object('id',invitation,'token',token);
end $$;

create function public.ledger_redeem(p_token text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare actor uuid:=auth.uid(); attempts integer; invite public.invitations; target uuid;
begin
  if actor is null then raise exception 'Sign-in required' using errcode='42501'; end if;
  insert into ledger_private.invite_attempts as a (user_id,window_start,attempts) values(actor,now(),1)
  on conflict(user_id) do update set
    window_start=case when a.window_start<now()-interval '15 minutes' then now() else a.window_start end,
    attempts=case when a.window_start<now()-interval '15 minutes' then 1 else least(a.attempts+1,1000) end
  returning a.attempts into attempts;
  if attempts > 12 then return jsonb_build_object('error','Too many attempts. Try again in 15 minutes.'); end if;
  if p_token is null or p_token !~ '^[0-9a-f]{64}$' then return jsonb_build_object('error','Invalid invitation code.'); end if;
  select i.budget_id into target from public.invitations i where i.token_hash=extensions.digest(p_token,'sha256');
  if target is null then return jsonb_build_object('error','The invitation is invalid or expired.'); end if;
  perform pg_advisory_xact_lock(hashtextextended(target::text,63));
  select i.* into invite from public.invitations i where i.token_hash=extensions.digest(p_token,'sha256') for update;
  if invite.revoked_at is not null or invite.expires_at<=now() or
    not exists (select 1 from public.budgets b where b.id=target and b.deleted_at is null and b.type='SHARED') then
    return jsonb_build_object('error','The invitation is invalid or expired.');
  end if;
  if ledger_private.role_for(target) is not null then return jsonb_build_object('budgetId',target); end if;
  if invite.use_count>=invite.max_uses then return jsonb_build_object('error','The invitation has already been used.'); end if;
  insert into public.budget_memberships as m (budget_id,user_id,role) values(target,actor,invite.role)
  on conflict (budget_id,user_id) do update set role=excluded.role,active=true,joined_at=now() where m.role<>'OWNER';
  update public.invitations set use_count=use_count+1 where id=invite.id;
  update public.budgets set sync_seq=sync_seq+1 where id=target;
  return jsonb_build_object('budgetId',target);
end $$;

create function public.ledger_invitations(p_budget uuid) returns jsonb
language sql stable security definer set search_path = '' as $$
  select coalesce(jsonb_agg(jsonb_build_object('id',id,'role',role,'expires',expires_at,
    'used',use_count,'revoked',revoked_at is not null) order by created_at desc), '[]'::jsonb)
  from public.invitations where budget_id=p_budget and ledger_private.role_for(p_budget)='OWNER'
$$;

create function public.ledger_revoke_invite(p_budget uuid, p_id uuid) returns boolean
language plpgsql security definer set search_path = '' as $$
begin
  perform pg_advisory_xact_lock(hashtextextended(p_budget::text,63));
  if ledger_private.role_for(p_budget) is distinct from 'OWNER' then raise exception 'Owner required' using errcode='42501'; end if;
  update public.invitations set revoked_at=now() where id=p_id and budget_id=p_budget and revoked_at is null;
  return found;
end $$;

create function public.ledger_member_access(p_budget uuid, p_user uuid, p_role text) returns boolean
language plpgsql security definer set search_path = '' as $$
begin
  perform pg_advisory_xact_lock(hashtextextended(p_budget::text,63));
  if ledger_private.role_for(p_budget) is distinct from 'OWNER' or p_role not in ('EDITOR','VIEWER','REVOKE') or p_role is null then
    raise exception 'Owner required' using errcode='42501';
  end if;
  update public.budget_memberships set
    active=p_role<>'REVOKE', role=case when p_role='REVOKE' then role else p_role end
  where budget_id=p_budget and user_id=p_user and role<>'OWNER' and active;
  if not found then return false; end if;
  update public.budgets set sync_seq=sync_seq+1 where id=p_budget;
  return true;
end $$;

revoke all on all functions in schema ledger_private from public, anon, authenticated;
grant execute on function ledger_private.role_for(uuid), ledger_private.shared_profile(uuid) to authenticated;
revoke all on function public.ledger_apply(text,uuid,uuid,bigint,uuid,jsonb), public.ledger_manifest(), public.ledger_snapshot(uuid),
 public.ledger_invite(uuid,text), public.ledger_redeem(text), public.ledger_invitations(uuid),
 public.ledger_revoke_invite(uuid,uuid), public.ledger_member_access(uuid,uuid,text) from public, anon, authenticated;
grant execute on function public.ledger_apply(text,uuid,uuid,bigint,uuid,jsonb), public.ledger_manifest(), public.ledger_snapshot(uuid),
 public.ledger_invite(uuid,text), public.ledger_redeem(text), public.ledger_invitations(uuid),
 public.ledger_revoke_invite(uuid,uuid), public.ledger_member_access(uuid,uuid,text) to authenticated;
