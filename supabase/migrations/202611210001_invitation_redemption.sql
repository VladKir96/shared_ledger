-- Record who actually redeemed a code so a lost response can be retried.
create table ledger_private.invitation_redemptions (
  invitation_id uuid not null references public.invitations on delete cascade,
  user_id uuid not null references auth.users on delete cascade,
  primary key (invitation_id, user_id)
);

alter table ledger_private.invitation_redemptions enable row level security;
revoke all on ledger_private.invitation_redemptions from public, anon, authenticated;

create function ledger_private.invitation_auth_error(p_actor uuid) returns text
language sql stable set search_path = '' as $$
  select coalesce(
    (select case when nullif(u.email, '') is not null and u.email_confirmed_at is null
                  then 'EMAIL_NOT_CONFIRMED' else '' end
     from auth.users u where u.id=p_actor),
    'AUTH_REQUIRED')
$$;

-- Keep the error field for older Android builds. Clients can branch on the code.
create function ledger_private.invitation_error(p_code text) returns jsonb
language sql immutable set search_path = '' as $$
  select jsonb_build_object('code', p_code, 'error', case p_code
    when 'AUTH_REQUIRED' then 'Please sign in before accepting an invitation.'
    when 'EMAIL_NOT_CONFIRMED' then 'Please confirm your email address.'
    when 'PERMISSION_DENIED' then 'You do not have permission to perform this action.'
    when 'INVITATION_NOT_FOUND' then 'Invalid invitation code.'
    when 'INVITATION_EXPIRED' then 'This invitation has expired.'
    when 'INVITATION_ALREADY_USED' then 'This invitation has already been used.'
    when 'INVITATION_REVOKED' then 'This invitation is no longer active.'
    when 'RATE_LIMITED' then 'Too many attempts. Please try again later.'
    else 'Unable to verify the invitation right now.'
  end)
$$;

create or replace function public.ledger_invite(p_budget uuid, p_role text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  actor uuid := auth.uid();
  auth_error text := ledger_private.invitation_auth_error(actor);
  token text;
  invitation uuid;
begin
  if auth_error <> '' then return ledger_private.invitation_error(auth_error); end if;
  if p_budget is null or p_role is null or p_role not in ('EDITOR','VIEWER') then
    return ledger_private.invitation_error('PERMISSION_DENIED');
  end if;

  perform pg_advisory_xact_lock(hashtextextended(p_budget::text,63));
  if ledger_private.role_for(p_budget) is distinct from 'OWNER' or
     not exists (select 1 from public.budgets where id=p_budget and type='SHARED') then
    return ledger_private.invitation_error('PERMISSION_DENIED');
  end if;
  if (select count(*) from public.invitations where budget_id=p_budget and created_at>now()-interval '1 hour') >= 20 then
    return ledger_private.invitation_error('RATE_LIMITED');
  end if;

  token := encode(extensions.gen_random_bytes(32),'hex');
  insert into public.invitations (budget_id,token_hash,role,expires_at,created_by)
  values(p_budget,extensions.digest(token,'sha256'),p_role,now()+interval '24 hours',actor)
  returning id into invitation;
  return jsonb_build_object('code','CREATED','id',invitation,'budgetId',p_budget,'token',token);
end $$;

create or replace function public.ledger_redeem(p_token text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  actor uuid := auth.uid();
  auth_error text := ledger_private.invitation_auth_error(actor);
  attempts integer;
  token text;
  code_hash bytea;
  found_invitation uuid;
  target uuid;
  invite public.invitations;
  already_member boolean;
begin
  if auth_error <> '' then return ledger_private.invitation_error(auth_error); end if;

  insert into ledger_private.invite_attempts as a (user_id,window_start,attempts) values(actor,now(),1)
  on conflict(user_id) do update set
    window_start=case when a.window_start<now()-interval '15 minutes' then now() else a.window_start end,
    attempts=case when a.window_start<now()-interval '15 minutes' then 1 else least(a.attempts+1,1000) end
  returning a.attempts into attempts;
  if p_token is not null and length(p_token)<=512 then
    token := lower(regexp_replace(p_token,'[[:space:]-]','','g'));
    if token ~ '^[0-9a-f]{64}$' then
      code_hash := extensions.digest(token,'sha256');
      select i.id,i.budget_id into found_invitation,target from public.invitations i where i.token_hash=code_hash;
    end if;
  end if;
  if attempts>12 and not exists (
    select 1 from ledger_private.invitation_redemptions r where r.invitation_id=found_invitation and r.user_id=actor
  ) then
    return ledger_private.invitation_error('RATE_LIMITED');
  end if;
  if found_invitation is null then return ledger_private.invitation_error('INVITATION_NOT_FOUND'); end if;

  -- Use the same lock as budget edits, revocation and membership updates.
  perform pg_advisory_xact_lock(hashtextextended(target::text,63));
  select i.* into invite from public.invitations i where i.id=found_invitation for update;
  if not found or not exists (
    select 1 from public.budgets b where b.id=target and b.deleted_at is null and b.type='SHARED'
  ) then
    return ledger_private.invitation_error('INVITATION_NOT_FOUND');
  end if;

  already_member := ledger_private.role_for(target) is not null;
  if already_member and exists (
    select 1 from ledger_private.invitation_redemptions r where r.invitation_id=invite.id and r.user_id=actor
  ) then
    return jsonb_build_object('code','ALREADY_MEMBER','budgetId',target);
  end if;
  if attempts>12 then return ledger_private.invitation_error('RATE_LIMITED'); end if;
  if invite.revoked_at is not null then return ledger_private.invitation_error('INVITATION_REVOKED'); end if;
  if invite.expires_at<=now() then return ledger_private.invitation_error('INVITATION_EXPIRED'); end if;
  if already_member then return jsonb_build_object('code','ALREADY_MEMBER','budgetId',target); end if;
  if invite.use_count>=invite.max_uses then return ledger_private.invitation_error('INVITATION_ALREADY_USED'); end if;

  insert into public.budget_memberships as m (budget_id,user_id,role) values(target,actor,invite.role)
  on conflict (budget_id,user_id) do update set role=excluded.role,active=true,joined_at=now() where m.role<>'OWNER';
  if not found then return ledger_private.invitation_error('PERMISSION_DENIED'); end if;
  update public.invitations set use_count=use_count+1 where id=invite.id;
  insert into ledger_private.invitation_redemptions (invitation_id,user_id) values(invite.id,actor)
  on conflict do nothing;
  update public.budgets set sync_seq=sync_seq+1 where id=target;
  return jsonb_build_object('code','JOINED','budgetId',target);
end $$;

revoke all on function ledger_private.invitation_auth_error(uuid), ledger_private.invitation_error(text)
  from public, anon, authenticated;
revoke all on function public.ledger_invite(uuid,text), public.ledger_redeem(text) from public, anon, authenticated;
grant execute on function public.ledger_invite(uuid,text), public.ledger_redeem(text) to authenticated;
