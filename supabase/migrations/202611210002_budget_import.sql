-- An import can create ledger records. It cannot edit budget metadata or membership.
create function ledger_private.import_dimension(p_kind text, p_budget uuid, p_id uuid, p_operation uuid, p_body jsonb)
returns jsonb language plpgsql set search_path = '' as $$
declare
  body jsonb;
  stored jsonb;
  now_ms bigint := (extract(epoch from now()) * 1000)::bigint;
begin
  body := ledger_private.to_columns(p_body) || jsonb_build_object(
    'id',p_id,'budget_id',p_budget,'version',1,'last_operation',p_operation,
    'created_at',now_ms,'updated_at',now_ms);
  if p_kind='account' then
    insert into public.accounts as a select (jsonb_populate_record(null::public.accounts,body)).*
    on conflict (id) do nothing returning to_jsonb(a) into stored;
    if stored is null then
      select to_jsonb(a) into stored from public.accounts a where a.id=p_id and a.budget_id=p_budget;
    else
      update public.budgets set sync_seq=sync_seq+1 where id=p_budget;
    end if;
  elsif p_kind='category' then
    insert into public.categories as c select (jsonb_populate_record(null::public.categories,body)).*
    on conflict (id) do nothing returning to_jsonb(c) into stored;
    if stored is null then
      select to_jsonb(c) into stored from public.categories c where c.id=p_id and c.budget_id=p_budget;
    else
      update public.budgets set sync_seq=sync_seq+1 where id=p_budget;
    end if;
  end if;
  if stored is null then
    raise exception 'Import identifier belongs to another budget' using errcode='42501';
  end if;
  return jsonb_build_object('status',case when stored->>'last_operation'=p_operation::text then 'ok' else 'conflict' end,
    'version',stored->'version','body',ledger_private.to_client(stored));
end $$;

create function public.ledger_import_batch(p_budget uuid, p_changes jsonb) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  actor uuid := auth.uid();
  change jsonb;
  kind text;
  record_id uuid;
  operation_id uuid;
  body jsonb;
  result jsonb;
  results jsonb := '[]'::jsonb;
begin
  if actor is null then raise exception 'Sign in to import' using errcode='42501'; end if;
  if p_budget is null or jsonb_typeof(p_changes) is distinct from 'array' then
    raise exception 'Invalid import batch' using errcode='22023';
  end if;
  if jsonb_array_length(p_changes) not between 1 and 50 or pg_column_size(p_changes)>800000 then
    raise exception 'Invalid import batch size' using errcode='22023';
  end if;
  perform pg_advisory_xact_lock(hashtextextended(p_budget::text,63));
  if coalesce(ledger_private.role_for(p_budget),'') not in ('OWNER','EDITOR') then
    raise exception 'Only owners and editors can import into this budget' using errcode='42501';
  end if;

  for change in select value from jsonb_array_elements(p_changes) loop
    kind := change->>'kind';
    record_id := (change->>'id')::uuid;
    operation_id := (change->>'operation')::uuid;
    body := change->'body';
    if kind is null or kind not in ('account','category','rule','entry') or record_id is null or operation_id is null or
       jsonb_typeof(body) is distinct from 'object' or pg_column_size(body)>16000 then
      raise exception 'Invalid import record' using errcode='22023';
    end if;
    if kind in ('account','category') then
      result := ledger_private.import_dimension(kind,p_budget,record_id,operation_id,body);
    else
      -- The normal writer applies relationship checks and sets the creator to the signed-in member.
      result := public.ledger_apply(kind,p_budget,record_id,0,operation_id,body - array['creator','payer']);
    end if;
    results := results || jsonb_build_array(result);
    -- A conflicting dependency needs review before subsequent records are uploaded.
    exit when result->>'status'='conflict';
  end loop;
  return results;
end $$;

revoke all on function ledger_private.import_dimension(text,uuid,uuid,uuid,jsonb) from public, anon, authenticated;
revoke all on function public.ledger_import_batch(uuid,jsonb) from public, anon, authenticated;
grant execute on function public.ledger_import_batch(uuid,jsonb) to authenticated;
