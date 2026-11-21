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
6. On sign-out, cached cloud budgets remain scoped to their previous user ID and the UI hides them. Verified revocation purges the protected local budget. While offline, the device can only enforce the last role it received. Android app-private storage and disabled OS backups protect the Room cache. Stored auth sessions and recovery verifiers use an AES-GCM key in Android Keystore.

Sync uploads individual records and pulls snapshots at the budget level. A large budget increases the snapshot download size. Bulk category moves and base-currency changes commit atomically in Room, and their individual cloud records can arrive across multiple sync requests. The final state reconciles after all writes are accepted.

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
