# Import and export formats

Use **Settings → Import and export**. Android's document picker grants access to the selected files. Export one budget or all visible budgets, optionally within the selected reporting range. JSON includes account and category settings and recurring rules even when the date filter restricts the transaction set.

## Merge into a budget

1. Open **Settings → Import and export → Choose a file**. Select a SharedLedger JSON archive or a CSV file. For CSV, confirm the column mapping.
2. Choose a writable **Destination**. You can select an existing personal or shared budget, create a new personal budget, or create a new shared budget when signed in and online. Shared budget owners and editors can merge, and viewers have read-only access. An archive with several budgets uses **Restore backup as local budgets**. To merge one of those budgets, export it separately.
3. Review the transaction counts, account and category mappings, duplicate counts, and any warnings. Tap **Review and import**, then confirm.

Merge keeps the destination budget, its members, and its existing transactions, accounts, and categories. It first checks saved import mappings and stable IDs. It reuses a category by its normalized name and transaction type when the match is unique. Accounts must also match account type and currency. New records keep applicable source details, and existing accounts keep their opening balances. New merge budgets include the default categories alongside imported ones.

Stable transaction IDs and recurring occurrences make reimports safe. The preview flags an existing ID with different transaction details for review. Distinct transactions with the same amount and date can still be imported. The receipt links in the local database remember mappings after accounts or categories are renamed.

JSON preserves both legs of a transfer, original amounts and currencies, notes, and historical account conversions. If the destination budget uses a different base currency, its reporting conversion uses an applicable historical quote or stays pending. The original archive retains its reporting conversion. Imported membership and payer or creator IDs are ignored when merging.

A merge saves records and outbox entries together in Room. You can merge into an existing writable shared budget offline using the last known role. Cloud changes remain pending until Supabase accepts them. The import uploads in resumable batches of up to 50 records, with dependencies before transactions. Other members receive the changes through normal sync. Use **Settings → Cloud sync** to retry or review conflicts. An import conflict on an account, category, or rule pauses the remaining import until resolved.

Deploy the cloud migration from the linked repository before using shared imports.

```sh
supabase db push --dry-run --remote
supabase db push --remote
supabase migration list --remote
```

Verify migration `202611210002` appears on the remote. [Database migration guide](setup-guide.md#5-apply-and-verify-the-database-migrations)

## JSON backup and restore

The root object has `version`, `budgets`, and `rates`. Version 1 stores each budget as an object containing `budget`, `accounts`, `categories`, `entries`, `rules`, and `members`. Relationships use UUID strings. Dates use `YYYY-MM-DD`. Currency amounts and rates are decimal strings. Nullable rate fields represent pending conversion.

**Restore backup as local budgets** restores the original budget structure and IDs. Shared archives restore locally. Restoring the same archive skips existing transaction IDs and rule occurrences. Select a merge destination to add the records to a different budget. Restore keeps the existing version 1 format.

## CSV

CSV exports one transaction per row. Fields include the transaction ID, budget name, date, type, original amount and currency, account, category, note, budget conversion metadata, and transfer destination fields.

External CSV imports use a header row and a column-mapping dialog. Map at least `date` and `amount`. Dates must use `YYYY-MM-DD`. Type may be `INCOME` or `EXPENSE`. With no type column, positive amounts are income and negative amounts are expenses. Decimal points and decimal commas inside quoted fields are accepted. Map any available `id`, `currency`, `account`, `category`, and `note` columns. Rows containing unsupported types or invalid data are reported in the preview.

An external row's ID is used for duplicate detection. Without source IDs, each occurrence of an identical row in the file gets its own stable ID. Reimporting the same CSV keeps those distinct purchases without adding them again. Use JSON to carry transfers and their relationships.
