# Verification

| Phase | Main files and behavior | Verification |
|---|---|---|
| Android foundation | Gradle project, Room, `HomeHistory.kt`, `Editor.kt`, `Settings.kt` | Debug build, Android 15 emulator |
| Collaborative budgets | `sync/`, `CloudSettings.kt`, `supabase/migrations/`, account deletion Edge Function | Debug build and local PostgreSQL migration/authorization suite |
| Financial features | `Rates.kt`, `Recurring.kt`, `Reports.kt`, `Analytics.kt`, `Portability.kt` | Debug build and automated money, recurrence, migration and file round-trip tests |
| Release preparation | Language resources, UI tests, Room schema exports, documentation and build configuration | Use the build/test commands in the README |

For hosted setup instructions, see the [complete setup guide](setup-guide.md). To run the database tests, start a **disposable local Supabase stack** with `supabase start` and check that all repository migrations have been applied to it. Run the following from the repository root with a PostgreSQL client installed. This example uses the default local port and development credentials.

```sh
psql 'postgresql://postgres:postgres@127.0.0.1:54322/postgres' -v ON_ERROR_STOP=1 -f supabase/tests/ledger.sql
psql 'postgresql://postgres:postgres@127.0.0.1:54322/postgres' -v ON_ERROR_STOP=1 -f supabase/tests/invitations.sql
psql 'postgresql://postgres:postgres@127.0.0.1:54322/postgres' -v ON_ERROR_STOP=1 -f supabase/tests/import_merge.sql
psql 'postgresql://postgres:postgres@127.0.0.1:54322/postgres' -v ON_ERROR_STOP=1 -f supabase/tests/bank_conversion.sql
psql 'postgresql://postgres:postgres@127.0.0.1:54322/postgres' -v ON_ERROR_STOP=1 -f supabase/tests/transaction_tombstones.sql
psql 'postgresql://postgres:postgres@127.0.0.1:54322/postgres' -v ON_ERROR_STOP=1 -f supabase/tests/budget_valuation.sql
```

The SQL uses fixed test identities inside a transaction and rolls back. It validates invitation redemption, direct RLS access, editor and viewer authorization, retries, conflict versions, and revocation. It does not require an Android client.

## Verified builds

The project produced the debug APK, unsigned release APK and release AAB with Gradle 8.11.1. Android lint reported zero errors and 17 warnings. Nine JVM tests passed, and 13 instrumented tests passed on an Android 15 emulator in airplane mode. The instrumented suite checked Room migration, transaction entry, search, historical rates, duplicate handling, JSON restore, outbox staging, and reporting across 10,000 transactions. The exchange-rate tests also checked daily cache reuse, fallback providers, rate limits, offline recovery, and the migration from version 2. The local PostgreSQL suite passed, including recurrence uniqueness and owned-budget cleanup after account deletion.

On the connected Android 15 emulator, the app downloaded all 30 starting-currency pairs on launch without opening Settings. Normal Save recorded a live EUR-to-RSD conversion with its rate, date, and provider. A separate historical EUR expense was saved in airplane mode and automatically converted after reconnection. Its original amount and transaction date were preserved.

The setup guide was checked against Supabase CLI 3.2.0 help and the linked vendor documentation. The debug build, JVM tests, and lint were rerun on macOS with JDK 17 and Android SDK 35. Windows and Linux installation steps follow the linked platform documentation and were outside this run.

## Android email authentication

The debug build passed 18 JVM tests and 15 instrumented tests on an Android 15 emulator in airplane mode. Android opened both auth links in the app. The tests exercised a closed activity, a running activity, a background activity, and activity recreation. The mocked Auth tests verified signup and resend requests, PKCE exchange, confirmation of the returned account, password updates and sign-in with the new password, rate limits, invalid or consumed links, and preservation of a different active account. Transaction entry and search also passed on a separate offline-only build with no Supabase settings. Android lint reported zero errors and five warnings.

The signed APK embeds the project URL and public key from `cloud.properties`. Hosted email delivery and clicking a link from an actual inbox still need to be checked on the configured project. Complete the [hosted email walkthrough](setup-guide.md#supabase-email-authentication-and-android-deep-links) with a disposable account.

## Shared budget invitation verification

The debug build passed 24 JVM and 18 Android 15 emulator tests. The invitation scenarios covered the old Share-message format, usable and expired sessions, refresh, unconfirmed accounts, server-confirmed creation, and separate network, rate-limit, and permission failures. On the emulator, a successful response loaded accounts, categories, and transactions into Room and selected the budget. The interrupted-download test kept the membership, requested a background retry, and completed the local download without a second redemption.

Both SQL suites and the HTTP integration test ran against the disposable local Supabase stack. The HTTP test created separate confirmed Auth accounts, signed in, refreshed the recipient's session, checked the saved invitation and active membership, and compared both snapshots after each account contributed transaction data. The opposing race requests accepted one user and rejected the other. The SQL tests also covered unconfirmed access, direct RLS checks, and retries after expiry or the attempt limit. The local HTTP test enabled auto-confirmation on its isolated instance. The SQL fixtures exercised the unconfirmed-user case separately. The hosted project accepted the public configuration for its Auth settings and reported that email auto-confirmation was off. The affected account, the two phones, and hosted invitation redemption still require the [two-device walkthrough](setup-guide.md#11-create-and-join-a-shared-budget).

The migration and test environments leave existing hosted data intact. The migration is `202611210001_invitation_redemption.sql`. See [deployment steps](setup-guide.md#5-apply-and-verify-the-database-migrations).

An upgrade fixture reproduced the legacy rejection of a pasted Share message and redeemed the same pre-migration code after applying the fix. A separate local smoke test used two authenticated WebSocket sessions and the publishable key. Both sessions received a budget-change event from the other user's expense, fetched matching snapshots, and calculated a total of 150 RSD.

## Bank conversion verification

The JVM tests cover exact decimal and reciprocal rates, dated reference fallback, and choosing by transaction date and confirmation time. The Room and editor tests record and correct successive RSD purchases and RUB income on a USD account. They verify that official rate changes leave confirmed values intact and that deleting the latest confirmation falls back to the preceding eligible transaction. JSON backup and the Room 4 to 5 migration continue to preserve historical fields.

## Manual budget valuation verification

The main budget panel previously showed a read-only preview. The only override was a rate field under the collapsed **Manual exchange rate** controls. The editor tests now change each budget field, change the original amount, restore the reference quote, save, and verify that the next USD income reuses the manual rate.

Room tests cover income and expenses, history across dates and scopes, refresh and deletion, independent bank and budget valuations, and Room 6 to 7 and JSON restore. The clean debug build passed 35 JVM tests and 35 Android 15 tests. Lint reported zero errors and five warnings, and all six local SQL suites passed. Two authenticated clients on the disposable local Supabase API received the same 100 USD account credit and 11,400 RSD valuation. Hosted deployment is outside this verification. [Apply the migrations](setup-guide.md#5-apply-and-verify-the-database-migrations)

## Transaction group headers

The debug build passed 42 JVM tests and 36 Android 15 instrumented tests. Lint reported zero errors and five warnings. The group tests cover all four grouping modes, date ranges and filters, stored multi-currency valuations, partial totals, transfer amounts, and a 10,000-transaction group. The editor and history tests verify that collapsed totals update after create, edit, and delete. Room fixtures cover imports and remote snapshots. The history test also passed on a 320 dp Android viewport in dark mode with 125% font scaling.

## Overview controls

The debug build passed 48 JVM tests and 38 Android 15 instrumented tests. Lint reported zero errors and 17 existing code or dependency warnings. Chart tests cover 3, 7, 15, and 30 categories, proportional sectors, sub-percent shares, connector spacing and hit detection, saved multi-currency valuations, and changes received through Room sync. The overview UI tests also passed on a 320 dp dark-mode viewport with 200% font scaling and on a wide light-mode viewport.

The UI tests exercise sector and label selection, a 30-category legend, group expansion with visible totals, and resetting by pull or button. They check that the largest categories retain labels, that selecting a previously unlabeled category shows its label and details, and that clearing the selection restores the priority labels. They also check that the gesture preserves the budget, period, and grouping, and leaves filters alone when the drag starts below the top or a text field is focused. Screenshots use synthetic budget data.

## Transaction deletion and sync recovery

The linked hosted schema was inspected with read-only queries. The live `ledger_apply` body matched the original migration. The hosted project had versions through `202611210001` and no user-defined transaction trigger at the time of inspection. Its transaction columns use a default of 1 for `version`, and `deleted_at` is nullable.

Use these read-only queries in the SQL editor to verify the deployed constraint and the table’s other constraints.

```sql
SELECT
    conname,
    pg_get_constraintdef(oid) AS definition
FROM pg_constraint
WHERE conrelid = 'public.transactions'::regclass
  AND conname = 'transactions_check2';

SELECT
    conname,
    contype,
    pg_get_constraintdef(oid) AS definition
FROM pg_constraint
WHERE conrelid = 'public.transactions'::regclass
ORDER BY conname;
```

`transactions_check2` requires `account_amount`, `account_rate`, `account_rate_date`, and `account_rate_source` to be either all NULL or all non-NULL. The deployed field-name transform produced `accountRatedate` and `accountRatesource` instead of `accountRateDate` and `accountRateSource`. The transform produced the same casing error for the budget rate date and source. A read-only synthetic query on the hosted database reproduced all four names. Older Android builds ignored the unrecognized properties, so a downloaded row kept its amount and rate and lost its date and source in Room.

Deleting that downloaded row set `deletedAt` and queued a full upsert. PostgreSQL checked the proposed `INSERT … ON CONFLICT DO UPDATE` row and rejected the partial account fields with SQLSTATE `23514` before it could update the existing row. Local PostgreSQL reproduced that failure. The phone’s stored outbox was outside this verification.

Migration `202611220001` normalizes each word of a field name and updates only deletion and sync metadata when deleting an existing transaction. An incomplete active write still fails the same CHECK. The APK accepts both spellings and refreshes previously cached cloud snapshots on upgrade. SQL tests cover all transaction types, imported and recurring entries, duplicate deletes, viewer permissions, and concurrent edit/delete ordering. The Android tests check preservation of stuck operations across a Room migration and restart, offline visibility, permanent failure quarantine, dependency ordering, retry, and convergence of two separately authenticated Room stores. Developer diagnostics can use `adb logcat -s SharedLedgerSync` to see the operation type, sanitized SQLSTATE and constraint name. The application UI shows a recovery message and the pending and failed counts.

The signed debug build passed 32 JVM tests and 30 instrumented tests on a fresh Android 15 emulator. Lint reported zero errors and five warnings. Five local SQL suites passed.

Two Android 15 emulators signed in as an owner and an editor against the disposable local Supabase stack. The owner queued deletions offline and closed the test process. The editor queued an offline edit while it still had the original rows. Both clients pulled the same tombstones on reconnection, cleared their outboxes, and restored account and budget totals. The test covered a confirmed purchase with missing local rate metadata, income, a transfer, an imported transaction, and a recurring occurrence. The recurring rule stayed active.

### Verify deletion on two phones

1. Apply the pending migrations to a test Supabase project and install the APK over the existing app. Sign in as an owner on phone A and as an editor in the same test budget on phone B.
2. On phone A, record a same-currency expense and income, a transfer, and an RSD purchase with a confirmed USD debit. Sync both phones and compare the accounts and budget totals.
3. Put A offline, delete the transactions, and reopen the app. The deletions should still be hidden and listed as pending, while B should still show the original records.
4. On B, edit the note of one of those entries while offline. Reconnect A and select **Sync now**, then reconnect B and sync. Both phones should hide the entries and show the same corrected account balances and totals. A new bank transaction should use the preceding confirmed rate or the applicable reference rate.
5. On a device with a previously stuck deletion, install the APK and sync after deploying the migration. If the new build previously flagged it as failed, choose **Retry sync**. Confirm the pending and failed counts clear after the server accepts the deletion.

Hosted write and physical-device tests require a test project and signed-in devices. [Deploy the migrations](setup-guide.md#5-apply-and-verify-the-database-migrations)

## Deployed project acceptance

Use two separate confirmed accounts on two Android devices connected to the same test project.

1. Create a shared budget, redeem an editor invite, and add an expense from each device. Verify both clients eventually show the same transaction IDs and totals.
2. Disable connectivity on one device, record three expenses, then reconnect. Check that all three appear on the other device once.
3. Record a EUR expense in an RSD budget at a historical rate, then add a new manual rate for the same date. Verify the saved transaction retains the original conversion.
4. Set up a monthly rule on a shared budget and let both offline devices generate the same due occurrence. Reconnect and verify one transaction exists.
5. Make conflicting edits while both devices are offline, reconnect, and select the desired version in Cloud sync. Verify the final version on both devices.
6. Revoke access from the owner device. After the other device syncs, verify the budget is removed from its ledger and direct requests are denied. Sign in with another account on the same device and verify account isolation.
7. Export a budget to JSON, restore it into a clean installation, then import it again. Verify relationships and check the duplicate count.
8. In an RSD shared budget, record 2,500 RSD on a USD account and confirm an actual 21.50 USD debit. From the other device, verify both amounts and **Bank actual · Confirmed**. Edit the note and sync. Both devices should still show the same amounts and rates. Start another RSD expense and check the suggested debit.

A cloud-configured debug APK was also clean-built and signature-verified. The embedded public configuration matched the local properties file, and the configured Auth settings endpoint returned HTTP 200. That check validates gateway access. Complete the [two-device walkthrough](setup-guide.md#11-create-and-join-a-shared-budget) on your own project to verify sign-in, sync, and Realtime. Independently test hosted confirmation and recovery email delivery and the deployed Edge Function using disposable accounts.

The optional hosted integration check uses two existing confirmed accounts in a disposable project. Set `SUPABASE_URL`, `SUPABASE_PUBLISHABLE_KEY`, `LEDGER_TEST_OWNER_EMAIL`, `LEDGER_TEST_OWNER_PASSWORD`, `LEDGER_TEST_EDITOR_EMAIL`, and `LEDGER_TEST_EDITOR_PASSWORD` in the command environment. Then run `python3 scripts/verify_cloud.py`. It creates and soft-deletes test budgets and checks real sign-in, session refresh, invitation and membership state, matching snapshots, conflicts, and revocation. To create disposable accounts automatically, run `python3 scripts/verify_cloud.py --local` against a separate loopback Supabase stack with email auto-confirmation enabled for that stack. Provide its local API URL as `SUPABASE_URL` and its local publishable or anon key as `SUPABASE_PUBLISHABLE_KEY`. The local test adds a third contender to exercise concurrent redemption.

## Push notifications and APK updates

The debug build passed 53 JVM tests and 39 instrumented tests on Android 15. Lint reported zero errors and 17 warnings. The suite ran offline with both optional configurations absent. The update tests used a newer same-signed APK and local HTTP response fixtures covering release parsing, metadata and signature rejection, GitHub rate limits, failed downloads, and cancellation.

The Android test verified a real APK with the platform's package parser, then opened the standard installer using its app-private content URI. Cancel kept version code 2 installed. Confirming the update installed version code 3, and the existing Room expense and database integrity check passed afterward. Android also rejected an APK signed with a different key. A disposable release-signing fixture verified that the staging script extracts the version and minimum SDK and computes the SHA-256 from the signed APK. The test key is separate from the distribution signing key.

All seven database SQL suites passed on a disposable PostgreSQL instance with Supabase authentication roles and claims emulated locally. The notification suite verifies event and delivery uniqueness, actor exclusion across devices, preference and membership changes, registration rotation, logout revocation, and access to deleted-transaction history. The Deno dispatcher test uses mocked FCM and Supabase responses to verify caller authorization, the data-only payload, retry handling, and invalid-token classification.

```sh
./gradlew testDebugUnitTest lintDebug
./gradlew assembleDebug assembleDebugAndroidTest
deno test --allow-env=PUSH_DISPATCH_SECRET,FCM_SERVICE_ACCOUNT_JSON,SUPABASE_URL,SUPABASE_SERVICE_ROLE_KEY \
  supabase/functions/dispatch-push/dispatch_test.ts
```

To repeat the real-APK instrumentation test, build a newer APK in a scratch checkout using the same debug key as the installed test build. On a disposable emulator, install the older APK and its instrumented test APK, then stage the newer APK into app-private cache.

```sh
adb shell -T 'run-as dev.sharedledger sh -c "mkdir -p cache && cat > cache/update-fixture.apk"' < /path/to/newer.apk
adb shell am instrument -w -e class dev.sharedledger.updates.UpdatePipelineTest \
  -e updateFixtureSigner CERTIFICATE_SHA256 -e updateFixtureHash APK_SHA256 \
  -e updateFixtureVersion NEW_VERSION_CODE -e updateFixtureVersionName NEW_VERSION_NAME \
  dev.sharedledger.test/androidx.test.runner.AndroidJUnitRunner
```

Add `-e openUpdateInstaller true` to exercise the actual installer after granting device permission. Use a synthetic local budget and verify it before and after the upgrade. Live FCM delivery requires deployed Supabase and Firebase configuration, and live release discovery requires a configured public GitHub release. [Firebase setup](setup-guide.md#firebase-push-notification-setup) · [APK update setup](setup-guide.md#building-and-publishing-sharedledger-releases)

## Signed GitHub releases

The first production build is SharedLedger 0.3.0 with Android version code 3 and application ID `dev.sharedledger`. The release script verified the APK with Android Build Tools and checked that it carries the configured Supabase public values, Firebase Android client resources, stable manifest URL, and trusted certificate fingerprint. The actual release APK installed and opened to the Personal budget on an offline Android 15 emulator.

Seven Python release workflow tests passed. The setup command was rerun using its default tool detection and returned the same certificate fingerprint. Repeating version 0.3.0 left the staged APK checksum unchanged. Reusing version code 3 for 0.3.1 was rejected. The release's Gradle run passed 54 JVM tests and lint with zero errors and 17 warnings. All 39 Android 15 instrumented tests passed. The standard installer upgraded the same-signed debug fixtures from version code 2 to 3 and preserved the synthetic expense and Room integrity. Android rejected the production APK as an in-place update over the old debug signer. Use these commands to repeat the local tests.

```sh
python3 -m unittest discover -s scripts/tests
./gradlew testDebugUnitTest lintDebug
```

The public GitHub origin and its empty release history were verified with read-only requests. The stable manifest and the signed update remain available locally for manual publication. Live update discovery requires that publication. [Publication and signing guide](setup-guide.md#building-and-publishing-sharedledger-releases)
