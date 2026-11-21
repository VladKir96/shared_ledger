-- Run on a disposable Supabase instance with all migrations applied.
begin;

insert into auth.users(id,email,email_confirmed_at,raw_user_meta_data) values
('b0000000-0000-0000-0000-000000000001','owner@example.test',now(),'{}'),
('b0000000-0000-0000-0000-000000000002','editor@example.test',now(),'{}'),
('b0000000-0000-0000-0000-000000000003','other@example.test',now(),'{}'),
('b0000000-0000-0000-0000-000000000004','unconfirmed@example.test',null,'{}');

create temp table invite_fixture(label text primary key, result jsonb);
grant select,insert on invite_fixture to authenticated;

create function pg_temp.require_code(actual jsonb, expected text) returns void language plpgsql as $$
begin
  if actual->>'code' is distinct from expected then
    raise exception 'Expected invitation outcome %, got %', expected, actual->>'code';
  end if;
end $$;

set local role authenticated;
do $$
declare budget constant uuid := 'c0000000-0000-0000-0000-000000000001';
begin
  perform set_config('request.jwt.claim.sub','',true);
  perform pg_temp.require_code(public.ledger_redeem(repeat('a',64)), 'AUTH_REQUIRED');
  perform set_config('request.jwt.claim.sub','b0000000-0000-0000-0000-000000000001',true);
  perform pg_temp.require_code(public.ledger_invite(budget,'EDITOR'), 'PERMISSION_DENIED');
  perform public.ledger_apply('budget',budget,budget,0,gen_random_uuid(),
    '{"name":"Invitation test budget","currency":"RSD","type":"SHARED","icon":"wallet","color":1}');
  perform pg_temp.require_code(public.ledger_invite(budget,'OWNER'), 'PERMISSION_DENIED');
  insert into invite_fixture select label, public.ledger_invite(budget,'EDITOR')
    from unnest(array['active','expired','revoked','unused']) label;
  if exists (select from invite_fixture where result->>'code' is distinct from 'CREATED') then
    raise exception 'Invitation creation failed';
  end if;
  if not public.ledger_revoke_invite(budget,(select (result->>'id')::uuid from invite_fixture where label='revoked')) then
    raise exception 'Could not revoke test invitation';
  end if;
end $$;
reset role;

update public.invitations set expires_at=now()-interval '1 second'
  where id=(select (result->>'id')::uuid from invite_fixture where label='expired');

do $$
begin
  if (select count(*) from invite_fixture f join public.invitations i on i.id=(f.result->>'id')::uuid
      where i.token_hash=extensions.digest(f.result->>'token','sha256')
        and i.budget_id='c0000000-0000-0000-0000-000000000001' and i.use_count=0)<>4 then
    raise exception 'Invitation was not persisted with the expected hash and budget';
  end if;
  if has_function_privilege('anon','public.ledger_redeem(text)','execute') or
     has_table_privilege('authenticated','public.invitations','select') or
     has_table_privilege('authenticated','ledger_private.invitation_redemptions','select') then
    raise exception 'Invitation RPC or private tables are exposed';
  end if;
end $$;

set local role authenticated;
do $$
declare budget constant uuid := 'c0000000-0000-0000-0000-000000000001';
  editor constant uuid := 'b0000000-0000-0000-0000-000000000002';
  active text := (select result->>'token' from invite_fixture where label='active');
  expired text := (select result->>'token' from invite_fixture where label='expired');
  revoked text := (select result->>'token' from invite_fixture where label='revoked');
  unused text := (select result->>'token' from invite_fixture where label='unused');
begin
  perform set_config('request.jwt.claim.sub','b0000000-0000-0000-0000-000000000004',true);
  perform pg_temp.require_code(public.ledger_redeem(active), 'EMAIL_NOT_CONFIRMED');
  perform pg_temp.require_code(public.ledger_invite(budget,'EDITOR'), 'EMAIL_NOT_CONFIRMED');

  perform set_config('request.jwt.claim.sub',editor::text,true);
  if exists(select from public.budget_memberships where budget_id=budget) or public.ledger_snapshot(budget) is not null then
    raise exception 'RLS allowed access before joining';
  end if;
  perform pg_temp.require_code(public.ledger_invite(budget,'EDITOR'), 'PERMISSION_DENIED');
  perform pg_temp.require_code(public.ledger_redeem('bad pasted text'), 'INVITATION_NOT_FOUND');
  perform pg_temp.require_code(public.ledger_redeem(repeat('f',64)), 'INVITATION_NOT_FOUND');
  perform pg_temp.require_code(public.ledger_redeem(expired), 'INVITATION_EXPIRED');
  perform pg_temp.require_code(public.ledger_redeem(revoked), 'INVITATION_REVOKED');
  perform pg_temp.require_code(public.ledger_redeem(E' \n' || upper(substr(active,1,32)) || E'-\r\n' || substr(active,33)), 'JOINED');
  perform pg_temp.require_code(public.ledger_redeem(active), 'ALREADY_MEMBER');
  perform pg_temp.require_code(public.ledger_redeem(unused), 'ALREADY_MEMBER');
  if (select count(*) from public.budget_memberships m where m.budget_id=budget and m.user_id=editor and m.active and m.role='EDITOR')<>1 or
     public.ledger_snapshot(budget) is null then
    raise exception 'Joined member could not see the cloud budget';
  end if;

  perform set_config('request.jwt.claim.sub','b0000000-0000-0000-0000-000000000003',true);
  perform pg_temp.require_code(public.ledger_redeem(active), 'INVITATION_ALREADY_USED');
  if public.ledger_snapshot(budget) is not null then raise exception 'One-use invitation was accepted twice'; end if;
  for n in 1..11 loop
    perform pg_temp.require_code(public.ledger_redeem(repeat('f',64)), 'INVITATION_NOT_FOUND');
  end loop;
  perform pg_temp.require_code(public.ledger_redeem(active), 'RATE_LIMITED');
end $$;
reset role;

-- A same-user retry still works after expiry, using the receipt and active membership.
update public.invitations set expires_at=now()-interval '1 second'
  where id=(select (result->>'id')::uuid from invite_fixture where label='active');

set local role authenticated;
do $$
begin
  perform set_config('request.jwt.claim.sub','b0000000-0000-0000-0000-000000000002',true);
  perform pg_temp.require_code(public.ledger_redeem((select result->>'token' from invite_fixture where label='active')), 'ALREADY_MEMBER');
  for n in 1..4 loop
    perform pg_temp.require_code(public.ledger_redeem(repeat('f',64)), 'INVITATION_NOT_FOUND');
  end loop;
  perform pg_temp.require_code(public.ledger_redeem(repeat('f',64)), 'RATE_LIMITED');
  perform pg_temp.require_code(public.ledger_redeem((select result->>'token' from invite_fixture where label='active')), 'ALREADY_MEMBER');
end $$;
reset role;

do $$
begin
  if (select count(*) from ledger_private.invitation_redemptions r
      where r.invitation_id in (select (result->>'id')::uuid from invite_fixture))<>1 or
     (select sum(use_count) from public.invitations where budget_id='c0000000-0000-0000-0000-000000000001')<>1 then
    raise exception 'Retry or existing membership incorrectly consumed another use';
  end if;
end $$;

rollback;
