-- Run after all migrations on a disposable project. No FCM requests are sent.
begin;
insert into auth.users(id,email,email_confirmed_at,raw_user_meta_data) values
('16000000-0000-0000-0000-000000000001','push-owner@example.test',now(),'{}'),
('16000000-0000-0000-0000-000000000002','push-editor@example.test',now(),'{}'),
('16000000-0000-0000-0000-000000000003','push-viewer@example.test',now(),'{}'),
('16000000-0000-0000-0000-000000000004','push-outsider@example.test',now(),'{}');

do $$
declare
  actor_id constant uuid := '16000000-0000-0000-0000-000000000001';
  recipient constant uuid := '16000000-0000-0000-0000-000000000002';
  removed constant uuid := '16000000-0000-0000-0000-000000000003';
  outsider constant uuid := '16000000-0000-0000-0000-000000000004';
  shared constant uuid := gen_random_uuid();
  personal constant uuid := gen_random_uuid();
  account constant uuid := gen_random_uuid();
  cash constant uuid := gen_random_uuid();
  expense_category constant uuid := gen_random_uuid();
  income_category constant uuid := gen_random_uuid();
  device constant uuid := gen_random_uuid();
  expense constant uuid := gen_random_uuid();
  second_expense constant uuid := gen_random_uuid();
  income constant uuid := gen_random_uuid();
  expense_operation constant uuid := gen_random_uuid();
  expense_body jsonb;
  income_body jsonb;
  leases jsonb;
  selected jsonb;
  details jsonb;
  event_id uuid;
  claim_id uuid;
begin
  if has_function_privilege('authenticated','public.ledger_push_claim()','execute')
    or has_function_privilege('anon','public.ledger_push_bind(uuid,text)','execute')
    or has_function_privilege('authenticated','public.ledger_push_finish(uuid,uuid,uuid,text,text,integer,integer)','execute')
    or has_table_privilege('authenticated','ledger_private.push_devices','select') then
    raise exception 'Privileged dispatch or tokens are accessible to clients';
  end if;

  perform set_config('request.jwt.claim.sub',actor_id::text,true);
  perform public.ledger_apply('budget',shared,shared,0,gen_random_uuid(),'{"name":"Family","type":"SHARED","currency":"RSD","icon":"wallet","color":1}');
  perform public.ledger_apply('budget',personal,personal,0,gen_random_uuid(),'{"name":"Personal","type":"PERSONAL","currency":"RSD","icon":"wallet","color":1}');
  perform public.ledger_apply('account',shared,account,0,gen_random_uuid(),'{"name":"Card","type":"CASH","opening":"0","icon":"wallet","color":1,"order":0,"archived":false,"currency":"RSD"}');
  perform public.ledger_apply('account',shared,cash,0,gen_random_uuid(),'{"name":"Cash","type":"CASH","opening":"0","icon":"wallet","color":1,"order":0,"archived":false,"currency":"RSD"}');
  perform public.ledger_apply('category',shared,expense_category,0,gen_random_uuid(),'{"name":"Groceries","type":"EXPENSE","icon":"basket","color":1,"order":0,"archived":false}');
  perform public.ledger_apply('category',shared,income_category,0,gen_random_uuid(),'{"name":"Salary","type":"INCOME","icon":"wallet","color":1,"order":0,"archived":false}');
  insert into public.budget_memberships(budget_id,user_id,role)
    values(shared,recipient,'EDITOR'), (shared,removed,'VIEWER'), (personal,recipient,'EDITOR');

  perform public.ledger_push_bind(gen_random_uuid(),repeat('actor-one',10));
  perform public.ledger_push_bind(gen_random_uuid(),repeat('actor-two',10));
  perform set_config('request.jwt.claim.sub',recipient::text,true);
  perform public.ledger_push_bind(device,repeat('recipient',10));
  perform public.ledger_push_settings(shared,'expense',false);
  perform set_config('request.jwt.claim.sub',removed::text,true);
  perform public.ledger_push_bind(gen_random_uuid(),repeat('removed',10));
  perform set_config('request.jwt.claim.sub',outsider::text,true);
  perform public.ledger_push_bind(gen_random_uuid(),repeat('outsider',10));
  perform set_config('request.jwt.claim.sub',actor_id::text,true);

  expense_body := jsonb_build_object('type','EXPENSE','accountId',account,'categoryId',expense_category,
    'amount','2500','currency','RSD','accountCurrency','RSD','budgetCurrency','RSD',
    'budgetAmount','2500','budgetRate','1','budgetRateDate','2026-11-23','budgetRateSource','Same currency',
    'date','2026-11-23','note','private note','payer',recipient);
  income_body := expense_body || jsonb_build_object('type','INCOME','categoryId',income_category,
    'amount','50000','budgetAmount','50000');

  if exists(select 1 from ledger_private.push_events) then raise exception 'Event before cloud commit'; end if;
  perform public.ledger_apply('entry',shared,expense,0,expense_operation,expense_body);
  perform public.ledger_apply('entry',shared,expense,0,expense_operation,expense_body);
  perform public.ledger_apply('entry',shared,income,0,gen_random_uuid(),income_body);
  perform public.ledger_apply('entry',shared,expense,1,gen_random_uuid(),
    expense_body || '{"budgetRateSource":"Technical refresh"}');
  perform public.ledger_import_batch(shared,jsonb_build_array(jsonb_build_object('kind','entry','id',gen_random_uuid(),
    'operation',gen_random_uuid(),'body',expense_body)));
  perform public.ledger_apply('entry',shared,gen_random_uuid(),0,gen_random_uuid(),
    (expense_body-array['categoryId','budgetAmount','budgetRate','budgetRateDate','budgetRateSource']) ||
    jsonb_build_object('type','TRANSFER','accountAmount','2500','accountRate','1','accountRateDate','2026-11-23',
      'accountRateSource','Same currency','destinationId',cash,'destinationCurrency','RSD','destinationAmount','2500','transferRate','1'));
  if (select count(*) from ledger_private.push_events)<>2 or
     exists(select 1 from ledger_private.push_events where ledger_private.push_events.actor<>actor_id) then
    raise exception 'Wrong notification events or actor';
  end if;

  update public.budget_memberships set active=false where budget_id=shared and user_id=removed;
  leases := public.ledger_push_claim();
  if jsonb_array_length(leases)<>1 then raise exception 'A disabled, removed, unrelated, or actor device received a lease'; end if;
  selected := leases->0;
  event_id := (selected->>'event_id')::uuid;
  claim_id := (selected->>'lease')::uuid;
  if (selected->>'device_id')::uuid<>device or public.ledger_push_claim()<>'[]'::jsonb then
    raise exception 'Duplicate dispatch or wrong device';
  end if;
  perform set_config('request.jwt.claim.sub',outsider::text,true);
  if public.ledger_push_details(event_id,device) is not null then raise exception 'Events exposed to non-members'; end if;
  perform set_config('request.jwt.claim.sub',recipient::text,true);
  details := public.ledger_push_details(event_id,device);
  if details->>'kind' is distinct from 'income' or details->>'amount' is distinct from '50000'
    or details->>'currency' is distinct from 'RSD' or details ? 'note'
    or details->>'lock_screen_details' is distinct from 'false' then raise exception 'Wrong notification details'; end if;
  perform public.ledger_push_settings(null,'show_amounts',false);
  if public.ledger_push_details(event_id,device)->>'amount' is not null then raise exception 'Amount leaked after opt-out'; end if;
  perform public.ledger_push_settings(null,'enabled',false);
  if public.ledger_push_target(event_id,device,claim_id) is not null or public.ledger_push_details(event_id,device) is not null then
    raise exception 'Global opt-out did not take effect on a claimed job';
  end if;
  perform public.ledger_push_settings(null,'enabled',true);
  perform public.ledger_push_bind(device,repeat('refreshed',10));
  perform public.ledger_push_finish(event_id,device,claim_id,'unregistered',repeat('recipient',10));
  if not exists(select 1 from ledger_private.push_devices where id=device and active and registration_id=repeat('refreshed',10)) then
    raise exception 'Stale FCM response disabled a refreshed token';
  end if;

  perform public.ledger_push_settings(shared,'expense',true);
  perform public.ledger_push_settings(null,'show_amounts',true);
  perform set_config('request.jwt.claim.sub', actor_id::text, true);
  perform public.ledger_apply('entry',shared,second_expense,0,gen_random_uuid(),expense_body);
  perform public.ledger_apply('entry',shared,second_expense,1,gen_random_uuid(),
    expense_body || '{"budgetRateSource":"Technical refresh"}');
  leases := public.ledger_push_claim();
  if jsonb_array_length(leases)<>1 then raise exception 'Opted-in expense did not reach its own recipient'; end if;
  selected := leases->0;
  event_id := (selected->>'event_id')::uuid;
  claim_id := (selected->>'lease')::uuid;
  perform public.ledger_push_finish(event_id,device,claim_id,'sent',p_provider_status=>200);
  perform set_config('request.jwt.claim.sub',recipient::text,true);
  details := public.ledger_push_details(event_id,device);
  if details->>'kind' is distinct from 'expense' or details->>'amount' is distinct from '2500' then
    raise exception 'Technical change suppressed the stored budget valuation';
  end if;
  perform set_config('request.jwt.claim.sub',actor_id::text,true);
  perform public.ledger_apply('entry',shared,second_expense,2,gen_random_uuid(),'{"deletedAt":1795478400000}');
  if public.ledger_push_claim()<>'[]'::jsonb then raise exception 'Deletion alerts should default to off'; end if;
  perform set_config('request.jwt.claim.sub',recipient::text,true);
  details := public.ledger_push_details(event_id,device);
  if details->>'entry_exists' is distinct from 'false' or details->>'amount' is not null then
    raise exception 'A deleted transaction should open history with no stale amount';
  end if;
  update public.budget_memberships set active=false where budget_id=shared and user_id=recipient;
  if public.ledger_push_details(event_id,device) is not null then raise exception 'Removed member could still read a previous event'; end if;
  perform set_config('request.jwt.claim.sub','',true);
  perform public.ledger_push_unbind(device);
  if exists(select 1 from ledger_private.push_devices where id=device) then raise exception 'Offline logout revocation failed'; end if;
end $$;
rollback;
