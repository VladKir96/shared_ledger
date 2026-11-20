# Import and export formats

Use **Settings → Import and export**. Android's document picker grants access to the selected files. Export one budget or all visible budgets, optionally within the selected reporting range. JSON includes account and category settings and recurring rules even when the date filter restricts the transaction set.

## JSON

The root object has `version`, `budgets`, and `rates`. Version 1 stores each budget as an object containing `budget`, `accounts`, `categories`, `entries`, `rules`, and `members`. Relationships use UUID strings. Dates use `YYYY-MM-DD`. Currency amounts and rates are decimal strings. Nullable rate fields represent pending conversion.

Import validates the version and relationships, presents a preview, and commits after confirmation. Shared archives restore as local budgets. Import preserves IDs when restoring the original budget and maps IDs deterministically when importing into another local budget. Re-import skips existing transaction IDs and rule occurrences. Exchange-rate metadata on historical transactions is preserved unless the destination budget uses a different base currency.

## CSV

CSV exports one transaction per row. Fields include the transaction ID, budget name, date, type, original amount and currency, account, category, note, budget conversion metadata, and transfer destination fields.

External CSV imports use a header row and a column-mapping dialog. Map at least `date` and `amount`. Dates must use `YYYY-MM-DD`. Type may be `INCOME` or `EXPENSE`. With no type column, positive amounts are income and negative amounts are expenses. Decimal points and decimal commas inside quoted fields are accepted. Map any available `id`, `currency`, `account`, `category`, and `note` columns. Rows containing unsupported types or invalid data are reported in the preview.

An external row's ID is used for duplicate detection. Without an ID, identical rows map to the same stable identifier, so supply an ID when two legitimately separate transactions have identical details. Use JSON to restore transfers and relationships.
