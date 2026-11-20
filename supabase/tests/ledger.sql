begin;
insert into auth.users(id,raw_user_meta_data) values
('10000000-0000-0000-0000-000000000001','{"display_name": "Owner"}'),
('10000000-0000-0000-0000-000000000002','{"display_name": "Editor"}'),
('10000000-0000-0000-0000-000000000003','{"display_name": "Viewer"}');

set local role authenticated;
do $$
declare
  owner_id constant uuid := '10000000-0000-0000-0000-000000000001';
  editor_id constant uuid := '10000000-0000-0000-0000-000000000002';
  viewer_id constant uuid := '10000000-0000-0000-0000-000000000003';
  budget constant uuid := '20000000-0000-0000-0000-000000000001';
  personal constant uuid := '20000000-0000-0000-0000-000000000002';
  acct constant uuid := '30000000-0000-0000-0000-000000000001';
  cat constant uuid := '40000000-0000-0000-0000-000000000001';
  tx constant uuid := '50000000-0000-0000-0000-000000000001';
  occurrence_tx constant uuid := '50000000-0000-0000-0000-000000000002';
  recurring_rule constant uuid := '60000000-0000-0000-0000-000000000001';
  original_op uuid := gen_random_uuid();
  editor_code text;
  viewer_code text;
  result jsonb;
  txbody jsonb := jsonb_build_object('type','EXPENSE','accountId',acct,'categoryId',cat,'amount','20','currency','EUR',
    'accountCurrency','RSD','budgetCurrency','RSD','date','2025-01-15','accountAmount','2343.40','budgetAmount','2343.40',
    'accountRate','117.17','budgetRate','117.17','accountRateDate','2025-01-15','budgetRateDate','2025-01-15',
    'accountRateSource','Frankfurter','budgetRateSource','Frankfurter','note','Groceries','payer',editor_id);
begin
  perform set_config('request.jwt.claim.sub', owner_id::text, true);
  result := public.ledger_apply('budget',budget,budget,0,gen_random_uuid(),'{"name":"Household","currency":"RSD","type":"SHARED","icon":"wallet","color":1}');
  if result->>'status' is distinct from 'ok' then raise exception 'Budget creation failed: %',result; end if;
  perform public.ledger_apply('budget',personal,personal,0,gen_random_uuid(),'{"name":"Private","currency":"RSD","type":"PERSONAL","icon":"wallet","color":1}');
  perform public.ledger_apply('account',budget,acct,0,gen_random_uuid(),'{"name":"Cash","currency":"RSD","type":"CASH","icon":"wallet","color":1,"opening":"0","order":0,"archived":false}');
  perform public.ledger_apply('category',budget,cat,0,gen_random_uuid(),'{"name":"Groceries","type":"EXPENSE","icon":"basket","color":1,"order":0,"archived":false}');
  editor_code := public.ledger_invite(budget,'EDITOR')->>'token';
  viewer_code := public.ledger_invite(budget,'VIEWER')->>'token';

  perform set_config('request.jwt.claim.sub', editor_id::text,true);
  if (select count(*) from public.budgets) <> 0 then raise exception 'Private budget was visible'; end if;
  if public.ledger_redeem(editor_code)->>'budgetId' is distinct from budget::text then raise exception 'Invitation failed'; end if;
  if (select count(*) from public.budgets) <> 1 then raise exception 'Unexpected RLS result'; end if;
  result := public.ledger_apply('entry',budget,tx,0,original_op,txbody);
  if result->>'status' is distinct from 'ok' or result->'body'->>'creator' is distinct from editor_id::text then raise exception 'Entry failed: %',result; end if;
  result := public.ledger_apply('entry',budget,tx,0,original_op,txbody);
  if result->>'version' is distinct from '1' then raise exception 'Retry was not idempotent: %',result; end if;
  result := public.ledger_apply('entry',budget,tx,0,gen_random_uuid(),txbody);
  if result->>'status' is distinct from 'conflict' then raise exception 'Stale update was accepted: %', result; end if;
  begin
    perform public.ledger_apply('category',budget,cat,1,gen_random_uuid(),'{"name":"Changed"}');
    raise exception 'Editor was allowed to edit a category';
  exception when insufficient_privilege then null;
  end;

  result := public.ledger_apply('rule',budget,recurring_rule,0,gen_random_uuid(),
    jsonb_build_object('type','EXPENSE','accountId',acct,'categoryId',cat,'amount','20','currency','EUR',
      'frequency','MONTHLY','interval',1,'start','2025-01-15','nextIndex',1,'automatic',true,'active',true,'remind',false,'note',''));
  if result->>'status' is distinct from 'ok' then raise exception 'Recurring rule failed'; end if;
  perform public.ledger_apply('entry',budget,occurrence_tx,0,gen_random_uuid(),txbody || jsonb_build_object('ruleId',recurring_rule));
  begin
    perform public.ledger_apply('entry',budget,gen_random_uuid(),0,gen_random_uuid(),txbody || jsonb_build_object('ruleId',recurring_rule));
    raise exception 'A recurring occurrence was duplicated';
  exception when unique_violation then null;
  end;

  perform set_config('request.jwt.claim.sub', viewer_id::text,true);
  if public.ledger_redeem(viewer_code)->>'budgetId' is distinct from budget::text then raise exception 'Viewer invitation failed'; end if;
  if not exists (select 1 from jsonb_array_elements(public.ledger_snapshot(budget)->'records') r
    where r->>'kind'='entry' and r->'body'->>'accountId'=acct::text) then raise exception 'Snapshot lost relations'; end if;
  begin
    perform public.ledger_apply('entry',budget,tx,1,gen_random_uuid(),txbody);
    raise exception 'Viewer was able to edit a transaction';
  exception when insufficient_privilege then null;
  end;

  perform set_config('request.jwt.claim.sub', owner_id::text,true);
  if not public.ledger_member_access(budget,editor_id,'REVOKE') then raise exception 'Revocation failed'; end if;
  perform set_config('request.jwt.claim.sub', editor_id::text,true);
  if (select count(*) from public.transactions) <> 0 or public.ledger_snapshot(budget) is not null then raise exception 'Revoked data was visible'; end if;
end $$;

reset role;
delete from auth.users where id='10000000-0000-0000-0000-000000000001';
do $$
begin
  if exists (select from public.budgets where id='20000000-0000-0000-0000-000000000001')
     or exists (select from public.transactions where budget_id='20000000-0000-0000-0000-000000000001') then
    raise exception 'Removing an account left owned data accessible';
  end if;
end $$;
rollback;
