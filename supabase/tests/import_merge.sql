-- Run against a disposable stack with all migrations applied.
begin;
insert into auth.users(id,email,email_confirmed_at,raw_user_meta_data) values
('15000000-0000-0000-0000-000000000001','import-owner@example.test',now(),'{}'),
('15000000-0000-0000-0000-000000000002','import-editor@example.test',now(),'{}'),
('15000000-0000-0000-0000-000000000003','import-viewer@example.test',now(),'{}');

set local role authenticated;
do $$
declare
  owner_id constant uuid := '15000000-0000-0000-0000-000000000001';
  editor_id constant uuid := '15000000-0000-0000-0000-000000000002';
  viewer_id constant uuid := '15000000-0000-0000-0000-000000000003';
  budget constant uuid := '25000000-0000-0000-0000-000000000001';
  account_id constant uuid := '35000000-0000-0000-0000-000000000001';
  category_id constant uuid := '45000000-0000-0000-0000-000000000001';
  extra_category constant uuid := '45000000-0000-0000-0000-000000000002';
  transaction_id constant uuid := '55000000-0000-0000-0000-000000000001';
  editor_code text;
  viewer_code text;
  result jsonb;
  create_account jsonb := jsonb_build_object('kind','account','id',account_id,'operation',gen_random_uuid(),'body',
    '{"name":"Card","type":"BANK","currency":"EUR","opening":"10","icon":"wallet","color":1,"order":0,"archived":false}'::jsonb);
  create_category jsonb := jsonb_build_object('kind','category','id',category_id,'operation',gen_random_uuid(),'body',
    '{"name":"Groceries","type":"EXPENSE","icon":"basket","color":1,"order":0,"archived":false}'::jsonb);
  create_entry jsonb := jsonb_build_object('kind','entry','id',transaction_id,'operation',gen_random_uuid(),'body',
    jsonb_build_object('type','EXPENSE','accountId',account_id,'categoryId',category_id,'currency','EUR','accountCurrency','EUR',
      'budgetCurrency','RSD','date','2026-01-02','amount','20.05','accountAmount','20.05','budgetAmount','2349.86',
      'accountRate','1','budgetRate','117.2','accountRateDate','2026-01-02','budgetRateDate','2026-01-02',
      'accountRateSource','Same currency','budgetRateSource','Historical archive','note','Preserved',
      'creator',viewer_id,'payer',viewer_id));
  batch jsonb := jsonb_build_array(create_account,create_category,create_entry);
begin
  perform set_config('request.jwt.claim.sub',owner_id::text,true);
  perform public.ledger_apply('budget',budget,budget,0,gen_random_uuid(),
    '{"name":"Household","type":"SHARED","currency":"RSD","icon":"wallet","color":1}');
  result := public.ledger_import_batch(budget,jsonb_build_array(create_category ||
    jsonb_build_object('id',extra_category,'body',(create_category->'body') || '{"name":"Existing default"}')));
  if result->0->>'status' is distinct from 'ok' then raise exception 'Owner import failed'; end if;
  editor_code := public.ledger_invite(budget,'EDITOR')->>'token';
  viewer_code := public.ledger_invite(budget,'VIEWER')->>'token';

  perform set_config('request.jwt.claim.sub',editor_id::text,true);
  if public.ledger_redeem(editor_code)->>'code' is distinct from 'JOINED' then raise exception 'Editor could not join'; end if;
  begin
    perform public.ledger_apply('account',budget,account_id,0,gen_random_uuid(),create_account->'body');
    raise exception 'Normal editor account write was allowed';
  exception when insufficient_privilege then null;
  end;
  result := public.ledger_import_batch(budget,batch);
  if jsonb_array_length(result)<>3 or exists (select 1 from jsonb_array_elements(result) item where item->>'status'<>'ok') then
    raise exception 'Editor import failed';
  end if;
  if public.ledger_import_batch(budget,batch) is distinct from result then
    raise exception 'Retry altered previously imported records';
  end if;
  result := public.ledger_import_batch(budget,jsonb_build_array(create_account ||
    jsonb_build_object('operation',gen_random_uuid(),'body',(create_account->'body') || '{"opening":"999"}')));
  if result->0->>'status' is distinct from 'conflict' then raise exception 'Import overwrote an existing account'; end if;
  if (select opening from public.accounts where id=account_id) is distinct from 10::numeric then
    raise exception 'Opening balance changed';
  end if;
  if exists (select 1 from public.transactions where id=transaction_id and
      (creator<>editor_id or payer is not null or amount<>20.05 or budget_amount<>2349.86 or budget_rate<>117.2)) then
    raise exception 'Import lost transaction data or trusted imported identities';
  end if;

  begin
    perform public.ledger_import_batch(budget,jsonb_build_array(
      create_category || jsonb_build_object('id','45000000-0000-0000-0000-000000000003'),
      create_entry || jsonb_build_object('id',gen_random_uuid(),'body',(create_entry->'body') ||
        '{"accountId":"35000000-0000-0000-0000-000000000009"}')));
    raise exception 'Broken relationship was accepted';
  exception when invalid_parameter_value then null;
  end;
  if exists (select 1 from public.categories where id='45000000-0000-0000-0000-000000000003') then
    raise exception 'A failed batch left partial records';
  end if;

  perform set_config('request.jwt.claim.sub',viewer_id::text,true);
  if public.ledger_redeem(viewer_code)->>'code' is distinct from 'JOINED' then raise exception 'Viewer could not join'; end if;
  begin
    perform public.ledger_import_batch(budget,batch);
    raise exception 'Viewer was allowed to import';
  exception when insufficient_privilege then null;
  end;
  result := public.ledger_snapshot(budget);
  if jsonb_array_length(result->'members')<>3 or
     not exists (select 1 from jsonb_array_elements(result->'records') row where row->'body'->>'id'=transaction_id::text) or
     not exists (select 1 from jsonb_array_elements(result->'records') row where row->'body'->>'id'=extra_category::text) then
    raise exception 'The shared snapshot lost imported transactions, existing categories or membership';
  end if;
end $$;
reset role;
do $$
begin
  if has_function_privilege('anon','public.ledger_import_batch(uuid,jsonb)','execute') or
    has_function_privilege('authenticated','ledger_private.import_dimension(text,uuid,uuid,uuid,jsonb)','execute') then
    raise exception 'Import RPC grants are overly broad';
  end if;
end $$;
rollback;
