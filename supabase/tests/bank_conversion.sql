-- Confirmed bank amounts and private learning history across shared-budget clients.
begin;
insert into auth.users(id,email,email_confirmed_at,raw_user_meta_data) values
('16000000-0000-0000-0000-000000000001','bank-owner@example.test',now(),'{}'),
('16000000-0000-0000-0000-000000000002','bank-editor@example.test',now(),'{}'),
('16000000-0000-0000-0000-000000000003','bank-outsider@example.test',now(),'{}');

set local role authenticated;
do $$
declare
  owner_id constant uuid := '16000000-0000-0000-0000-000000000001';
  editor_id constant uuid := '16000000-0000-0000-0000-000000000002';
  outsider_id constant uuid := '16000000-0000-0000-0000-000000000003';
  budget constant uuid := '26000000-0000-0000-0000-000000000001';
  account_id constant uuid := '36000000-0000-0000-0000-000000000001';
  category_id constant uuid := '46000000-0000-0000-0000-000000000001';
  transaction_id constant uuid := '56000000-0000-0000-0000-000000000001';
  confirmed jsonb := '{"source":"BANK_ACTUAL","confirmedAt":1795219200000,"learningEligible":true,
    "reference":{"value":"0.0085","date":"2026-11-20","source":"Fixture"},"adjustment":"1.011764705882"}';
  actual jsonb := jsonb_build_object('type','EXPENSE','accountId',account_id,'categoryId',category_id,
    'amount','2500','currency','RSD','accountAmount','21.50','accountCurrency','USD','budgetAmount','2500','budgetCurrency','RSD',
    'accountRate','0.0086','accountRateDate','2026-11-21','accountRateSource','Bank actual','budgetRate','1',
    'budgetRateDate','2026-11-21','budgetRateSource','Same currency','date','2026-11-21','note','Groceries','bankConversion',confirmed);
  estimate jsonb := (actual - 'bankConversion') ||
    '{"accountAmount":"22","accountRate":"0.0088","accountRateSource":"Reference","note":"Editor note"}';
  body jsonb;
  result jsonb;
  invite text;
  first_operation uuid := gen_random_uuid();
  version bigint;
begin
  perform set_config('request.jwt.claim.sub',owner_id::text,true);
  perform public.ledger_apply('budget',budget,budget,0,gen_random_uuid(),
    '{"name":"Household","type":"SHARED","currency":"RSD","icon":"wallet","color":1}');
  perform public.ledger_apply('account',budget,account_id,0,gen_random_uuid(),
    '{"name":"USD debit","type":"DEBIT_CARD","currency":"USD","opening":"0","icon":"wallet","color":1,"order":0,"archived":false}');
  perform public.ledger_apply('category',budget,category_id,0,gen_random_uuid(),
    '{"name":"Groceries","type":"EXPENSE","icon":"basket","color":1,"order":0,"archived":false}');
  result := public.ledger_apply('entry',budget,transaction_id,0,first_operation,actual);
  if result->>'status' is distinct from 'ok' then raise exception 'Owner actual amount was rejected'; end if;
  version := (result->>'version')::bigint;
  if public.ledger_apply('entry',budget,transaction_id,0,first_operation,actual) is distinct from result then
    raise exception 'Retry created a different observation';
  end if;
  invite := public.ledger_invite(budget,'EDITOR')->>'token';

  perform set_config('request.jwt.claim.sub',editor_id::text,true);
  if public.ledger_redeem(invite)->>'code' is distinct from 'JOINED' then raise exception 'Editor could not join'; end if;
  select item->'body' into strict body from jsonb_array_elements(public.ledger_snapshot(budget)->'records') item
    where item->'body'->>'id'=transaction_id::text;
  if body->'bankConversion' is distinct from confirmed or (body->>'amount')::numeric is distinct from 2500::numeric or
     (body->>'accountAmount')::numeric is distinct from 21.50::numeric or (body->>'budgetAmount')::numeric is distinct from 2500::numeric then
    raise exception 'Editor snapshot did not preserve confirmed historical amounts';
  end if;
  result := public.ledger_apply('entry',budget,transaction_id,0,gen_random_uuid(),estimate);
  if result->>'status' is distinct from 'conflict' then raise exception 'A stale estimate overwrote a confirmed entry'; end if;
  result := public.ledger_apply('entry',budget,transaction_id,version,gen_random_uuid(),estimate);
  if result->>'status' is distinct from 'ok' or not exists (select 1 from public.transactions where id=transaction_id
      and note='Editor note' and account_amount=21.50 and account_rate=0.0086 and bank_conversion=confirmed) then
    raise exception 'A current legacy note edit lost bank confirmation';
  end if;
  result := public.ledger_apply('entry',budget,transaction_id,(result->>'version')::bigint,gen_random_uuid(),actual ||
    jsonb_build_object('accountAmount','21.75','accountRate','0.0087','bankConversion',confirmed || '{"confirmedAt":1795219201000}'));
  if result->>'status' is distinct from 'ok' then raise exception 'Explicit bank correction was rejected'; end if;

  perform set_config('request.jwt.claim.sub',owner_id::text,true);
  if (select count(*) from public.transactions where budget_id=budget and bank_conversion->>'source'='BANK_ACTUAL') <> 1 or
    (select sum(account_amount) from public.transactions where budget_id=budget) is distinct from 21.75::numeric then
    raise exception 'The shared account contains duplicate or divergent confirmed amounts';
  end if;
  perform set_config('request.jwt.claim.sub',outsider_id::text,true);
  if exists (select 1 from public.transactions where id=transaction_id) then
    raise exception 'Unrelated account could read private bank conversion details';
  end if;
end $$;
rollback;
