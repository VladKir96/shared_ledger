alter table public.transactions add column budget_rate_confirmed_at bigint;

alter table public.transactions add constraint transactions_budget_confirmation_check check (
  budget_rate_confirmed_at is null or coalesce((
    budget_rate_confirmed_at > 0 and type in ('EXPENSE','INCOME') and currency <> budget_currency
    and budget_rate_source = 'User-defined' and budget_rate_date = date
    and budget_rate > 0 and budget_amount > 0
  ), false)
);

-- Clients that predate the confirmation field can still edit unrelated details.
create function ledger_private.keep_budget_rate_confirmation() returns trigger
language plpgsql set search_path = '' as $$
begin
  if old.budget_rate_confirmed_at is not null and new.budget_rate_confirmed_at is null and new.budget_amount > 0
    and (new.account_id,new.type,new.currency,new.budget_currency,new.date,
         new.budget_rate,new.budget_rate_date,new.budget_rate_source)
        is not distinct from (old.account_id,old.type,old.currency,old.budget_currency,old.date,
                              old.budget_rate,old.budget_rate_date,old.budget_rate_source) then
    new.budget_rate_confirmed_at := old.budget_rate_confirmed_at;
  end if;
  return new;
end $$;

create trigger preserve_budget_rate_confirmation before update on public.transactions
for each row execute function ledger_private.keep_budget_rate_confirmation();
revoke all on function ledger_private.keep_budget_rate_confirmation() from public, anon, authenticated;
