# SharedLedger

SharedLedger is a native Android expense tracker for personal and shared budgets. You can start with a personal budget on one phone, or connect a hosted Supabase project so multiple people can work on the same budget. The app is free and ad-free. It supports offline entry, recurring transactions, analytics, and file import and export.

**New to Android development or Supabase? Start with the [complete setup guide](docs/setup-guide.md).** It walks through installing the tools, configuring a hosted project, building and installing the APK, and checking synchronization with two separate accounts.

## Features

- Track income, expenses, accounts, categories, and transfers. Search transactions and compare balances and spending in Analytics.
- See filtered totals in **Transactions** when grouping by date, category, account, or payer. [Read group totals](docs/setup-guide.md#read-transaction-group-totals)
- Work offline with a local SQLite database. Cloud-enabled budgets queue edits locally and synchronize after reconnection.
- Share budgets through one-use invitations. Owners manage access, Editors can record transactions, and Viewers have read-only access.
- Preview conversions using automatically refreshed daily and historical rates. Supported starting currencies are RSD, EUR, RUB, USD, GBP, and CHF. Recorded conversions retain their rate and source, and manual overrides are available under **Settings → Exchange rates**. [Frankfurter v2](https://frankfurter.dev/) · [Currency API fallback](https://github.com/fawazahmed0/exchange-api)
- Schedule recurring transactions and export budgets from **Settings → Import and export**. JSON preserves budget relationships and transfers. The [file format guide](docs/files.md) describes CSV mapping and import limits.
- Choose English, Russian, or Serbian for core interface labels. Some messages and default category names are in English. Currency amounts and dates follow the device format.

## Requirements

| Item | Version or purpose |
| --- | --- |
| Java | JDK **17**, including a Java compiler |
| Android SDK | **Android SDK Platform 35**, **Build Tools 35.0.0**, and **Platform-Tools** for ADB |
| Android Studio | Recommended for setup and running the app. See [Android tool installation](docs/setup-guide.md#1-install-the-android-tools). |
| Gradle | The checked-in wrapper downloads **Gradle 8.11.1**. |
| Android phone or emulator | **Android 8.0 (API 26)** or later |
| Cloud setup only | A hosted Supabase project, Supabase CLI, and two Android devices with separate test accounts for the sync walkthrough |

## Quick start in offline mode

1. Get the repository source and open the project folder. It contains `gradlew`, `gradlew.bat`, `app/`, and `supabase/`.
2. Install [JDK 17 and the Android SDK](docs/setup-guide.md#1-install-the-android-tools). In Android Studio, open the project root and let it sync. For a terminal build, follow the guide to set `JAVA_HOME` and `ANDROID_HOME`.
3. From a fresh checkout, leave `cloud.properties` absent and keep the two Supabase environment variables unset. Run this command from the project root.

   ```sh
   ./gradlew assembleDebug
   ```

   In Windows PowerShell, run `.\gradlew.bat assembleDebug`. The first build downloads dependencies. Look for `BUILD SUCCESSFUL` and the file `app/build/outputs/apk/debug/app-debug.apk`.
4. [Install the APK on a phone](docs/setup-guide.md#10-install-on-an-android-phone), or run the `app` configuration from Android Studio. On first launch, SharedLedger opens the **Personal** budget with a **Cash** account in RSD. Try **Expense → Choose category → Save expense**. **Settings → Cloud sync** shows **Local mode** in an offline-only build.

## Set up shared budgets

Follow the [hosted setup walkthrough](docs/setup-guide.md#2-create-a-hosted-supabase-project) in order. It covers the project URL and publishable key, the CLI, the database migrations, the account-deletion function, email setup, Realtime, and the cloud-enabled APK.

- [Apply the migrations to the linked project](docs/setup-guide.md#5-apply-and-verify-the-database-migrations) if a CLI command tries to connect to `127.0.0.1:54322`.
- [Configure the Android build](docs/setup-guide.md#8-configure-the-android-app) if **Cloud sync** shows **Local mode**. Gradle reads `cloud.properties` at build time, so changing the file requires rebuilding and installing the APK.
- [Test with two devices](docs/setup-guide.md#11-create-and-join-a-shared-budget) using the same cloud-enabled build and two different accounts.

## Supabase Email Authentication and Android Deep Links

In the hosted Supabase Dashboard, open **Authentication → URL Configuration**. Replace `http://localhost:3000` with `sharedledger://auth/confirm` as the **Site URL**. On a phone, localhost points to the phone itself. Add both of these exact entries under **Redirect URLs** and save.

```text
sharedledger://auth/confirm
sharedledger://auth/recovery
```

Keep **Confirm email** enabled and keep the generated `{{ .ConfirmationURL }}` link in both the confirmation and recovery templates. SharedLedger supplies the matching redirect in each request. The default flow works without a website. Applying the database migrations does not update these Dashboard settings.

Register a disposable account from **Settings → Cloud sync → Create account**. Open the newest confirmation email on the same Android installation, wait for **Email verified**, then tap **Continue**. Test **Reset password** from the signed-out Cloud sync form. Open the newest recovery link on that phone, tap **Update password** after entering the new password, and sign in again. SharedLedger exchanges each one-time code using an encrypted verifier stored on the Android installation that requested the email.

An existing unconfirmed account can use **Resend confirmation email** from the Cloud sync sign-in form after entering its email. An `otp_expired` response can indicate a past expiry, a previously used or superseded link, or a mail scanner that opened the link first. Check whether the account is already confirmed and try signing in. If needed, request a fresh link and open the newest email on the requesting device. See the setup guide for invalid links, optional scanner protection, and verified HTTPS Android App Links. [Detailed email configuration and testing](docs/setup-guide.md#supabase-email-authentication-and-android-deep-links)

## Shared budget invitations

Create a **Shared** budget and let it sync. In **Settings → Cloud sync**, the owner chooses **Editor** or **Viewer** and taps **Generate invitation**. The app shows the code after Supabase saves the invitation. A recipient with a confirmed email and an active session on the same Supabase project can paste the raw code, link, or full Share message under **Join a shared budget**. One-use codes expire after 24 hours. If the budget download is interrupted after joining, **Sync now** resumes it without another code.

For an existing linked project, preview and apply the invitation migration before testing the new flow.

```sh
supabase db push --dry-run --remote
supabase db push --remote
supabase migration list --remote
```

Look for version `202611210001`. Existing unexpired, unused invitation codes still work. See the setup guide for error codes, diagnostics, and the two-device test. [Create and join a shared budget](docs/setup-guide.md#11-create-and-join-a-shared-budget) · [Verify invitation failures](docs/setup-guide.md#diagnose-invitation-failures)

## Import into a personal or shared budget

Use **Settings → Import and export** to merge JSON or CSV into a writable budget. The preview shows reused and new accounts and categories, and keeps the destination's existing history. Shared owners and editors can merge; viewers stay read-only. Deploy Supabase migration `202611210002` before syncing cloud imports. Use **Restore backup as local budgets** to recover the original archive structure. [Import and restore guide](docs/files.md)

## Record an actual bank conversion

When the transaction currency differs from the account currency, enter the original amount and edit the suggested debit or credit to match your bank statement. Select **Confirm actual bank amount** before saving. SharedLedger uses the latest confirmed rate for that account, currency pair, and direction on subsequent transactions. A backdated entry can use only rates from transactions on or before its date. The first conversion uses the applicable reference quote when available.

History shows the budget amount first, followed by distinct account and original amounts. **≈** marks estimated budget valuations. **Settings → Exchange rates** shows the latest confirmation and its date. [Bank conversion guide](docs/setup-guide.md#recording-bank-conversions)

## Set a budget valuation

When the transaction currency differs from the budget currency, edit either the rate or the budget amount directly in **Budget valuation**. For example, a 100 USD income at 114 RSD per USD adds 100 USD to the account and 11,400 RSD to the budget. Saving an edited valuation remembers its rate for that budget, account, currency pair, and income or expense direction. **Use reference rate** restores the applicable provider quote for the transaction. [Budget valuation guide](docs/setup-guide.md#setting-a-budget-valuation)

## Sync transaction deletions

Deleting an entry hides it immediately and queues the deletion. Supabase records a tombstone using the saved server amounts and relationships, and other devices hide the entry when they sync. Deploy pending database migrations through `202611220002` before syncing with this APK. Open **Settings → Cloud sync** to check pending and failed changes or select **Retry sync** after fixing a reported problem. [Database migration steps](docs/setup-guide.md#5-apply-and-verify-the-database-migrations) · [Deletion diagnostics and two-device checks](docs/verification.md#transaction-deletion-and-sync-recovery)

## Build artifacts and tests

| Build | Command from the repository root | Output |
| --- | --- | --- |
| Debug APK, signed with the build machine's debug key | `./gradlew assembleDebug` | `app/build/outputs/apk/debug/app-debug.apk` |
| Unsigned release APK | `./gradlew assembleRelease` | `app/build/outputs/apk/release/app-release-unsigned.apk` |
| Unsigned Android App Bundle | `./gradlew bundleRelease` | `app/build/outputs/bundle/release/app-release.aab` |

Use `.\gradlew.bat` instead of `./gradlew` in Windows PowerShell. For distribution, use [Android Studio's signing workflow](docs/setup-guide.md#9-build-the-apk) and keep the private signing key outside the repository.

Run the JVM tests and Android lint from the repository root.

```sh
./gradlew testDebugUnitTest lintDebug
```

With a running Android emulator or an authorized USB-connected phone, run the instrumented tests.

```sh
./gradlew connectedDebugAndroidTest
```

See [verification notes](docs/verification.md) for recorded results, the disposable database test, and the optional hosted test script.

## Project layout

| Path | Purpose |
| --- | --- |
| [`app/`](app/) | Native Android app, Gradle settings, Room schema snapshots, and tests |
| [`presentation/`](app/src/main/java/dev/sharedledger/presentation/) | Jetpack Compose screens, navigation, and view models |
| [`domain/`](app/src/main/java/dev/sharedledger/domain/) | Money calculations, dates, and reporting |
| [`data/`](app/src/main/java/dev/sharedledger/data/) | Room, ledger operations, exchange rates, and file import and export |
| [`sync/`](app/src/main/java/dev/sharedledger/sync/) | Supabase Auth, Realtime, the synchronization outbox, and WorkManager jobs |
| [`supabase/migrations/`](supabase/migrations/) | PostgreSQL schema, Row Level Security, and authenticated database functions |
| [`supabase/functions/`](supabase/functions/) | The `delete-account` Edge Function |
| [`docs/`](docs/) | Setup, data model, file formats, and verification |

The [data model and sync protocol](docs/schema.md) explain how local changes reach Supabase and how devices handle version conflicts. The setup guide includes [credential safety and backups](docs/setup-guide.md#14-security-and-backups) and a [troubleshooting checklist](docs/setup-guide.md#13-troubleshooting).
