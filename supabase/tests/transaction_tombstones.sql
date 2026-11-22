-- Run after all migrations. The private writer reproduces the pre-fix upsert.
begin;
insert into auth.users(id,email,email_confirmed_at,raw_user_meta_data) values
('17000000-0000-0000-0000-000000000001','delete-owner@example.test',now(),'{}'),
('17000000-0000-0000-0000-000000000002','delete-editor@example.test',now(),'{}'),
('17000000-0000-0000-0000-000000000003','delete-viewer@example.test',now(),'{}');

do $$
declare
  owner_id constant uuid := '17000000-0000-0000-0000-000000000001';
  editor_id constant uuid := '17000000-0000-0000-0000-000000000002';
  viewer_id constant uuid := '17000000-0000-0000-0000-000000000003';
  budget constant uuid := '27000000-0000-0000-0000-000000000001';
  debit constant uuid := '37000000-0000-0000-0000-000000000001';
  cash constant uuid := '37000000-0000-0000-0000-000000000002';
  groceries constant uuid := '47000000-0000-0000-0000-000000000001';
  salary constant uuid := '47000000-0000-0000-0000-000000000002';
  rule_id constant uuid := '57000000-0000-0000-0000-000000000001';
  paid constant uuid := '67000000-0000-0000-0000-000000000001';
  day constant text := '2026-11-20';
  wallet jsonb := '{"name":"Test account","type":"CASH","opening":"0","icon":"wallet","color":1,"order":0,"archived":false}';
  category jsonb := '{"name":"Test category","icon":"wallet","color":1,"order":0,"archived":false}';
  actual jsonb := jsonb_build_object('type','EXPENSE','accountId',debit,'categoryId',groceries,'amount','2500',
    'currency','RSD','accountAmount','21.50','accountCurrency','USD','accountRate','0.0086','accountRateDate',day,
    'accountRateSource','Bank actual','budgetAmount','2500','budgetCurrency','RSD','budgetRate','1','budgetRateDate',day,
    'budgetRateSource','Same currency','date',day,'note','Fixture',
    'bankConversion','{"source":"BANK_ACTUAL","confirmedAt":1795219200000}'::jsonb);
  domestic jsonb := (actual - 'bankConversion') || jsonb_build_object('accountId',cash,'accountCurrency','RSD',
    'accountAmount','2500','accountRate','1','accountRateSource','Same currency');
  broken jsonb := (actual - 'accountRateDate') || '{"deletedAt":1795220000000}';
  partial_constraint text;
  editor_code text;
  viewer_code text;
  response jsonb;
  version bigint;
  before_seq bigint;
  tombstone jsonb;
  fixture jsonb;
  row_id uuid;
  operation uuid;
begin
  perform set_config('request.jwt.claim.sub',owner_id::text,true);
  perform public.ledger_apply('budget',budget,budget,0,gen_random_uuid(),
    '{"name":"Household","type":"SHARED","currency":"RSD","icon":"wallet","color":1}');
  perform public.ledger_apply('account',budget,debit,0,gen_random_uuid(),wallet || '{"currency":"USD"}');
  perform public.ledger_apply('account',budget,cash,0,gen_random_uuid(),wallet || '{"currency":"RSD"}');
  perform public.ledger_apply('category',budget,groceries,0,gen_random_uuid(),category || '{"type":"EXPENSE"}');
  perform public.ledger_apply('category',budget,salary,0,gen_random_uuid(),category || '{"type":"INCOME"}');
  perform public.ledger_apply('rule',budget,rule_id,0,gen_random_uuid(),jsonb_build_object(
    'type','EXPENSE','accountId',cash,'categoryId',groceries,'amount','2500','currency','RSD','frequency','MONTHLY',
    'interval',1,'start',day,'nextIndex',1,'automatic',true,'active',true,'remind',false,'note','Fixture'));
  response := public.ledger_apply('entry',budget,paid,0,gen_random_uuid(),actual)->'body';
  if response->>'accountRateDate' is distinct from day or response->>'accountRateSource' is distinct from 'Bank actual'
    or response->>'budgetRateDate' is distinct from day or response->>'budgetRateSource' is distinct from 'Same currency' then
    raise exception 'The Android payload lost rate dates or sources';
  end if;
  begin
    perform ledger_private.apply_record('entry',budget,paid,1,gen_random_uuid(),broken);
    raise exception 'The pre-fix partial tombstone was accepted';
  exception when check_violation then
    get stacked diagnostics partial_constraint = CONSTRAINT_NAME;
    if partial_constraint <> 'transactions_check2' then raise; end if;
  end;
  begin
    perform public.ledger_apply('entry',budget,paid,1,gen_random_uuid(),broken - 'deletedAt');
    raise exception 'The CHECK was weakened for active writes';
  exception when check_violation then
    get stacked diagnostics partial_constraint = CONSTRAINT_NAME;
    if partial_constraint <> 'transactions_check2' then raise; end if;
  end;
  editor_code := public.ledger_invite(budget,'EDITOR')->>'token';
  viewer_code := public.ledger_invite(budget,'VIEWER')->>'token';

  set local role authenticated;
  perform set_config('request.jwt.claim.sub',editor_id::text,true);
  if public.ledger_redeem(editor_code)->>'code' is distinct from 'JOINED' then raise exception 'Cannot join as editor'; end if;
  response := public.ledger_apply('entry',budget,paid,1,gen_random_uuid(),actual || '{"note":"Editor changed the note"}');
  if response->>'status' is distinct from 'ok' then raise exception 'Concurrent edit failed'; end if;
  select sync_seq into before_seq from public.budgets where id=budget;
  perform set_config('request.jwt.claim.sub',owner_id::text,true);
  operation := gen_random_uuid();
  response := public.ledger_apply('entry',budget,paid,1,operation,broken);
  tombstone := response->'body';
  version := (response->>'version')::bigint;
  if response->>'status' is distinct from 'ok' or version <> 3 or tombstone->>'deletedAt' is null or
    tombstone->>'note' is distinct from 'Editor changed the note' or (tombstone->>'accountAmount')::numeric <> 21.50 or
    tombstone->'bankConversion' is distinct from actual->'bankConversion' then
    raise exception 'Deletion did not preserve the canonical server row';
  end if;
  if public.ledger_apply('entry',budget,paid,1,operation,'{"deletedAt":1795220000000}') is distinct from response or
    public.ledger_apply('entry',budget,paid,3,gen_random_uuid(),'{"deletedAt":1795220000001}') is distinct from response or
    (select sync_seq from public.budgets where id=budget) <> before_seq+1 then
    raise exception 'A duplicate delete changed the row or budget revision';
  end if;
  perform set_config('request.jwt.claim.sub',editor_id::text,true);
  if public.ledger_apply('entry',budget,paid,version,gen_random_uuid(),actual)->>'status' is distinct from 'conflict' or
    not exists (select 1 from jsonb_array_elements(public.ledger_snapshot(budget)->'records') item
      where item->'body'=tombstone) then
    raise exception 'A stale editor resurrected the transaction or could not see its tombstone';
  end if;

  for fixture in select value from jsonb_array_elements(jsonb_build_array(
    domestic,
    domestic || jsonb_build_object('categoryId',salary,'type','INCOME'),
    (actual - 'bankConversion') || '{"accountRateSource":"Last bank rate","bankConversion":{"source":"BANK_STALE"}}',
    domestic || jsonb_build_object('categoryId',null,'type','TRANSFER','destinationId',debit,'destinationCurrency','USD',
      'destinationAmount','21.50','transferRate','0.0086','budgetAmount',null,'budgetRate',null,'budgetRateDate',null,'budgetRateSource',null),
    domestic || jsonb_build_object('ruleId',rule_id),
    domestic || '{"note":"Imported entry"}'
  )) loop
    row_id := gen_random_uuid();
    if fixture->>'note' = 'Imported entry' then
      response := public.ledger_import_batch(budget,jsonb_build_array(jsonb_build_object(
        'kind','entry','id',row_id,'operation',gen_random_uuid(),'body',fixture)))->0;
    else
      response := public.ledger_apply('entry',budget,row_id,0,gen_random_uuid(),fixture);
    end if;
    if response->>'status' is distinct from 'ok' then raise exception 'Fixture creation failed'; end if;
    response := public.ledger_apply('entry',budget,row_id,1,gen_random_uuid(),'{"deletedAt":1795220000000}');
    if response->>'status' is distinct from 'ok' or response->'body'->>'deletedAt' is null or
      response->'body'->>'destinationId' is distinct from fixture->>'destinationId' then
      raise exception 'Deletion changed transfer or entry relationships';
    end if;
  end loop;

  row_id := gen_random_uuid();
  response := public.ledger_apply('entry',budget,row_id,0,gen_random_uuid(),'{"deletedAt":1795220000000}');
  if response->>'status' is distinct from 'ok' or exists (select 1 from public.transactions where id=row_id) then
    raise exception 'Deleting a never-published ordinary entry inserted a placeholder';
  end if;
  row_id := gen_random_uuid();
  response := public.ledger_apply('entry',budget,row_id,0,gen_random_uuid(),domestic ||
    jsonb_build_object('ruleId',rule_id,'date','2026-12-20','deletedAt',1795220000000));
  if response->>'status' is distinct from 'ok' or not exists (select 1 from public.recurring_rules
    where id=rule_id and active and deleted_at is null) then raise exception 'Unsent occurrence removed its recurring rule'; end if;
  if exists (select 1 from public.transactions where budget_id=budget and deleted_at is null) then
    raise exception 'A deleted expense, income, transfer, import or occurrence remains active';
  end if;

  perform set_config('request.jwt.claim.sub',viewer_id::text,true);
  if public.ledger_redeem(viewer_code)->>'code' is distinct from 'JOINED' then raise exception 'Cannot join as viewer'; end if;
  begin
    perform public.ledger_apply('entry',budget,paid,3,gen_random_uuid(),'{"deletedAt":1795220000000}');
    raise exception 'A viewer could delete';
  exception when insufficient_privilege then null;
  end;
end $$;
rollback;
