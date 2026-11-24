# Data model and synchronization

## Storage and signs

The Android sources live under `app/src/main/java/dev/sharedledger`. Presentation owns Compose and MVVM state. Domain code covers dates, decimal arithmetic, and reporting. Data owns Room and storage operations. Sync owns authentication, remote access, Realtime, and background jobs.

| Entity | Scope and relationships |
|---|---|
| Profiles | Supabase Auth user with a display name |
| Budgets | Owner, base currency, type, icon and color |
| Budget memberships | Unique budget/user pair with owner, editor or viewer role and active status |
| Accounts | Budget-owned wallet, account currency and signed opening balance |
| Categories | Budget-owned expense or income label with icon, color, order and archive state |
| Transactions | Budget, source account, optional destination, category, payer and creator |
| Recurring rules | Budget, account, category, original date and occurrence cursor |
| Exchange rates | Local cache keyed by pair, effective date and provider, with separate dated lookup records |
| Invitations | Backend-only hashes with expiry, redemption count and revocation state |
| Outbox and remote versions | Local record queue, expected server version, operation ID and optional conflict copy |

Income and expense records store **positive original amounts**. Their type determines the sign of the effect. Transfers store both positive legs in one atomic record. An account calculation starts at opening balance, adds income and incoming transfers, and subtracts expenses and outgoing transfers. Budget income, expenses and net cash flow use stored base-currency values and ignore transfers. Missing conversions are nullable and surfaced in reports.

Room stores decimal fields as strings. PostgreSQL uses numeric, and the transport serializes numeric quantities as strings. The budget and account exchange rates, source dates and provider names are frozen on each recorded transaction. Android dates are calendar dates in the device's local time zone. Absolute creation and edit timestamps are UTC milliseconds. The server supplies authoritative timestamps and versions for sync.

Room v1 creates the schema. Migration 1 to 2 adds a budget/payer/date index. Migration 2 to 3 deduplicates cached quotes by effective date, retains earlier lookup dates, and adds exchange-rate update settings and status without rewriting transaction amounts. Migration 3 to 4 records import mappings and marks pending import operations. Checked-in Room schemas support migration validation. Budget deletion cascades on local purge. Ledger user deletions, category archive, and account archive preserve historical records as appropriate.

## Sync protocol

1. Android changes Room and an outbox record in one database transaction. Each sync operation has a UUID and the server version that the edit was based on.
2. The app requests the authenticated user's manifest. The server returns authorized budget IDs, roles, and revision counters. The client pulls a snapshot for each changed budget and applies it with one Room transaction. Realtime budget changes wake this process. WorkManager also retries and reconciles periodically.
3. Server writes use `ledger_apply` and a per-budget transaction lock. Accepted edits increment the record version. Retries use the same operation ID. A stale version returns a conflict with the server copy. Users can choose a version from **Settings → Cloud sync**.
4. A recurring occurrence uses an identifier derived from rule ID and calendar date. PostgreSQL also enforces a unique rule and date pair, so two devices cannot add two occurrences for the same rule and day.
5. Supabase policies gate direct reads by active membership. Writes use authenticated functions that enforce roles and validate references. Editors can edit transactions and rules. Owners manage account and category settings, members and invitations. Editors can create new accounts and categories through the create-only import RPC. Viewer access is read-only. Invite tokens are random, hashed before storage, expire after 24 hours, and are redeemed atomically with per-user attempt limits.
6. On sign-out, cached cloud budgets remain scoped to their previous user ID and the UI hides them. Verified revocation removes the protected budget cache. Queued changes remain in app-private storage under the original user ID, so the user can retry if access is restored. While offline, the device can only enforce the last role it received. Android app-private storage and disabled OS backups protect the Room cache. Stored auth sessions and recovery verifiers use an AES-GCM key in Android Keystore.

Sync uploads individual records and pulls snapshots at the budget level. A large budget increases the snapshot download size. Bulk category moves and base-currency changes commit atomically in Room, and their individual cloud records can arrive across multiple sync requests. The final state reconciles after all writes are accepted.

### Transaction deletion and outbox recovery

Local deletion sets `deletedAt` and writes the outbox operation in the same Room transaction. Active queries, budgets, account balances, and bank-rate selection exclude tombstones. Supabase migration `202611220001` routes a transaction deletion to an in-place update of the existing row’s deletion timestamp and sync metadata. The server preserves amounts, rates, and relationships, increments the version and budget revision once, and returns the canonical tombstone. Duplicate requests return the existing tombstone. Deleting a never-published ordinary entry needs no server row. An unsent recurring occurrence can still publish a validated tombstone to prevent another device from recreating that occurrence.

Deletion wins over a concurrent edit of the same transaction. If an edit reached the server first, the server preserves its values in the tombstone. If the deletion reached the server first, both stale and rebased active edits receive the tombstone as a conflict. The Android client applies a received tombstone over any queued edit of that transaction. Other version conflicts still use the existing comparison screen.

Room migration 5 to 6 preserves previous outbox payloads and assigns them to the signed-in principal of their budget. It refreshes snapshot cursors so cached entries receive consistently spelled rate dates and sources after the server migration. Network failures, rate limits, and recoverable server errors use WorkManager backoff. Authentication failures can refresh the session, and access, constraint, and payload failures are recorded for explicit retry or correction. Independent operations continue while children of failed or conflicting records wait. A rejected import batch is retried by record so the failing record can be identified. The per-user last-success timestamp advances only after a sync leaves no unresolved changes. [Constraint diagnostics](verification.md#transaction-deletion-and-sync-recovery)

### Budget imports

Room applies the reviewed merge in one transaction. Stable IDs and local import links preserve account and category mappings and identify reimports. Ordinary restore keeps the version 1 archive structure and is local.

Cloud merges mark their new records in the sync outbox. Supabase migration `202611210002` adds `ledger_import_batch`, which checks active Owner or Editor membership under the per-budget lock and processes up to 50 creates per call. Existing IDs with conflicting writes are returned for review. A retry reuses the operation IDs to acknowledge already accepted records. Batch transactions resolve accounts and categories before dependent rules and transactions. The RPC cannot modify budget settings or memberships, and direct account/category maintenance remains owner-only.

## Invitation lifecycle

The owner calls `ledger_invite` for an active shared cloud budget. It returns `CREATED` with the invitation ID, budget ID, and a 64-character hexadecimal code after inserting the SHA-256 hash. The plaintext code is returned only at creation. Clients can list or revoke invitations through owner-scoped RPCs. The app never needs direct access to invitation or redemption-receipt rows.

For `ledger_redeem`, Supabase resolves the current user from the access token and checks the Auth user's email confirmation. The app normalizes pasted links and Share messages, and PostgreSQL normalizes the hexadecimal input before looking up its hash. The RPC uses a per-budget transaction lock and locks the invitation row. It checks revocation, expiry and remaining uses before adding or reactivating the budget/user membership, incrementing the count, recording a redemption receipt, and advancing the budget's Realtime revision in the same transaction. Both RPCs use `SECURITY DEFINER` with an empty search path and execute grants limited to the authenticated role.

`JOINED` and `ALREADY_MEMBER` include the budget ID. The membership primary key and the receipt's invitation/user primary key prevent duplicate rows. A retry by the same active member with a receipt still succeeds after expiry or revocation of the invite. Other users cannot reuse a consumed code. A revoked member cannot regain access with that same consumed code. Earlier codes use the same hashes and remain usable while active and unused. The migration leaves existing invitations and memberships in place.

After a successful RPC, the app pulls the authorized snapshot, applies the budget, accounts, categories and transactions to Room, and selects the budget. If the first pull fails, the backend membership remains active and WorkManager retries. Realtime and manifest checks reconcile the budget on the client. See the setup guide for actionable outcomes. [Invitation outcomes and diagnostics](setup-guide.md#diagnose-invitation-failures)

## Exchange-rate updates

Startup and foreground checks schedule network-constrained WorkManager jobs. Successful currency pairs are reused for the rest of the local calendar day. Periodic checks and newly pending transactions can request another job, and transient failures use exponential backoff. The required currencies are refreshed in batches. Other currencies used by visible accounts and budgets are checked against the starting currencies, with additional pairs looked up in the editor as needed.

Dated requests can use an earlier published quote from the same provider window. Online historical lookup checks up to seven days before the selected date. Offline entry can use a cached quote in that window and shows its actual date. An unresolved entry also serves as a durable retry record. Only unfilled account or budget conversion fields are updated, using the latest local version of the transaction. A shared-budget backfill goes through the normal versioned outbox. If another edit reached the server first, the client takes the authoritative server record and can fill any fields that are still pending.

### Bank conversion

Room migration 4 to 5 added nullable `entries.bankConversion`. Supabase migration `202611210003` adds matching nullable JSON to `transactions.bank_conversion`. Existing rows keep their historical amounts and rates with no inferred bank confirmation.

The existing account amount is the exact confirmed debit or credit when the source is `BANK_ACTUAL`. The stored account rate is the account amount divided by the original amount with DECIMAL128 precision. The editor can display the reciprocal. The metadata includes the rate source and confirmation time. Legacy estimate metadata stays readable. Account amounts and budget valuations use separate conversions. Budget amounts in their original currency are exact. An independently estimated budget valuation stays approximate even when the account amount is confirmed.

The app uses the latest confirmed native transaction in the same budget, account, original and account currency pair, and income or expense direction. It chooses by transaction date on or before the selected date, then by confirmation time for same-day entries. Editing or deleting the source changes which observation applies. Shared members receive the same confirmed observations through the versioned transaction snapshot. Membership policies restrict those records to that budget.

If there is no applicable bank rate, the app uses the applicable reference quote, a labeled dated cached quote, or pending status. Automatically suggested amounts and ambiguous legacy values remain ineligible until the user confirms the actual bank amounts.

### Budget valuation

The budget valuation converts the original amount to the budget currency. Direct input accepts either a positive decimal rate in budget units per original unit or an exact amount at the budget currency’s precision. Rate input uses half-even rounding in the budget currency. Amount input preserves the entered amount and derives its rate with DECIMAL128 precision. The account conversion and bank confirmation use their existing fields.

Room 7 and Supabase migration `202611220002` add a nullable confirmation timestamp to the existing transaction. The value records when a user saves a new manual valuation. Eligible active transactions are matched by budget, account, original and budget currencies, and direction. Selection uses transaction date on or before the requested date, then confirmation time. A saved suggestion retains its source and date without creating another confirmation. A changed or deleted observation affects later suggestions while previously saved amounts stay fixed. Older ambiguous `Manual` values retain their valuation and need an explicit edit to enter the scoped history.

Without an applicable confirmation, the app uses a configured manual rate for the date, a dated API quote, a labeled cache when offline, or pending conversion. **Use reference rate** bypasses both manual sources. Shared confirmations follow the existing transaction snapshot and versioned outbox. The migration preserves a confirmation during older-client edits that keep the same rate and scope. A full JSON restore retains confirmation metadata. Merging into another budget keeps the saved valuation where currencies match and leaves the destination’s suggestion history independent. [Import and export formats](files.md)

### Shared budget push notifications

Supabase migration `202611230001_notifications.sql` adds account-wide preferences in `public.push_preferences` and per-budget toggles in `public.push_budget_preferences`. Account settings default to enabled, with amounts visible in the unlocked notification and details private on the lock screen. Each shared budget enables expense and income alerts by default. Edit and deletion alerts require explicit opt-in. RLS limits preferences to their owner and checks active budget access for per-budget writes.

`ledger_private.push_devices` stores one FCM registration ID per installation and never exposes it to budget members. Registering requires authentication. An installation can revoke its binding after offline logout using a random private capability. Invalid registrations are disabled and devices that have not checked in for 35 days are pruned.

The transaction trigger writes the outbox in the same database transaction as the financial change. Workers can see the event after commit. The trigger uses `auth.uid()` for the actor and excludes transfers, historical imports, and valuation-only updates. A unique creation event per transaction and unique event/device delivery rows prevent duplicate jobs from repeated synchronization. Queue claims use row locks and per-attempt leases. The dispatcher rechecks membership, preferences, and current device binding before sending, then records acceptance, skips, errors, or retry state. Outbox data expires after seven days. FCM only receives an opaque event ID. The Android app fetches details using the signed-in recipient's session and rechecks authorization when opening the associated budget. No Room migration is required.
