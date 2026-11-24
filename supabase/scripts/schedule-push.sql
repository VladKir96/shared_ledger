-- Run in hosted SQL Editor after setting ledger_push_url and ledger_push_dispatch_secret in Vault.
-- Use the project base HTTPS URL and the same random secret as PUSH_DISPATCH_SECRET on the Edge Function.
create extension if not exists pg_cron;
create extension if not exists pg_net;

select cron.schedule('sharedledger-push', '* * * * *', $job$
  select net.http_post(
    url := (select decrypted_secret from vault.decrypted_secrets where name='ledger_push_url')
      || '/functions/v1/dispatch-push',
    headers := jsonb_build_object('Content-Type','application/json',
      'x-ledger-dispatch',(select decrypted_secret from vault.decrypted_secrets where name='ledger_push_dispatch_secret')),
    body := '{}'::jsonb,
    timeout_milliseconds := 55000
  );
$job$);
