-- Split the identifier at every underscore. initcap on the whole suffix can
-- produce accountRatedate under the database collation, which clients ignore.
create or replace function ledger_private.to_client(p_body jsonb) returns jsonb
language sql immutable set search_path = '' as $$
  select coalesce(jsonb_object_agg(
    (select string_agg(case when position=1 then word else upper(left(word,1) collate pg_catalog."C") || substr(word,2) end,
      '' order by position) from unnest(string_to_array(key,'_')) with ordinality as parts(word,position)),
    case when jsonb_typeof(value) = 'number' and key = any(array[
      'opening','amount','account_amount','budget_amount','account_rate','budget_rate','destination_amount','transfer_rate'
    ]) then to_jsonb(value #>> '{}') else value end
  ), '{}'::jsonb)
  from jsonb_each(p_body - array['version','last_operation','sync_seq'])
$$;

-- Keep the existing checks and writer for active records. A synchronized deletion
-- changes only server-owned deletion and sync metadata on the existing transaction.
alter function public.ledger_apply(text,uuid,uuid,bigint,uuid,jsonb) set schema ledger_private;
alter function ledger_private.ledger_apply(text,uuid,uuid,bigint,uuid,jsonb) rename to apply_record;
revoke all on function ledger_private.apply_record(text,uuid,uuid,bigint,uuid,jsonb) from public, anon, authenticated;

create function public.ledger_apply(p_kind text, p_budget uuid, p_id uuid, p_base bigint, p_operation uuid, p_body jsonb)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  access_role text;
  current_entry public.transactions%rowtype;
  deleting boolean;
  local_now bigint := (extract(epoch from now()) * 1000)::bigint;
begin
  if p_kind is distinct from 'entry' then
    return ledger_private.apply_record(p_kind,p_budget,p_id,p_base,p_operation,p_body);
  end if;
  if auth.uid() is null or p_operation is null or p_id is null or p_budget is null or p_base < 0 or
     p_base is null or jsonb_typeof(p_body) is distinct from 'object' or pg_column_size(p_body) > 16000 then
    raise exception 'Invalid ledger operation' using errcode='22023';
  end if;
  perform pg_advisory_xact_lock(hashtextextended(p_budget::text, 63));
  access_role := ledger_private.role_for(p_budget);
  if access_role is null or access_role = 'VIEWER' then
    raise exception 'Writable budget access required' using errcode='42501';
  end if;

  deleting := p_body->>'deletedAt' is not null;
  if deleting and p_body->>'deletedAt' !~ '^[1-9][0-9]{0,17}$' then
    raise exception 'Invalid transaction deletion timestamp' using errcode='22023';
  end if;
  select * into current_entry from public.transactions where budget_id=p_budget and id=p_id;
  if not found then
    if deleting and p_base=0 and p_body->>'ruleId' is null then
      -- A local-only ordinary entry has never affected other devices.
      return jsonb_build_object('status','ok','version',0,'body',null);
    end if;
    -- Retain a valid tombstone for an unsent recurring occurrence so the same
    -- deterministic entry cannot be generated on another device.
    return ledger_private.apply_record(p_kind,p_budget,p_id,p_base,p_operation,p_body);
  end if;

  if current_entry.deleted_at is not null or p_base > current_entry.version then
    return jsonb_build_object('status',case when deleting and current_entry.deleted_at is not null then 'ok' else 'conflict' end,
      'version',current_entry.version,'body',ledger_private.to_client(to_jsonb(current_entry)));
  end if;
  if not deleting then
    return ledger_private.apply_record(p_kind,p_budget,p_id,p_base,p_operation,p_body);
  end if;

  -- Deletion wins over an earlier edit to the same ID, while preserving the latest
  -- server values and all relationships. Repeated deletes return the same row.
  update public.transactions set deleted_at=local_now, updated_at=local_now,
    version=version+1, last_operation=p_operation
  where budget_id=p_budget and id=p_id returning * into current_entry;
  update public.budgets set sync_seq=sync_seq+1 where id=p_budget;
  return jsonb_build_object('status','ok','version',current_entry.version,'body',ledger_private.to_client(to_jsonb(current_entry)));
end $$;

revoke all on function public.ledger_apply(text,uuid,uuid,bigint,uuid,jsonb) from public, anon, authenticated;
grant execute on function public.ledger_apply(text,uuid,uuid,bigint,uuid,jsonb) to authenticated;
