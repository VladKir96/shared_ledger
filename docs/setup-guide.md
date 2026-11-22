# Set up SharedLedger from source

This guide takes you from a fresh checkout to a shared budget on two Android devices. For personal use, complete [step 1](#1-install-the-android-tools), [step 9](#9-build-the-apk), and [step 10](#10-install-on-an-android-phone). For shared budgets, follow the guide in order.

| Stage | Steps |
| --- | --- |
| Set up your computer | [Java, Android Studio, and the SDK](#1-install-the-android-tools) |
| Set up Supabase | [Create a project](#2-create-a-hosted-supabase-project) · [Find credentials](#3-find-the-project-url-public-key-and-reference-id) · [Install and link the CLI](#4-install-the-cli-and-link-the-project) · [Apply migrations](#5-apply-and-verify-the-database-migrations) |
| Configure cloud services | [Edge Function](#6-deploy-the-account-deletion-function) · [Authentication, email, and recovery](#7-configure-authentication-and-realtime) |
| Put the app on your phone | [Configure the app](#8-configure-the-android-app) · [Build the APK](#9-build-the-apk) · [Install on Android](#10-install-on-an-android-phone) |
| Verify and maintain | [Two-device test](#11-create-and-join-a-shared-budget) · [Updates](#12-update-the-app-or-backend) · [Troubleshooting](#13-troubleshooting) · [Security and backups](#14-security-and-backups) |

**Where to run commands.** Use Terminal on macOS or Linux and PowerShell on Windows. After installing the tools, open a terminal in the repository root. That is the folder containing `gradlew`, `settings.gradle.kts`, and `supabase/config.toml`. Run `pwd` and `ls` on macOS or Linux, or `Get-Location` and `Get-ChildItem` on Windows, to check your location. Replace uppercase placeholders such as `YOUR_PROJECT_REF` before running examples.

## 1. Install the Android tools

### Install Android Studio and SDK packages

1. Download Android Studio for your operating system and processor. On Apple Silicon, choose the Mac build for Apple chips. Install it and complete the first-run setup wizard. [Android Studio installation](https://developer.android.com/studio/install)
2. In Android Studio, open **Tools → SDK Manager**. From the welcome screen, use **More Actions → SDK Manager**. Copy the **Android SDK Location** displayed at the top. Use that location in the terminal commands below.
3. Under **SDK Platforms**, select **Android 15 (API Level 35)**. Under **SDK Tools**, select **Android SDK Platform-Tools** and enable **Show Package Details** to select **Android SDK Build-Tools 35.0.0**. You can also select **Android SDK Command-line Tools (latest)** for `sdkmanager` and **Android Emulator** if you plan to use a virtual device.
4. Select **Apply** and accept the licenses when prompted. Open the repository root in Android Studio and wait for Gradle sync. Android Studio can write a machine-specific SDK path to `local.properties`. For terminal builds, set `ANDROID_HOME` to the same SDK directory. [SDK tools and licenses](https://developer.android.com/tools/sdkmanager)

SharedLedger uses JDK **17** for its Java and Kotlin build toolchain. Android Studio already includes a Java runtime, called JetBrains Runtime or JBR. If the bundled JDK is version 17 and includes `javac`, you can use it. If it has a different major version, install a JDK 17 toolchain for this project. Choose the **JDK** download for your operating system and processor. In Android Studio, set **Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK** to the JDK 17 installation. Use the same JDK in your terminal. [Android build JDKs](https://developer.android.com/build/jdks) · [Temurin JDK 17 installers](https://adoptium.net/temurin/releases/?version=17)

### macOS Terminal

If Android Studio is installed in `/Applications`, check its bundled JDK.

```sh
"/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java" -version
```

If it reports Java 17, run this line in Terminal.

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

If it reports another version or the file is missing, install a JDK 17 `.pkg`, choosing **aarch64** for Apple Silicon or **x64** for Intel. Then run this line instead.

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
```

For either JDK, run the checks below. Replace the SDK path if SDK Manager shows a different location.

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

java -version
javac -version
echo "$JAVA_HOME"
test -f "$ANDROID_HOME/platforms/android-35/android.jar" && echo "Platform 35 found"
test -d "$ANDROID_HOME/build-tools/35.0.0" && echo "Build Tools 35.0.0 found"
adb version
```

If `/usr/libexec/java_home` reports `Unable to locate a Java Runtime`, it could not find a system-installed JDK. Android Studio's bundled JDK can still be available at the path shown above. Point `JAVA_HOME` to that JDK if it is version 17, or install JDK 17 and run the checks again. For zsh, add the working export lines to `~/.zshrc` to keep them in new terminals.

### Windows PowerShell

Install the JDK 17 installer or extract the JDK 17 archive for your machine. Set `JAVA_HOME` to its installation directory. Replace the example directory below with the one containing `bin\javac.exe`. The default Android Studio SDK location is typically `%LOCALAPPDATA%\Android\Sdk`.

```powershell
$env:JAVA_HOME = 'C:\Path\To\Your\JDK-17'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"

java -version
javac -version
$env:JAVA_HOME
Test-Path "$env:ANDROID_HOME\platforms\android-35\android.jar"
Test-Path "$env:ANDROID_HOME\build-tools\35.0.0"
adb version
```

Expect Java and `javac` to report version 17, both `Test-Path` checks to print `True`, and `adb version` to print a Platform-Tools version. To persist the settings, add `JAVA_HOME` and `ANDROID_HOME` under Windows **Environment Variables** and add their `bin` and `platform-tools` directories to your user **Path**. Open a new PowerShell window afterward.

### Linux terminal

Install Android Studio and a JDK 17 package for your distribution, or extract a JDK 17 archive to a folder you control. Set `JAVA_HOME` to the extracted or installed JDK directory containing `bin/javac`. Android Studio commonly uses `$HOME/Android/Sdk` for the SDK. Use the path reported by SDK Manager.

```sh
export JAVA_HOME="/path/to/your/jdk-17"
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

java -version
javac -version
echo "$JAVA_HOME"
test -f "$ANDROID_HOME/platforms/android-35/android.jar" && echo "Platform 35 found"
test -d "$ANDROID_HOME/build-tools/35.0.0" && echo "Build Tools 35.0.0 found"
adb version
```

Add the working export lines to `~/.bashrc` or `~/.zshrc` as appropriate. The environment changes above apply immediately to the current terminal. [Android SDK environment variables](https://developer.android.com/tools/variables)

### Accept licenses from the terminal if needed

If the build reports an unaccepted SDK license, install **Android SDK Command-line Tools (latest)** from SDK Manager and run this command with the same SDK path as the build.

```sh
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses
```

In PowerShell, use `& "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" --licenses`. Read and accept the applicable licenses, then retry the build. The SDK Manager can also install the same packages and prompt for their licenses. [Android SDK Manager](https://developer.android.com/tools/sdkmanager)

## 2. Create a hosted Supabase project

Supabase provides the hosted PostgreSQL database, email/password authentication, HTTP APIs, Realtime events, and the account-deletion Edge Function. The Android app connects directly to Supabase, so the hosted setup needs no separately managed application server. Android handles email confirmation and password recovery directly. A static confirmation page is available for deployments whose mail security systems pre-open one-time links.

1. Open the [Supabase Dashboard](https://supabase.com/dashboard) and sign in or create a Supabase administrator account. This is separate from the two user accounts you will create inside SharedLedger.
2. Select or create an organization. Choose the **Free** plan if offered and suitable for your test project. Check the limits and billing options shown for your organization. [Supabase plans](https://supabase.com/pricing)
3. Choose **New project** and name it, for example **SharedLedger Test**. Use the password generator to create a database password and store it in a password manager. The CLI may need it to access PostgreSQL.
4. Choose an available region close to the people who will use the app. Create the project and wait for provisioning to finish. Open the project and check that **Table Editor** and the project **Connect** dialog load. [Create a Supabase project](https://supabase.com/docs/guides/getting-started)

Use a dedicated test project for the first deployment and two-device walkthrough. If you already have a project, select it and continue to step 3.

## 3. Find the project URL, public key, and reference ID

1. In your project's Dashboard, open **Connect** and copy the **Project URL** and **Publishable key**. You can also find publishable keys under **Project Settings → API Keys**. Keep the project URL as the HTTPS origin, such as `https://your-project-ref.supabase.co`. The app appends its API paths. [Supabase API keys](https://supabase.com/docs/guides/getting-started/api-keys)
2. Find the **Project ID** in the project's general settings. This is the **project ref**. For a standard project URL it is the part before `.supabase.co`. You can also match the ID against `supabase projects list` after logging in. The ref goes into the CLI link command. The full HTTPS URL goes into the Android build.
3. Keep the URL and key from the same project. The examples in this guide are placeholders.

| Value | Use |
| --- | --- |
| Project URL | HTTPS gateway the Android app calls |
| Publishable key, starting with `sb_publishable_` | Public app identifier. Use this as `SUPABASE_PUBLISHABLE_KEY`. User access is controlled by signed-in sessions and Row Level Security. |
| Legacy `anon` key | Older public JWT containing the `anon` role. The current Android build accepts it in the same field. Use a publishable key for new setups. |
| Secret key, starting with `sb_secret_` | Privileged backend API key that bypasses RLS |
| Legacy `service_role` key | Privileged server-side JWT used by the account-deletion function in its hosted runtime |
| Database password | PostgreSQL administrator credential, used for management or migrations when prompted |
| Supabase personal access token | Management credential for the CLI or deployment automation |

**Keep secret keys, service-role keys, database passwords, CLI access tokens, and SMTP credentials out of APKs.** The Android build needs only the HTTPS origin and the public client key.

## 4. Install the CLI and link the project

### Install the Supabase CLI

On macOS with [Homebrew](https://brew.sh/), run this in Terminal.

```sh
brew install supabase/tap/supabase
supabase --version
```

On Windows with [Scoop](https://scoop.sh/), run these commands in PowerShell.

```powershell
scoop bucket add supabase https://github.com/supabase/scoop-bucket.git
scoop install supabase
supabase --version
```

On Linux, use the same Homebrew formula or install the stable package for your architecture from the [official CLI releases](https://github.com/supabase/cli/releases). For example, on Debian or Ubuntu download the `.deb`, change to the download folder, and run `sudo dpkg -i ./DOWNLOADED_PACKAGE.deb` with the actual filename. Confirm the installation with `supabase --version`.

The version command should print a CLI version number. This guide's hosted commands were checked with **CLI 3.2.0**. If you run the CLI through `npx`, Supabase requires Node.js 20 or later. In that case, prefix the CLI examples with `npx`, for example `npx supabase --version`. [Official Supabase CLI installation guide](https://supabase.com/docs/guides/local-development/cli/getting-started)

### Sign in and link

1. From the repository root, run `supabase login`. Follow the browser authorization, then return to the terminal if it prompts for a verification code. On a headless or CI machine, supply a Supabase personal access token through the secret environment variable `SUPABASE_ACCESS_TOKEN`. This is your CLI credential. The Android publishable key serves a different purpose.
2. Link the checkout using the project ref from step 3. Then list the projects visible to the CLI.

   ```sh
   supabase link --project-ref YOUR_PROJECT_REF
   supabase projects list
   ```

3. A successful link reports that the checkout is linked. In the project list, compare the linked marker and project ID with the Dashboard before applying migrations. Linking selects a target without applying the repository's migrations. If the project is missing, sign in to the correct Supabase account or ask the project owner for the necessary access. [CLI command targets](https://supabase.com/docs/guides/local-development/local-and-hosted-projects)

## 5. Apply and verify the database migrations

A migration is a versioned SQL file that creates or updates the PostgreSQL schema. The files in [`supabase/migrations/`](../supabase/migrations/) are applied in this order.

| Migration | What it sets up |
| --- | --- |
| [`202611200001_ledger.sql`](../supabase/migrations/202611200001_ledger.sql) | Ledger tables, indexes, constraints, signup profile trigger, Row Level Security, and publishing `public.budgets` to `supabase_realtime` if that publication exists |
| [`202611200002_api.sql`](../supabase/migrations/202611200002_api.sql) | Database functions for budget snapshots and writes, version checks, invitations, and membership changes |
| [`202611210001_invitation_redemption.sql`](../supabase/migrations/202611210001_invitation_redemption.sql) | Authenticated invitation creation and redemption, specific error codes, and receipts for safe retries |
| [`202611210002_budget_import.sql`](../supabase/migrations/202611210002_budget_import.sql) | Create-only shared import batches for owners and editors |
| [`202611210003_bank_conversion.sql`](../supabase/migrations/202611210003_bank_conversion.sql) | Optional bank conversion metadata and protection for confirmed amounts during older-client edits |
| [`202611220001_transaction_tombstones.sql`](../supabase/migrations/202611220001_transaction_tombstones.sql) | Canonical exchange-rate field names and versioned deletion that preserves server-side values |
| [`202611220002_budget_valuation.sql`](../supabase/migrations/202611220002_budget_valuation.sql) | Budget valuation confirmation timestamp and compatibility with edits from older clients |

1. From the repository root, preview the hosted migrations and check that the `Target` line names the intended project. A fresh project should list all seven files above. If you already deployed the transaction deletion migration, only `202611220002_budget_valuation.sql` should be pending.

   ```sh
   supabase db push --dry-run --remote
   ```

2. After checking the target and file list, apply the migrations. Enter the database password only if prompted, and confirm after reviewing the proposed changes.

   ```sh
   supabase db push --remote
   supabase migration list --remote
   ```

3. Check that all seven migration versions through `202611220002` appear in both the **Local** and **Remote** columns. In the Supabase Dashboard for that project, open **Table Editor**, select the **public** schema, and look for `profiles`, `budgets`, `budget_memberships`, `accounts`, `categories`, `transactions`, `recurring_rules`, `exchange_rate_cache`, and `invitations`. Empty tables are expected before users and budgets are created.

### If the CLI tries to connect to `127.0.0.1:54322`

The error can look like this.

```text
Target: local stack (127.0.0.1:54322)
failed to connect to postgres
connect ECONNREFUSED 127.0.0.1:54322

Make sure Docker is running, then run: supabase start
To use the linked project instead, pass --remote.
```

The CLI attempted to contact a **local database** on your computer. With CLI 3.x, linking alone leaves `db push` and `migration list` targeting local by default. Use the commands above with `--remote`. If `supabase db push --help` lacks `--remote` and lists `--linked`, use `--linked` for the preview, push, and migration list with that older CLI, or upgrade to the current CLI. [CLI v3 migration guide](https://supabase.com/docs/guides/local-development/cli/migrating-to-v3)

A hosted push connects to the project's database over the network and works without Docker. `supabase start` is for the optional local stack, which uses a Docker-compatible runtime in the standard setup. Database migrations create tables and SQL functions. The next step deploys the separate server-side Edge Function. [Supabase CLI target reference](https://supabase.com/docs/guides/local-development/local-and-hosted-projects)

## 6. Deploy the account-deletion function

The only Edge Function in this repository is [`delete-account`](../supabase/functions/delete-account/index.ts). From the linked repository root, deploy it using server-side bundling, then list deployed functions.

```sh
supabase functions deploy delete-account --no-verify-jwt --use-api
supabase functions list
```

Check that `delete-account` appears in the CLI list and under **Edge Functions** in the same Dashboard project. The CLI should report a successful deployment. `--use-api` bundles the function on Supabase's servers, so this command works without Docker. [Deploying Edge Functions](https://supabase.com/docs/guides/functions/deploy)

For this specific function, `--no-verify-jwt` skips the gateway's JWT verification step. The [checked-in Supabase configuration](../supabase/config.toml) sets the same option with `verify_jwt = false`. The function accepts POST, requires a bearer token, validates it through `auth.getUser(access)`, and uses the verified user's ID for deletion. It returns HTTP 401 if caller authentication fails. It uses the hosted runtime's `SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY`. The hosted runtime provides these variables by default. This implementation requires the legacy service-role variable to remain available there. Android uses the same public project URL with its publishable key. Keep gateway verification settings appropriate to each other function's own authentication implementation. [Edge Function environment variables](https://supabase.com/docs/guides/functions/secrets)

Verify deployment through the dashboard list and function logs. An end-to-end deletion test permanently removes the account and the cloud budgets it owns, including shared history. Use a disposable test account and test budgets for that check.

## 7. Configure authentication and Realtime

### Enable email/password sign-in and signup

1. Open **Authentication → Sign In / Providers → Email** in the Dashboard. Enable the **Email** provider. In **Sign In / Providers**, also allow new user signups if users should register from the app. Set an eight-character minimum or the stronger policy you use for your project. The app requires at least eight characters.
2. Keep **Confirm email** enabled for real users. For a short sync-only test in a disposable project, you can temporarily turn it off before creating fresh test users. Signup can then sign the user in immediately. Turn confirmations back on before testing email delivery or inviting real users. Password recovery still requires working email delivery.
3. Check **Authentication → Users** after registering from the Android app. A successful signup creates a user. With confirmation enabled, confirm the email and verify the user's confirmed status before signing in. [Supabase authentication settings](https://supabase.com/docs/guides/auth/general-configuration) · [Testing hosted email flows](https://supabase.com/docs/guides/auth/testing-auth-flows)

### Supabase Email Authentication and Android Deep Links

#### Configure the hosted Dashboard

1. Open **Authentication → URL Configuration**. Set **Site URL** to `sharedledger://auth/confirm` and save. Supabase supports custom URI schemes for native apps. The Site URL is a fallback when the email request omits a redirect or the supplied URI is rejected. `http://localhost:3000` points to the device opening the link. Use the Android URI for this Android-only project. [Supabase mobile redirect URLs](https://supabase.com/docs/guides/auth/redirect-urls#mobile-deep-linking-uris)
2. Under **Redirect URLs** or **Additional Redirect URLs**, add these two exact entries and save. SharedLedger explicitly sends the confirmation URI for signup and resend, and the recovery URI for password resets.

   ```text
   sharedledger://auth/confirm
   sharedledger://auth/recovery
   ```

3. Open **Authentication → Emails → Templates** and inspect **Confirm signup** and **Reset password**. Leave the default templates in place when they use the generated `{{ .ConfirmationURL }}`. If you previously customized them to point at localhost, restore this link in **Confirm signup**.

   ```html
   <a href="{{ .ConfirmationURL }}">Confirm email</a>
   ```

   Use the following link in **Reset password** if its template was customized.

   ```html
   <a href="{{ .ConfirmationURL }}">Reset password</a>
   ```

   Supabase sends an HTTPS verification link. After validating it, Supabase redirects to Android with a short-lived PKCE code. The app exchanges that code with the verifier it saved on the requesting installation, then verifies the confirmed email on the returned account. A shared-budget invitation uses `sharedledger://invite/<code>` and needs no entry in Supabase Auth. [Supabase email templates](https://supabase.com/docs/guides/auth/auth-email-templates) · [PKCE flow](https://supabase.com/docs/guides/auth/sessions/pkce-flow)
4. Install the rebuilt cloud-enabled APK. The repository changes update the Android handlers and local `supabase/config.toml`. The hosted URL settings, email settings, and any template edits are manual Dashboard changes. Database migrations leave them untouched.

#### Test signup, resend, and recovery

1. On Android, open **Settings → Cloud sync → Create account**, enter a disposable email address and password, and submit. Look for the registration message and open the newest confirmation in that inbox on the **same Android device and app installation**. If Android asks which app to open, choose SharedLedger. Wait for **Email verified**, then tap **Continue**. Check that **Cloud sync** shows the account and that **Authentication → Users** lists it as confirmed. If a different account was already active, SharedLedger keeps it signed in. Switch accounts from **Cloud sync** if needed.
2. Test an existing unconfirmed account by opening the **Sign in** form, entering its email, and tapping **Resend confirmation email**. The resend works for an account originally registered in an older app build. For each email flow, the app waits at least 60 seconds before allowing another request for that address and observes Supabase rate-limit responses. If the older link was already consumed and the account is confirmed, sign in.
3. On the **Cloud sync** sign-in form, enter the confirmed account's email and tap **Reset password**. Open the newest recovery email on the device that requested it. Wait for the **Reset password** form, enter a new password, and tap **Update password**. After **Password updated**, sign in with the new password. A successful reset signs out the matching local account if it was active. A different active account is kept.
4. Replay a consumed link and check that the app explains it is expired or used. Repeat with SharedLedger running and with it in the background. In a separate test, use the app without signing in and save a local expense.

Use the newest link on the same installation. A new request replaces the verifier for that email flow, and reinstalling or clearing the app removes it. If you see `otp_expired`, the token might have expired, been replaced or used, or been opened by an email security scanner. If a confirmation link reached localhost, check both the Site URL and the redirect allowlist. Check the user's status in the Dashboard before trying another confirmation. For invalid links, avoid forwarding the email or copying only part of the link. Request a fresh confirmation or recovery email and open it on the requesting device.

#### Optional confirmation page for email scanners

Previews and security scanners sometimes request the Supabase HTTPS link before the recipient clicks it. Disable automatic link tracking with your SMTP provider if it rewrites authentication links. If automated previews keep consuming links, use the included [confirmation page](auth-email-interstitial.html). It waits for a button click before following the original Supabase verification URL. This mitigates automated previews that follow links or load pages. A scanner that also clicks the button can still consume a token. [Supabase email scanner guidance](https://supabase.com/docs/guides/auth/auth-email-templates)

1. Set `data-project-url` at the top of the HTML file to the same HTTPS **Project URL** used by your Android build. Deploy the file to a real HTTPS address you control. Use static hosting without analytics or query-string access logging on this page, because the incoming URL contains the one-time link.
2. In **Confirm signup**, use the following link. Substitute the real public URL of the deployed HTML file for `YOUR_DEPLOYED_PAGE_URL`.

   ```html
   <a href="YOUR_DEPLOYED_PAGE_URL?confirmation_url={{ .ConfirmationURL }}">Confirm email</a>
   ```

3. If the same issue affects recovery, use this link in **Reset password** with the same public URL.

   ```html
   <a href="YOUR_DEPLOYED_PAGE_URL?confirmation_url={{ .ConfirmationURL }}">Reset password</a>
   ```

4. Request fresh email and verify that loading the page leaves the Supabase verification link unused. Tap the button on the requesting Android device to complete the same native PKCE flow. Keep the two Android redirects in the Dashboard. For deployments whose email clients block custom-scheme navigation, try opening the link in the device's external browser.

Android custom URI schemes are unverified. Another installed app can register the same scheme and intercept or block a redirect. PKCE requires the verifier from the requesting SharedLedger installation to redeem the code. For production, consider moving the redirect endpoints to verified HTTPS Android App Links. That requires a domain you control, an Android manifest update, a Digital Asset Links file with the production signing fingerprint, and matching HTTPS redirect entries in Supabase. [Android App Links verification](https://developer.android.com/training/app-links/verify-android-applinks)

### Configure SMTP

1. For ordinary user inboxes, choose an SMTP provider and verify a sender address or domain with it. Copy the provider's SMTP host, port, username, password, and sender details. The provider will specify any required DNS records.
2. In the Dashboard's **Authentication** area, open **Custom SMTP** or the **SMTP Settings** tab under **Emails**. Turn on custom SMTP, enter the provider settings, and save. Keep these credentials in Supabase and your password manager. Review **Authentication → Rate Limits** for your expected usage.
3. Register a test user with confirmation enabled. Check that the email reaches the intended inbox and that its link confirms the account. Then run the on-device recovery test above. If messages fail, check Auth logs in Supabase and delivery logs at your SMTP provider.
4. The built-in Supabase email service is suitable for limited testing. It only sends to addresses on the project team and has a low send limit. Check the current limits before testing, especially if you plan to send two confirmations plus a recovery email. [Supabase custom SMTP](https://supabase.com/docs/guides/auth/auth-smtp)

The local `supabase/config.toml` includes email confirmation and both Android authentication redirects for local development. Applying the database migrations does not copy those Auth settings to the hosted Dashboard. Configure and verify them in the hosted project as described above.

### Verify Realtime for `public.budgets`

1. In the hosted Dashboard, open **Database → Publications** and locate `supabase_realtime`. Check that `public.budgets` is enabled. The first migration adds this table automatically if the publication exists. If it is absent from the publication, enable that table in the Dashboard and check again. SharedLedger only subscribes to `public.budgets`. [Supabase Realtime publication troubleshooting](https://supabase.com/kb/troubleshooting/realtime-postgres-changes-troubleshooting)
2. Optionally confirm publication membership in the Dashboard **SQL Editor** using this read-only query.

   ```sql
   select schemaname, tablename
   from pg_publication_tables
   where pubname = 'supabase_realtime'
     and schemaname = 'public'
     and tablename = 'budgets';
   ```

   Expect one row with `public` and `budgets`.
3. Realtime events tell the app to schedule a sync. The client still pulls authorized data and pushes queued changes through its API. **Sync now**, returning to the app, and network-constrained WorkManager jobs can sync when the WebSocket is unavailable. The periodic job requests a 15-minute interval. Android controls when it actually runs, so allow for delays and use **Sync now** for the walkthrough.

## 8. Configure the Android app

1. In the repository root, check for an existing `cloud.properties`. If it already exists, edit it directly to preserve your configuration. For a new file on macOS or Linux, run the following. On Windows PowerShell, use `Copy-Item cloud.properties.example cloud.properties`.

   ```sh
   cp cloud.properties.example cloud.properties
   ```

2. Open the new file in a text editor and replace both placeholders. Use the Project URL and **Publishable key** from step 3. Enter plain values without quotation marks. The file must sit next to `gradlew`, outside `app/`.

   ```properties
   SUPABASE_URL=https://your-project-ref.supabase.co
   SUPABASE_PUBLISHABLE_KEY=sb_publishable_replace_me
   ```

3. [`app/build.gradle.kts`](../app/build.gradle.kts) reads Java properties from the repository root and embeds them in Android `BuildConfig`. Environment variables of the same names take precedence, including variables set by a build runner. Build and reinstall whenever you change the URL or key. [`.gitignore`](../.gitignore) excludes `cloud.properties` so local project settings stay out of commits.
4. After building, open **Settings → Cloud sync** in the installed app. A **Sign in** form or an existing signed-in session confirms the build has an HTTPS URL and a nonempty key. **Local mode** means the build has missing or unusable configuration. To check which values were built in, privately inspect `app/build/generated/source/buildConfig/debug/dev/sharedledger/BuildConfig.java` after a debug build. Confirm the selected project by signing in with its test account.

For CI, supply the two values as environment variables on the build step. This is a GitHub Actions example for a Linux runner after checkout and JDK 17 and Android SDK installation. Add those named values to its secret store and avoid printing them in build logs.

```yaml
- name: Build the cloud-enabled debug APK
  env:
    SUPABASE_URL: ${{ secrets.SUPABASE_URL }}
    SUPABASE_PUBLISHABLE_KEY: ${{ secrets.SUPABASE_PUBLISHABLE_KEY }}
  run: |
    test -n "$SUPABASE_URL"
    test -n "$SUPABASE_PUBLISHABLE_KEY"
    ./gradlew assembleDebug
```

## 9. Build the APK

1. From the repository root, run `./gradlew --version` on macOS or Linux, or `.\gradlew.bat --version` on Windows. The wrapper downloads its pinned distribution on the first run. Expect **Gradle 8.11.1** and check that the JVM version is 17 when using the terminal setup above.
2. Build and verify the debug APK.

   **macOS and Linux**

   ```sh
   ./gradlew assembleDebug
   ls -lh app/build/outputs/apk/debug/app-debug.apk
   ```

   **Windows PowerShell**

   ```powershell
   .\gradlew.bat assembleDebug
   Get-Item app/build/outputs/apk/debug/app-debug.apk
   ```

3. Look for `BUILD SUCCESSFUL` and a nonempty `app/build/outputs/apk/debug/app-debug.apk`. The debug APK is signed automatically with the build machine's debug key and is ready to install. If the command fails, read the first error under `What went wrong` and use [Troubleshooting](#13-troubleshooting). To rebuild all generated artifacts after correcting a setup issue, use `./gradlew clean assembleDebug` or `.\gradlew.bat clean assembleDebug`. Gradle clean clears build outputs on your computer. It leaves the installed app's data on your device.
4. A previously generated or downloaded APK contains the two values used at build time. For your own Supabase project, use the APK produced after completing step 8.

For distribution builds, `./gradlew assembleRelease bundleRelease` (Windows `.\gradlew.bat assembleRelease bundleRelease`) produces `app/build/outputs/apk/release/app-release-unsigned.apk` and `app/build/outputs/bundle/release/app-release.aab`. A release APK must be signed to install. An AAB is a publishing format that a store or bundle tool converts into device APKs. Use **Build → Generate Signed Bundle / APK** in Android Studio to produce signed distributable artifacts. Keep the signing key and password in secure storage for later updates. [Android app signing](https://developer.android.com/studio/publish/app-signing)

To run JVM tests and lint, use `./gradlew testDebugUnitTest lintDebug`. For instrumented tests, run `./gradlew connectedDebugAndroidTest` with an emulator or a connected phone listed by `adb devices`. Use `.\gradlew.bat` on Windows. See [the verification notes](verification.md) for the expected coverage and additional database tests.

## 10. Install on an Android phone

### Method A — Open the APK on your phone

1. Find `app/build/outputs/apk/debug/app-debug.apk` on your computer and transfer it to your phone using a trusted file-transfer method, such as USB File transfer. Put it somewhere easy to find, such as **Downloads**.
2. Open the APK from your phone's file manager. When Android prompts you, allow **Install unknown apps** or **Allow from this source** for that file manager or browser, then return to the installer. Use an APK that you built or obtained from a source you trust. Device policy and menu labels vary by Android version and manufacturer.
3. Tap **Install**, then **Open**. SharedLedger should show **Home**. For a cloud-enabled APK, continue to **Settings → Cloud sync**.

### Method B — Install with ADB

1. On the phone, open **Settings → About phone** and tap **Build number** seven times to enable **Developer options**. On some devices, **Build number** is in **Software information**. Open **Developer options** and enable **USB debugging**. Connect the phone with a data cable, unlock it, and approve this computer's debugging request on the phone. [Run apps on a hardware device](https://developer.android.com/studio/run/device)
2. From the repository root on the computer, run the following. The phone should appear with state `device` before you install.

   ```sh
   adb devices
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

3. ADB should print `Success`. The `-r` flag reinstalls over a compatible existing package while keeping its data. Android checks that the package ID and signing identity match and that the installed app can be updated by this build. If two devices are connected, use `adb -s DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk` for each serial shown by `adb devices`. See [Troubleshooting](#13-troubleshooting) for signature, SDK, and connection errors. [ADB command reference](https://developer.android.com/tools/adb)

## 11. Create and join a shared budget

Use the **same cloud-enabled APK** on both phones, with separate email accounts for Users A and B. These are app users. They do not need Supabase Dashboard access. Use a test budget with base currency **RSD**, the default **Cash** account, and today's date so exchange rates and period filters stay out of this sync test.

### Device A — Register, create a budget, and share it

1. Open **Settings → Cloud sync** and tap **Create account**. Fill in **Display name**, **Email**, and **Password**, then tap **Create account** in the form. If confirmation is enabled, open the newest email link on that phone and tap **Continue** after **Email verified**. With confirmation off, signup may sign you in immediately. Successful sign-in shows your email and **Sync now**.
2. Open **Settings → Budgets → Create budget**. Name it **Sync Test**, choose **Shared** under **Type**, set **Base currency** to **RSD**, leave **First account (optional)** set to **Cash**, and tap **Save**. On **Home**, check that **Sync Test** is selected in **Budget**, or use **Settings → Budgets → Switch** to select it.
3. On **Home**, tap **Expense**, enter **100**, tap **Choose category**, select **Groceries**, and tap **Save expense**. Go to **Settings → Cloud sync**, tap **Sync now**, and check that the expense is no longer queued.
4. In the **Sync Test members** section of **Cloud sync**, choose **Editor** under **Invite as** and tap **Generate invitation**. A Viewer can read records and cannot add an expense, so use Editor for this walkthrough. When **Invitation ready** appears, use **Copy code** or **Share** to send it to Device B. Supabase has saved the invitation at this point. Each code is one-use and expires after 24 hours.

### Device B — Join and write back

1. Complete email confirmation as User B and check that **Settings → Cloud sync** displays B's email. A user listed under **Authentication → Users** can still have an unconfirmed email or be signed out. Under **Join a shared budget**, paste the code, link, or full Share message and tap **Join budget**. You can also open the invitation link while signed in.
2. The app opens **Sync Test** after downloading it. Open **Transactions** and verify that A's **100 RSD** expense appears. Check that B has the **Editor** role in **Cloud sync**. If the app reports that the membership was confirmed but the download is pending, reconnect and tap **Sync now**. The app can also complete the download through its scheduled retry.
3. Record another **50 RSD** expense on Device B and tap **Sync now**. On Device A, verify that it appears in **Transactions**, using **Sync now** as needed. With the same date period and account filters, both **Home** screens should show **150 RSD** in expenses.

### Check offline queueing and recovery

1. Once both devices show **150 RSD**, turn on airplane mode on Device A and ensure Wi-Fi is off. On A, add a **25 RSD** expense. It should immediately show in A's **Transactions** and raise A's expenses to **175 RSD**.
2. View **Settings → Cloud sync**. Its count shows queued changes and conflicts. Device B should still show **150 RSD** while A is offline.
3. Reconnect A. Allow background sync to run, or tap **Sync now** on A and then on B. Both devices should show **175 RSD** and the three test expenses exactly once.
4. For a separate Realtime check, leave Device B open and online while A saves another test transaction. Confirm that B updates without **Sync now**. If manual sync works but that update is delayed, [check the publication and Realtime logs](#13-troubleshooting).

| Cloud sync display | What to check |
| --- | --- |
| **Up to date**, **0 queued**, and **0 conflicts** | Compare the transaction rows on both devices to confirm delivery. |
| **Checking for changes…** | A sync is running. Wait for the result before rechecking. |
| **N queued** | Unsynced edits remain on this device. Check connectivity and tap **Sync now**. |
| An error message or **Sync interrupted. Local changes are queued.** | Read the status, check the connection and account, and retry. |
| **N conflicts** | Open **Compare changes** and choose the appropriate version. Verify it on both devices after syncing. |

To connect an existing local personal budget to your own account, select it and use **Settings → Cloud sync → Enable sync for this budget**. This uploads that personal budget for your signed-in devices. For an invitation-based shared budget, use **Type → Shared** when you create it.

### Diagnose invitation failures

| Outcome | What it means and how to check |
| --- | --- |
| `INVITATION_NOT_FOUND` | The code has the wrong format or Supabase has no matching invitation in this project. Verify both APKs use the same project URL. Paste the exact code, link, or full Share message. The older app could append the repeated code label to the token when pasting the full message. |
| `INVITATION_EXPIRED` | The 24-hour window ended. Ask the owner for a fresh invitation. |
| `INVITATION_ALREADY_USED` | The invitation has reached its use limit. If B already joined, check for B's membership before requesting another invitation. |
| `INVITATION_REVOKED` | The owner deactivated this invitation. Ask the owner for a fresh invitation. |
| `ALREADY_MEMBER` | The recipient already has active access. The app opens the existing budget after syncing. |
| `AUTH_REQUIRED` | Sign in under **Cloud sync**. Check that the account email is visible before retrying. |
| `EMAIL_NOT_CONFIRMED` | Open the confirmation email or request a new one from **Cloud sync**. |
| `PERMISSION_DENIED` | Check the role and permissions. Only the owner of an active shared budget can create invitations. |
| `BUDGET_NOT_SYNCED` | The app has no uploaded shared budget for this invitation. Create a **Shared** budget or complete its first synchronization. |
| `NETWORK_ERROR` | Check connectivity and try again. If the first request created the membership, a retry opens it. |
| `SERVER_ERROR` | Verify project configuration and that migration `202611210001` is applied. Check PostgREST and PostgreSQL logs. |
| `RATE_LIMITED` | Wait for the corresponding window to reset. Supabase limits new attempts to 12 per user per 15 minutes and 20 invitations per budget per hour. A retry of an already recorded redemption can still open the active membership. |

On Android, filter Logcat by `SharedLedgerInvite` or run `adb logcat -s SharedLedgerInvite:I`. It shows the RPC, session or refresh state, structured outcome, HTTP or RPC error code, and snapshot result. Do not paste tokens, passwords, or key values into logs or bug reports.

In the Supabase Dashboard, open **Logs Explorer** and check **Auth** for sign-in or token refresh, **PostgREST** for `ledger_invite` and `ledger_redeem`, and **Postgres** for errors at the same time. An RPC can return HTTP 200 with a business outcome such as `INVITATION_EXPIRED`; use the app's outcome log to identify it. For synchronization without **Sync now**, check the **Realtime** logs and that `public.budgets` is in the `supabase_realtime` publication.

To verify actual membership, get B's UUID from **Authentication → Users** and the budget UUID from **Table Editor → budgets**. Run the following read-only query in the Dashboard **SQL Editor**, after substituting both UUIDs.

```sql
select user_id, email_confirmed_at, m.role, m.active
from public.budget_memberships m
join auth.users u on u.id = m.user_id
where m.budget_id = 'YOUR_BUDGET_UUID'::uuid and m.user_id = 'USER_B_UUID'::uuid;
```

Look for one active membership with the intended role and a confirmation timestamp. The timestamp verifies email confirmation. A separate sign-in or refresh check verifies the Android session. The owner can check the invitation's expiry, revocation, and use count in **Cloud sync** or **Table Editor → invitations**. The database stores the code as a hash.

### Read transaction group totals

In **Transactions**, choose a date range and select **Group by → Date, Category, Account, or Payer**. Each header shows the count and totals for the rows that match the current filters, including search. Tap the header to collapse the rows and keep the totals visible.

Mixed groups show income and expenses separately in the selected budget currency, using the valuations saved on each transaction. **≈** marks an estimated valuation. **Partial total** indicates that one or more transactions still need a budget conversion.

Transfers count as transactions and are excluded from income and expenses. A transfer-only group shows the amount sent in each source currency. Account groups show filtered activity for the source account. For account balances, use **Settings → Accounts**.

### Recording bank conversions

1. Add an expense or income. Select the account and then the original transaction currency. A USD account and an RSD purchase open the two-amount editor.
2. Enter the original amount with the keyboard or calculator. The editor uses the last confirmed rate for that account, pair, and direction. If none applies, it can use a reference quote or dated cache. Enter the figure from your bank and select **Confirm actual bank amount**.
3. For 2,500 RSD and a debit of 21.50 USD, the editor shows **1 USD = 116.2791 RSD**. Tap **Swap rate direction** to see USD per RSD. **Keep account amount when changing transaction amount** holds the amount you typed while you correct the original.
4. Use the same steps for RUB income credited to USD. The account receives the exact confirmed USD credit. An RSD budget uses its independent dated RUB-to-RSD valuation. If the bank first credits a RUB account and you exchange the funds later, record the conversion as a transfer.
5. If the bank posts a separate fee, open the saved transaction and select **Record separate bank fee**. Enter the separate fee in the account currency. A fee already included in the debit contributes to the displayed effective bank rate.

Save a suggested amount as an estimate while the actual amount is unavailable. Open the transaction later to enter and confirm the final debit or credit. When offline, SharedLedger still uses the last applicable bank rate and can fall back to a dated cached quote. If no usable quote exists, the conversion remains pending.

History shows the budget currency first with **≈** on estimated valuations. Distinct account and original currencies appear below it. Transaction details show each amount, rate, and source. Account balances use the native account currency.

In **Settings → Exchange rates**, select an account, pair, and direction to see the latest confirmed rate and its date. A backdated entry uses confirmations from transactions dated on or before the selected day. Correcting or deleting the source transaction changes future suggestions. Saved amounts and rates on other transactions remain intact. [Storage and sync policy](schema.md#bank-conversion)

### Setting a budget valuation

1. Add or edit an income or expense. For a 100 USD income to a USD account in an RSD budget, enter **100 USD**.
2. In **Budget valuation**, enter **114** in the RSD-per-USD rate field or **11,400 RSD** in the amount field using `11400`. Editing either field recalculates the other. Changing the original amount uses the selected rate to recalculate the budget amount.
3. Save to confirm the edited valuation. A later 200 USD income on the same account can reuse 114 and show **22,800 RSD**, including offline. Expenses have their own rate history. Use **Use reference rate** to replace a valuation with the applicable provider quote.

If the original amount is already in the budget currency, the budget uses that amount directly. When the account has a third currency, the bank debit or credit and the budget valuation can be edited independently. Transaction details show the original amount, account amount, budget rate, rate date, and source. Deploy `202611220002` to share the new confirmations across devices. [Storage and sync policy](schema.md#budget-valuation)

### Recover a failed transaction deletion

Apply the migrations through `202611220001` before retrying shared-budget deletions. Install the APK over the existing app, sign in to the same account, and open **Settings → Cloud sync**. Deletions queued by an older build use the corrected format on the first sync. If an earlier attempt is marked failed in the new app, choose **Retry sync** after the database migration or permission issue is resolved. The screen shows pending and failed counts and the last successful sync.

If a budget lost editing access, the owner must restore it before the affected user can retry. A pending deletion stays hidden on the device where it was made. When the server accepts it, the deletion also appears on other devices after they sync. [Diagnostics and two-device checks](verification.md#transaction-deletion-and-sync-recovery)

## 12. Update the app or backend

1. Back up the budgets you need from **Settings → Import and export** before changing devices, switching backend projects, or replacing an installation with an APK signed by a different key. Sync any queued changes while the original project remains accessible.
2. Update the source from your repository. If it contains new Supabase migrations, verify the linked project again and review `supabase db push --dry-run --remote` before applying. If the function source changes, redeploy `delete-account` after reviewing its authentication. Check the hosted settings when the guide or configuration requirements change.
3. Keep `cloud.properties` pointing to the intended project and rebuild. Install the new APK with `adb install -r app/build/outputs/apk/debug/app-debug.apk` or the phone's package installer. Matching signing identity is required for an in-place update. An APK signed with a different debug key cannot update the existing installation in place. Export and verify a backup before intentionally uninstalling an incompatible build.
4. Launch SharedLedger after updating. Check your active budget, sign-in status, and **Sync now** before relying on the update across devices. Use a separate test project and installation when the goal is to start with clean data.

## 13. Troubleshooting

| Symptom | Likely cause and resolution | Verify |
| --- | --- | --- |
| `The operation couldn’t be completed. Unable to locate a Java Runtime.` | macOS found no registered Java installation. Use Android Studio's JDK 17 if it is bundled, or install a JDK 17 package and set `JAVA_HOME` using [step 1](#1-install-the-android-tools). | `java -version` and `javac -version` both report 17. |
| `JAVA_HOME is not set`, or Gradle cannot find a Java 17 toolchain | Set `JAVA_HOME` to the JDK directory above `bin`. Put its `bin` first in `PATH` and select the same Java 17 Gradle JDK in Android Studio. | `./gradlew --version` (Windows `.\gradlew.bat --version`) prints the wrapper version and the intended JVM. |
| `SDK location not found`, missing `android-35`, or unaccepted SDK license | Match `ANDROID_HOME` or Android Studio's `local.properties` with SDK Manager. Install Platform 35 and Build Tools 35.0.0, and accept licenses. | Perform the SDK file checks in step 1 and retry `assembleDebug`. |
| Gradle cannot download dependencies or its distribution | Check access to Gradle distributions, Google's Android repository, Maven Central, and the Gradle Plugin Portal. On a managed network, ask for the correct proxy or certificate configuration. A fresh build needs network access. | Retry the wrapper build. The failed download should complete, and Gradle should print `BUILD SUCCESSFUL`. |
| `Target: local stack (127.0.0.1:54322)` and `ECONNREFUSED` | CLI 3.x defaulted to local. Link the intended project, then use `supabase db push --dry-run --remote`. On an older CLI that only lists `--linked`, use that flag or upgrade. | The target line names your hosted project. |
| Docker daemon or socket error | A local-stack or local bundling command needs a running container runtime. For hosted database pushes use `--remote`. For hosted function bundling use `--use-api`. Start Docker only if you intend to run the local stack. | A hosted preview or deployment reaches the intended project. For local work, verify that `supabase start` reports healthy services. |
| Migration fails, a relation already exists, or versions differ | Check the target ref and the first SQL error. Run `supabase migration list --remote` if available. A hand-created schema can conflict with migration history. Back up the database and reconcile it before retrying. Avoid resetting a hosted database or marking a failed migration applied as a shortcut. | Both versions appear in **Local** and **Remote** and the ledger tables exist in **Table Editor**. |
| Cloud sync shows **Local mode** or sign-in returns an invalid API key error | Verify the exact property names, the HTTPS Project URL, and the public key from the same project. Check environment overrides. Build and install a fresh APK. | **Cloud sync** shows **Sign in** and a test user from that project can authenticate. |
| Login errors or signup unavailable | Check **Authentication → Sign In / Providers**, email/password signup, and the project's Auth logs. Make sure the user belongs to this project, has a valid password, and is confirmed if required. | The test user is listed under **Authentication → Users** and the app shows the account email after sign-in. |
| No confirmation email, rate limit, or browser shows localhost | Check spam, allowed recipients, rate limits, SMTP delivery logs, and the Site URL and redirect allowlist. Review the confirmation template and request a fresh email when eligible. | **Email verified** appears in the app and the Dashboard lists the account as confirmed. |
| An email link is invalid, expired, or already used | Verify both redirect entries in **Authentication → URL Configuration**. Check whether the account is already confirmed. Use **Resend confirmation email** or **Reset password** and open the newest email on the installation that requested it. See the scanner guidance if fresh links repeatedly expire. | The app shows **Email verified** or lets you update the password and sign in again. |
| Invite invalid, expired, used, or revoked | Check the [invitation outcome and logs](#diagnose-invitation-failures). Verify the app versions, project, email confirmation, and signed-in accounts. | **Join budget** succeeds and the recipient appears under the budget's members. |
| Manual sync works but changes arrive late | Check that `public.budgets` belongs to `supabase_realtime`, inspect Realtime logs, and test a network that allows WebSockets. Android can defer background work. | Keep the receiving phone online and open. Save a test change on the other phone and check whether it arrives without **Sync now**. |
| Permission denied, RLS error, or a member cannot make changes | Confirm all migrations ran and the signed-in user has an active membership for the selected budget. Use **Editor** or **Owner** for transaction changes and the **Owner** account for managing members and invitations. Keep RLS enabled. | Check the member role in **Cloud sync** and retry a permitted action. |
| Edge Function deploy fails or **Delete account** fails | Check CLI login, the linked project, deployment permissions, and `supabase functions deploy --help`. Deploy the repository's function with `--use-api` and check its logs. The function needs the hosted Supabase URL and legacy service-role runtime variable, plus a valid caller session. | `delete-account` appears in **Edge Functions**. Check account deletion only with disposable data. |
| `adb devices` is empty, `unauthorized`, or `offline` | Unlock the phone, confirm USB debugging, approve the connection dialog, and try a data cable or another USB port. Use the manufacturer's Windows USB driver or Linux device permissions if your OS requires them. | `adb devices` lists the intended serial with state `device`. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, `INSTALL_FAILED_VERSION_DOWNGRADE`, or `INSTALL_FAILED_OLDER_SDK` | Use an APK signed with the installed app's key and an appropriate version. The phone needs Android 8.0 or later. Export data before replacing an incompatible existing build. For installer restrictions, use a device where app installation is allowed. | ADB reports `Success` or the package installer completes, and the app opens. |
| Devices show different totals | Compare the project, active budget, account filters, date period, and transaction rows. Check queued changes, conflicts, pending currency conversions, and the date on each phone. Run **Sync now** on the sender, then on the receiver. | The devices show the same test rows and expense total under matching filters. |

## 14. Security and backups

- **Client configuration.** APKs expose embedded values to anyone who inspects them. Use only the project URL and public publishable key, or the supported legacy `anon` key. Keep the ignored `cloud.properties` out of commits to prevent accidental disclosure of environment settings and management credentials.
- **Server and administrative credentials.** Store your PostgreSQL password, management token, SMTP credentials, and signing key in secure storage. Keep elevated API credentials inside the server environment. If a privileged credential is exposed, revoke or rotate it in the corresponding service and update its authorized consumers.
- **Budget access.** RLS restricts database reads to authorized users. The SQL functions check the caller and membership role for writes, invitations, and role changes. Invitation codes are stored as hashes, are single-use with a 24-hour expiry, and can be revoked by the owner. Share codes only with the intended recipient.
- **Account deletion.** **Settings → Cloud sync → Delete account** calls the Edge Function using the signed-in session. Deleting an owner account deletes its cloud budgets and their shared history. Export data you plan to retain before confirming and coordinate with other members first.
- **Backups.** Export JSON from **Settings → Import and export** to retain budget relationships and transfers. These export files contain financial data. Store them in encrypted or otherwise access-controlled storage and test restore with a separate installation or local budget. Android system backup is disabled for the app. For server-level backups, use your project's available backup tools and check restore procedures before production changes. [SharedLedger file formats](files.md) · [Supabase database backups](https://supabase.com/docs/guides/platform/backups)

See the [data model](schema.md) for authorization and synchronization details. The [verification notes](verification.md) list the commands and flows already checked and the hosted deployment tests to run on your own project.
