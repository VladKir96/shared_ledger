# SharedLedger

SharedLedger is a native Android expense tracker for personal and shared budgets. It runs offline without an account and contains no advertising, purchases, or subscriptions.

## Build and run

Use JDK 17, Android SDK Platform 35 and Build Tools 35.0.0. Open this directory in Android Studio and run the `app` configuration on Android 8.0 or later. Android Studio can write your SDK location to `local.properties`. For terminal builds, set `JAVA_HOME` and `ANDROID_HOME` to your installations.

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Use `gradlew.bat` on Windows. The first build downloads the pinned Gradle distribution and project dependencies. With no cloud configuration, the app starts with an empty personal budget, a cash account in RSD and the default categories.

```sh
./gradlew testDebugUnitTest lintDebug
./gradlew connectedDebugAndroidTest
```

The connected suite needs an emulator or device. It covers Room migrations, startup exchange-rate refresh, offline conversion and retries, recurrence, JSON round trips, outbox staging, and core Compose interactions.

## Use a Supabase project

1. Create a dedicated Supabase project and install the [Supabase CLI](https://supabase.com/docs/guides/local-development/cli/getting-started).
2. Link the project and push the versioned migrations from the repository root. Use a dedicated test project for acceptance testing.
   
   ```sh
   supabase login
   supabase link --project-ref YOUR_PROJECT_REF
   supabase db push
   supabase functions deploy delete-account --no-verify-jwt
   ```
   
   The account deletion function verifies the caller with Supabase Auth and uses the service-role key inside the function runtime.
3. In **Authentication → URL Configuration**, add `sharedledger://auth/recovery` as an allowed redirect. Keep the email provider enabled and configure SMTP for delivery. A password reset must be requested and completed on the same device because the Android app stores the PKCE verifier locally. Keep the confirmation email configured to send users to a site that can tell them to return to the app and sign in.
4. Copy `cloud.properties.example` to `cloud.properties` and fill in the project HTTPS URL and **publishable key**. The same property names can be supplied as environment variables for CI. Never use a service-role key or server secret in an Android build.
5. Rebuild and install on two devices. Open **Settings → Cloud sync** to sign in. Create a shared budget under **Settings → Budgets** and use **Cloud sync** to generate an editor or viewer invitation. Share the code or deep link. The recipient can paste the code after signing in with a separate account.
6. To upload an existing personal budget, select the budget and choose **Enable sync for this budget** under **Cloud sync**. Back up any local data before testing account deletion.

The migration adds the budget table to the Supabase Realtime publication. Verify that the publication exists and that the table is enabled in the project. WorkManager and the Sync now action can still pull changes if Realtime is temporarily unavailable.

## Release artifacts

```sh
./gradlew assembleRelease bundleRelease
```

Outputs are in `app/build/outputs/apk/release/` and `app/build/outputs/bundle/release/`. For a distribution build, use Android Studio's **Generate Signed Bundle / APK** and supply your own upload key. Keep signing material outside the repository. The terminal commands above produce unsigned release outputs.

## Operating notes

- Amounts are entered in the original currency and rounded to currency minor units using half-even rounding. The editor automatically looks up the rate for the selected date and previews the account and budget amounts. Save can complete while a lookup is underway. Pending conversions are filled after a valid rate becomes available, while recorded rates and manual overrides are preserved.
- **Settings → Exchange rates** shows the provider, quote dates, and last successful refresh. Background updates are on by default. Turning them off pauses scheduled refreshes and background retries. The editor still looks up rates on demand, and **Refresh now** can run a refresh manually.
- A transfer moves balances between two accounts. For a cross-currency transfer, enter the amount received. Net cash flow and spending charts exclude transfers.
- Monthly and annual rules keep their original calendar anchor. A January 31 rule falls on the last valid day in February, then returns to March 31. Android can defer recurring background work. Open the app to trigger an immediate due check.
- JSON is the full-fidelity backup format. The [file format and import behavior](docs/files.md) describe how CSV imports are mapped.
- Numbers and dates follow the device format. The language picker changes translated interface labels independently. User-entered names, default category names, and some dynamic messages remain in English.
- The [data model and sync protocol](docs/schema.md) cover RLS, conflict handling, and local state. The [verification notes](docs/verification.md) describe what has been tested and what needs a deployed project.

Frankfurter v2 supplies daily and historical rates. The dated Currency API provides a fallback when Frankfurter has no usable quote. RSD, EUR, RUB, USD, GBP and CHF were checked against live provider responses. Historical coverage varies by pair and date. The editor displays the actual date and source of each quote. [Frankfurter API](https://frankfurter.dev/) · [Currency API](https://github.com/fawazahmed0/exchange-api)
# shared_ledger
