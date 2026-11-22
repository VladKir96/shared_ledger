-- Keep the existing monetary columns authoritative. Older archives and clients omit the metadata.
alter table public.transactions add column bank_conversion jsonb;
alter table public.transactions add constraint transactions_bank_conversion_check check (
  bank_conversion is null or (
    jsonb_typeof(bank_conversion) = 'object' and type in ('EXPENSE','INCOME') and currency <> account_currency
    and coalesce(bank_conversion->>'source','') in
      ('BANK_ACTUAL','BANK_ESTIMATED','BANK_STALE','REFERENCE_API','CACHED_RATE','MANUAL_RATE','ENTERED_AMOUNT','PENDING')
    and case bank_conversion->>'source'
      when 'PENDING' then account_amount is null
      when 'BANK_ACTUAL' then coalesce(account_amount > 0 and account_rate > 0
        and jsonb_typeof(bank_conversion->'confirmedAt') = 'number',false)
      else coalesce(account_amount > 0,false)
    end
  )
);

-- The ledger RPC already compares versions. Also preserve an actual amount on metadata-only
-- writes from older clients that do not know how to send bank_conversion.
create function ledger_private.keep_confirmed_bank_amount() returns trigger
language plpgsql set search_path = '' as $$
begin
  if old.bank_conversion->>'source' = 'BANK_ACTUAL'
     and (new.bank_conversion->>'source') is distinct from 'BANK_ACTUAL'
     and (new.account_id,new.currency,new.account_currency,new.type,new.amount)
         is not distinct from (old.account_id,old.currency,old.account_currency,old.type,old.amount) then
    new.bank_conversion := old.bank_conversion;
    new.account_amount := old.account_amount;
    new.account_rate := old.account_rate;
    new.account_rate_date := least(new.date, old.account_rate_date);
    new.account_rate_source := old.account_rate_source;
    if new.date <> old.date then
      new.bank_conversion := new.bank_conversion - array['reference','adjustment'];
    end if;
  end if;
  return new;
end $$;

create trigger preserve_confirmed_bank_amount before update on public.transactions
for each row execute function ledger_private.keep_confirmed_bank_amount();
revoke all on function ledger_private.keep_confirmed_bank_amount() from public, anon, authenticated;
