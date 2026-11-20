# Verification

| Phase | Main files and behavior | Verification |
|---|---|---|
| Android foundation | Gradle project, Room, `HomeHistory.kt`, `Editor.kt`, `Settings.kt` | Debug build, Android 15 emulator |
| Collaborative budgets | `sync/`, `CloudSettings.kt`, `supabase/migrations/`, account deletion Edge Function | Debug build and local PostgreSQL migration/authorization suite |
| Financial features | `Rates.kt`, `Recurring.kt`, `Reports.kt`, `Analytics.kt`, `Portability.kt` | Debug build and automated money, recurrence, migration and file round-trip tests |
| Release preparation | Language resources, UI tests, Room schema exports, documentation and build configuration | Use the build/test commands in the README |

For hosted setup instructions, see the [complete setup guide](setup-guide.md). To run the database tests, start a **disposable local Supabase stack** with `supabase start` and check that both repository migrations have been applied to it. Run the following from the repository root with a PostgreSQL client installed. This example uses the default local port and development credentials.

```sh
psql 'postgresql://postgres:postgres@127.0.0.1:54322/postgres' -v ON_ERROR_STOP=1 -f supabase/tests/ledger.sql
```

The SQL uses fixed test identities inside a transaction and rolls back. It validates invitation redemption, direct RLS access, editor and viewer authorization, retries, conflict versions, and revocation. It does not require an Android client.

## Verified builds

The project produced the debug APK, unsigned release APK and release AAB with Gradle 8.11.1. Android lint reported zero errors and 17 warnings. Nine JVM tests passed, and 13 instrumented tests passed on an Android 15 emulator in airplane mode. The instrumented suite checked Room migration, transaction entry, search, historical rates, duplicate handling, JSON restore, outbox staging, and reporting across 10,000 transactions. The exchange-rate tests also checked daily cache reuse, fallback providers, rate limits, offline recovery, and the migration from version 2. The local PostgreSQL suite passed, including recurrence uniqueness and owned-budget cleanup after account deletion.

On the connected Android 15 emulator, the app downloaded all 30 starting-currency pairs on launch without opening Settings. Normal Save recorded a live EUR-to-RSD conversion with its rate, date, and provider. A separate historical EUR expense was saved in airplane mode and automatically converted after reconnection. Its original amount and transaction date were preserved.

The setup guide was checked against Supabase CLI 3.2.0 help and the linked vendor documentation. The debug build, JVM tests, and lint were rerun on macOS with JDK 17 and Android SDK 35. Windows and Linux installation steps follow the linked platform documentation and were outside this run.

## Android email authentication

The debug build passed 18 JVM tests and 15 instrumented tests on an Android 15 emulator in airplane mode. Android opened both auth links in the app. The tests exercised a closed activity, a running activity, a background activity, and activity recreation. The mocked Auth tests verified signup and resend requests, PKCE exchange, confirmation of the returned account, password updates and sign-in with the new password, rate limits, invalid or consumed links, and preservation of a different active account. Transaction entry and search also passed on a separate offline-only build with no Supabase settings. Android lint reported zero errors and five warnings.

The signed APK embeds the project URL and public key from `cloud.properties`. Hosted email delivery and clicking a link from an actual inbox still need to be checked on the configured project. Complete the [hosted email walkthrough](setup-guide.md#supabase-email-authentication-and-android-deep-links) with a disposable account.

## Deployed project acceptance

Use two separate confirmed accounts on two Android devices connected to the same test project.

1. Create a shared budget, redeem an editor invite, and add an expense from each device. Verify both clients eventually show the same transaction IDs and totals.
2. Disable connectivity on one device, record three expenses, then reconnect. Check that all three appear on the other device once.
3. Record a EUR expense in an RSD budget at a historical rate, then add a new manual rate for the same date. Verify the saved transaction retains the original conversion.
4. Set up a monthly rule on a shared budget and let both offline devices generate the same due occurrence. Reconnect and verify one transaction exists.
5. Make conflicting edits while both devices are offline, reconnect, and select the desired version in Cloud sync. Verify the final version on both devices.
6. Revoke access from the owner device. After the other device syncs, verify the budget is removed from its ledger and direct requests are denied. Sign in with another account on the same device and verify account isolation.
7. Export a budget to JSON, restore it into a clean installation, then import it again. Verify relationships and check the duplicate count.

A cloud-configured debug APK was also clean-built and signature-verified. The embedded public configuration matched the local properties file, and the configured Auth settings endpoint returned HTTP 200. That check validates gateway access. Complete the [two-device walkthrough](setup-guide.md#11-create-and-join-a-shared-budget) on your own project to verify sign-in, sync, and Realtime. Independently test hosted confirmation and recovery email delivery and the deployed Edge Function using disposable accounts.

The optional hosted integration check uses two existing confirmed accounts in a disposable project. Set `SUPABASE_URL`, `SUPABASE_PUBLISHABLE_KEY`, `LEDGER_TEST_OWNER_EMAIL`, `LEDGER_TEST_OWNER_PASSWORD`, `LEDGER_TEST_EDITOR_EMAIL`, and `LEDGER_TEST_EDITOR_PASSWORD` in the command environment. Then run `python3 scripts/verify_cloud.py`. It creates and soft-deletes test budgets and checks real sign-in, RPC behavior, matching snapshots, conflicts and revocation.
