-- Disposable shared-budget fixture. Check both snapshots and writes with the existing RPC.
begin;
insert into auth.users(id,email,email_confirmed_at,raw_user_meta_data) values
('18000000-0000-0000-0000-000000000001','valuation-owner@example.test',now(),'{}'),
('18000000-0000-0000-0000-000000000002','valuation-editor@example.test',now(),'{}');

set local role authenticated;
do $$
declare
  owner_id constant uuid := '18000000-0000-0000-0000-000000000001';
  editor_id constant uuid := '18000000-0000-0000-0000-000000000002';
  budget constant uuid := '28000000-0000-0000-0000-000000000001';
  account_id constant uuid := '38000000-0000-0000-0000-000000000001';
  category_id constant uuid := '48000000-0000-0000-0000-000000000001';
  transaction_id constant uuid := '68000000-0000-0000-0000-000000000001';
  original jsonb := jsonb_build_object('type','INCOME','accountId',account_id,'categoryId',category_id,'amount','100',
    'currency','USD','accountCurrency','USD','accountAmount','100','accountRate','1','accountRateDate','2026-11-22',
    'accountRateSource','Same currency','budgetCurrency','RSD','budgetAmount','11400','budgetRate','114',
    'budgetRateDate','2026-11-22','budgetRateSource','User-defined','budgetRateConfirmedAt',1795305600000,
    'date','2026-11-22','note','Fixture');
  response jsonb;
  owner_snapshot jsonb;
  editor_snapshot jsonb;
  invite text;
  version bigint;
begin
  perform set_config('request.jwt.claim.sub',owner_id::text,true);
  perform public.ledger_apply('budget',budget,budget,0,gen_random_uuid(),
    '{"name":"Household","type":"SHARED","currency":"RSD","icon":"wallet","color":1}');
  perform public.ledger_apply('account',budget,account_id,0,gen_random_uuid(),
    '{"name":"USD debit","type":"DEBIT_CARD","currency":"USD","opening":"0","icon":"wallet","color":1,"order":0,"archived":false}');
  perform public.ledger_apply('category',budget,category_id,0,gen_random_uuid(),
    '{"name":"Salary","type":"INCOME","icon":"wallet","color":1,"order":0,"archived":false}');
  invite := public.ledger_invite(budget,'EDITOR')->>'token';
  perform set_config('request.jwt.claim.sub',editor_id::text,true);
  if public.ledger_redeem(invite)->>'code' is distinct from 'JOINED' then raise exception 'Editor join failed'; end if;
  response := public.ledger_apply('entry',budget,transaction_id,0,gen_random_uuid(),original);
  if response->>'status' is distinct from 'ok' or response->'body'->>'budgetRateConfirmedAt' is distinct from '1795305600000'
    or response->'body'->>'budgetRateSource' is distinct from 'User-defined' then raise exception 'Confirmation lost'; end if;
  version := (response->>'version')::bigint;
  editor_snapshot := public.ledger_snapshot(budget);

  perform set_config('request.jwt.claim.sub',owner_id::text,true);
  owner_snapshot := public.ledger_snapshot(budget);
  if owner_snapshot is distinct from editor_snapshot or not exists (
    select 1 from public.transactions where id=transaction_id and account_amount=100 and account_rate=1
      and budget_amount=11400 and budget_rate=114 and budget_rate_date='2026-11-22'
  ) then raise exception 'Members received divergent account or budget amounts'; end if;
  response := public.ledger_apply('entry',budget,transaction_id,version,gen_random_uuid(),
    (original - 'budgetRateConfirmedAt') || '{"note":"Legacy client note edit"}');
  if response->'body'->>'budgetRateConfirmedAt' is distinct from '1795305600000' then raise exception 'Legacy edit discarded confirmation'; end if;
  version := (response->>'version')::bigint;

  begin
    perform public.ledger_apply('entry',budget,transaction_id,version,gen_random_uuid(),
      original || '{"budgetRateSource":"Reference"}');
    raise exception 'An unconfirmed reference valuation was marked as user-defined';
  exception when check_violation then null;
  end;

  response := public.ledger_apply('entry',budget,transaction_id,version,gen_random_uuid(),
    (original - 'budgetRateConfirmedAt') || '{"budgetRateSource":"Frankfurter","budgetRate":"117","budgetAmount":"11700"}');
  if response->'body'->>'budgetRateConfirmedAt' is not null or response->'body'->>'budgetRateSource' is distinct from 'Frankfurter'
    or (response->'body'->>'accountAmount')::numeric <> 100 then raise exception 'Reference restoration altered account or kept manual confirmation'; end if;
end $$;
rollback;
