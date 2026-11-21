package dev.sharedledger.data

import androidx.room.withTransaction
import dev.sharedledger.domain.*
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

@Serializable
data class Archive(val version: Int = 1, val budgets: List<BudgetArchive>, val rates: List<Rate> = emptyList())

@Serializable
data class BudgetArchive(
    val budget: Budget, val accounts: List<Account>, val categories: List<Category>,
    val entries: List<Entry>, val rules: List<Rule>, val members: List<Member> = emptyList()
)

data class CsvTable(val headers: List<String>, val rows: List<List<String>>)
data class CsvMapping(val date: String, val amount: String, val type: String?, val currency: String?, val category: String?,
                      val account: String?, val note: String?, val id: String?)
data class ImportPreview(val archive: Archive, val errors: List<String> = emptyList(), val rejectedRows: Int = errors.size) {
    val count get() = archive.budgets.sumOf { it.entries.size }
}

class Portability @Inject constructor(private val repo: LedgerRepository) {
    private val dao get() = repo.dao

    suspend fun export(ids: List<String>, range: DateSpan): Archive = repo.db.withTransaction {
        val rates = linkedSetOf<Rate>()
        val budgets = ids.distinct().map { key ->
            val budget = requireNotNull(dao.budget(key)?.takeIf {
                it.deletedAt == null && (it.principal == null || it.principal == repo.principal.value)
            }) { "This budget is unavailable." }
            val entries = dao.entries(key).filter { it.deletedAt == null && LocalDate.parse(it.date) in range }
            entries.forEach { entry ->
                listOf(entry.accountCurrency, entry.budgetCurrency).distinct().forEach { target ->
                    dao.rate(entry.currency, target, entry.date)?.let { rates.add(it.copy(requestedDate = entry.date)) }
                }
            }
            BudgetArchive(budget.copy(principal = null, owner = null, role = Role.OWNER), dao.accounts(key).filter { it.deletedAt == null },
                dao.categories(key).filter { it.deletedAt == null }, entries, dao.rules(key).filter { rule -> rule.deletedAt == null || entries.any { it.ruleId == rule.id } },
                dao.membersOnce(key))
        }
        Archive(budgets = budgets, rates = rates.toList())
    }

    fun encode(value: Archive, csv: Boolean): String = if (csv) encodeCsv(value) else ledgerJson.encodeToString(Archive.serializer(), value)

    fun decodeJson(text: String): ImportPreview {
        val archive = try { ledgerJson.decodeFromString<Archive>(text) } catch (_: Exception) {
            throw IllegalArgumentException("This file is not a valid SharedLedger archive.")
        }
        require(archive.version == 1 && archive.budgets.isNotEmpty() && archive.budgets.size <= 500) { "Unsupported or empty archive." }
        require(archive.budgets.map { it.budget.id }.distinct().size == archive.budgets.size) { "The archive repeats a budget ID." }
        archive.budgets.forEach(::validate)
        listOf(archive.budgets.flatMap { it.accounts.map(Account::id) }, archive.budgets.flatMap { it.categories.map(Category::id) },
            archive.budgets.flatMap { it.rules.map(Rule::id) }, archive.budgets.flatMap { it.entries.map(Entry::id) }).forEach { ids ->
            require(ids.distinct().size == ids.size) { "The archive reuses record identifiers across budgets." }
        }
        archive.rates.forEach { rate ->
            rate.base.validCurrency(); rate.target.validCurrency()
            require(rate.value.toBigDecimalOrNull()?.signum() == 1)
            require(LocalDate.parse(rate.date) <= LocalDate.parse(rate.requestedDate) && rate.source.length in 1..120)
        }
        return ImportPreview(archive)
    }

    private fun validate(value: BudgetArchive) {
        val budget = value.budget
        budget.id.requireId(); budget.name.cleanName(); budget.currency.validCurrency()
        val accounts = value.accounts.associateBy { it.id }
        val categories = value.categories.associateBy { it.id }
        val rules = value.rules.associateBy { it.id }
        require(accounts.size == value.accounts.size && categories.size == value.categories.size &&
            rules.size == value.rules.size && value.entries.map { it.id }.distinct().size == value.entries.size &&
            value.members.map { it.userId }.distinct().size == value.members.size) { "The archive contains duplicate identifiers." }
        value.accounts.forEach {
            it.id.requireId(); it.name.cleanName(); it.currency.validCurrency()
            require(it.budgetId == budget.id && parseMoney(it.opening) != null)
        }
        value.categories.forEach {
            it.id.requireId(); it.name.cleanName()
            require(it.budgetId == budget.id && it.type != EntryType.TRANSFER)
        }
        value.members.forEach {
            it.userId.requireId(); it.name.cleanName()
            require(it.budgetId == budget.id)
        }
        value.rules.forEach {
            it.id.requireId(); it.currency.validCurrency()
            require(it.budgetId == budget.id && it.accountId in accounts && categories[it.categoryId]?.type == it.type &&
                it.amount.toBigDecimalOrNull()?.signum() == 1 && it.interval in 1..366 && it.nextIndex in 0..4_000_000 && it.note.length <= 2000)
            val start = LocalDate.parse(it.start)
            require(it.end == null || LocalDate.parse(it.end) >= start)
        }
        val occurrences = mutableSetOf<Pair<String, String>>()
        value.entries.forEach { entry ->
            entry.id.requireId(); entry.currency.validCurrency(); entry.budgetCurrency.validCurrency(); entry.accountCurrency.validCurrency()
            val date = LocalDate.parse(entry.date)
            val amount = parseMoney(entry.amount)
            require(entry.budgetId == budget.id && accounts[entry.accountId]?.currency == entry.accountCurrency &&
                entry.note.length <= 2000 && amount?.signum() == 1) { "A transaction has an invalid account or amount." }
            require(entry.ruleId == null || rules[entry.ruleId]?.type == entry.type && occurrences.add(entry.ruleId to entry.date)) {
                "The archive contains an invalid or duplicate recurring occurrence."
            }
            listOf(entry.accountAmount, entry.budgetAmount).forEach { require(it == null || parseMoney(it)?.signum() in listOf(0, 1)) }
            fun rateFields(value: String?, rate: String?, day: String?, source: String?) {
                require(if (value == null) rate == null && day == null && source == null else
                    rate?.toBigDecimalOrNull()?.signum() == 1 && day != null && LocalDate.parse(day) <= date && !source.isNullOrBlank())
            }
            rateFields(entry.accountAmount, entry.accountRate, entry.accountRateDate, entry.accountRateSource)
            rateFields(entry.budgetAmount, entry.budgetRate, entry.budgetRateDate, entry.budgetRateSource)
            if (entry.type == EntryType.TRANSFER) {
                require(entry.destinationId != null && entry.destinationId != entry.accountId &&
                    entry.destinationCurrency != null && accounts[entry.destinationId]?.currency == entry.destinationCurrency &&
                    entry.destinationAmount?.let(::parseMoney)?.signum() == 1 && entry.transferRate?.toBigDecimalOrNull()?.signum() == 1 &&
                    entry.currency == entry.accountCurrency && entry.budgetAmount == null && entry.categoryId == null)
            } else require(categories[entry.categoryId]?.type == entry.type && entry.destinationId == null)
        }
    }

    private val merge = ImportMerge(repo)

    suspend fun reviewMerge(preview: ImportPreview, destination: ImportDestination): ImportReview =
        merge.review(preview, destination)

    suspend fun applyMerge(preview: ImportPreview, destination: ImportDestination, reviewed: ImportReview): ImportReview =
        merge.apply(preview, destination, reviewed)

    suspend fun restore(preview: ImportPreview): Pair<Int, Int> = repo.db.withTransaction {
        require(preview.archive.budgets.isNotEmpty())
        var inserted = 0
        var duplicates = 0
        var activate: String? = null
        for (value in preview.archive.budgets) {
            val existing = dao.budget(value.budget.id)
            require(existing?.principal == null && existing?.deletedAt == null) {
                "A protected or deleted budget has this identifier. Use Merge to choose a destination."
            }
            val budget = existing ?: value.budget.copy(type = BudgetType.PERSONAL, principal = null, owner = null,
                role = Role.OWNER, deletedAt = null).also { repo.store(it, it) }
            activate = budget.id
            fun remap(recordId: String) = recordId
            val accountMap = value.accounts.associate { it.id to remap(it.id) }
            val categoryMap = value.categories.associate { it.id to remap(it.id) }
            val ruleMap = value.rules.associate { it.id to remap(it.id) }
            val existingAccounts = dao.accounts(budget.id).associateBy { it.id }
            val existingCategories = dao.categories(budget.id).associateBy { it.id }
            val existingRules = dao.rules(budget.id).associateBy { it.id }
            val existingMembers = dao.membersOnce(budget.id).associateBy { it.userId }
            value.accounts.forEach {
                val mapped = accountMap.getValue(it.id)
                val current = existingAccounts[mapped] ?: dao.account(mapped)
                require(current == null || current.budgetId == budget.id) { "An account identifier belongs to another budget." }
                require(current == null || current.currency == it.currency) { "An existing account uses a different currency." }
                if (current == null) repo.store(budget, it.copy(id = mapped, budgetId = budget.id))
            }
            value.categories.forEach {
                val mapped = categoryMap.getValue(it.id)
                val current = existingCategories[mapped] ?: dao.category(mapped)
                require(current == null || current.budgetId == budget.id) { "A category identifier belongs to another budget." }
                require(current == null || current.type == it.type) { "An existing category uses a different transaction type." }
                if (current == null) repo.store(budget, it.copy(id = mapped, budgetId = budget.id))
            }
            value.members.forEach {
                if (it.userId !in existingMembers) dao.put(it.copy(budgetId = budget.id))
            }
            value.rules.forEach {
                val mapped = ruleMap.getValue(it.id)
                val current = existingRules[mapped] ?: dao.rule(mapped)
                require(current == null || current.budgetId == budget.id) { "A recurring-rule identifier belongs to another budget." }
                if (current == null) repo.store(budget, it.copy(id = mapped,
                    budgetId = budget.id, accountId = accountMap.getValue(it.accountId), categoryId = categoryMap.getValue(it.categoryId)))
            }
            val occurrences = dao.entries(budget.id).mapNotNull { e -> e.ruleId?.let { it to e.date } }.toMutableSet()
            value.entries.forEach { entry ->
                val rule = entry.ruleId?.let(ruleMap::getValue)
                val entryId = if (rule != null && rule != entry.ruleId) occurrenceId(rule, LocalDate.parse(entry.date)) else remap(entry.id)
                if (dao.entry(entryId) != null || rule != null && !occurrences.add(rule to entry.date)) {
                    duplicates++
                } else {
                    val transferred = entry.copy(id = entryId, budgetId = budget.id, accountId = accountMap.getValue(entry.accountId),
                        categoryId = entry.categoryId?.let(categoryMap::getValue), destinationId = entry.destinationId?.let(accountMap::getValue), ruleId = rule)
                    val adjusted = if (budget.currency == transferred.budgetCurrency || transferred.type == EntryType.TRANSFER) transferred
                    else if (budget.currency == transferred.currency) transferred.copy(budgetCurrency = budget.currency,
                        budgetAmount = transferred.amount, budgetRate = "1", budgetRateDate = transferred.date, budgetRateSource = "Same currency")
                    else transferred.copy(budgetCurrency = budget.currency, budgetAmount = null, budgetRate = null, budgetRateDate = null, budgetRateSource = null)
                    repo.store(budget, adjusted)
                    inserted++
                }
            }
        }
        repo.storeArchiveRates(preview.archive.rates)
        dao.put((dao.preferences() ?: Preferences()).copy(activeBudget = activate))
        inserted to duplicates
    }

    fun parseCsv(text: String): CsvTable {
        val rows = csvRows(text.removePrefix("\uFEFF"))
        val headers = rows.firstOrNull()?.map { it.trim() } ?: emptyList()
        require(headers.size in 2..100 && headers.none { it.isBlank() } && headers.map { it.lowercase() }.distinct().size == headers.size) {
            "CSV requires a header row with unique column labels."
        }
        return CsvTable(headers, rows.drop(1))
    }

    fun decodeCsv(table: CsvTable, mapping: CsvMapping, name: String, currency: String): ImportPreview {
        val budget = Budget(named("csv-budget:${name.trim()}:$currency"), name.cleanName(), currency.validCurrency())
        val accounts = linkedMapOf<Pair<String, String>, Account>()
        val categories = linkedMapOf<Pair<String, EntryType>, Category>()
        val entries = linkedMapOf<String, Entry>()
        val errors = mutableListOf<String>()
        var rejected = 0
        val occurrences = mutableMapOf<String, Int>()
        val headerIndex = table.headers.withIndex().associate { it.value to it.index }
        table.rows.forEachIndexed { index, row ->
            fun get(column: String?): String = headerIndex[column]?.let { row.getOrNull(it) }?.trim().orEmpty()
            try {
                require(row.size == table.headers.size) { "Wrong number of columns." }
                val day = LocalDate.parse(get(mapping.date))
                val raw = requireNotNull(parseMoney(get(mapping.amount))) { "Invalid amount." }
                val code = get(mapping.currency).ifBlank { currency }.uppercase().validCurrency()
                val typeValue = get(mapping.type).uppercase()
                require(typeValue in listOf("", "INCOME", "EXPENSE")) { "Expected Income or Expense." }
                val type = if (typeValue == "INCOME" || typeValue.isBlank() && raw.signum() > 0) EntryType.INCOME else EntryType.EXPENSE
                val amount = raw.abs()
                require(amount.signum() > 0) { "Amount must be nonzero." }
                val wallet = get(mapping.account).ifBlank { "Cash" }.cleanName()
                val label = get(mapping.category).ifBlank { if (type == EntryType.INCOME) "Other Income" else "Other" }.cleanName()
                val note = get(mapping.note)
                require(note.length <= 2000) { "Note is too long." }
                val account = accounts.getOrPut(wallet to code) { Account(named("${budget.id}:$wallet:$code"), budget.id, wallet, code) }
                val category = categories.getOrPut(label to type) { Category(named("${budget.id}:$label:$type"), budget.id, label, type, "more", categoryColors.first()) }
                val supplied = get(mapping.id)
                val identifier = if (supplied.isNotEmpty()) named("${budget.id}:csv-id:$supplied") else {
                    val key = "${budget.id}:csv-row:${listOf(day, amount.storage(), code, type, wallet, label, note).joinToString("\u001f")}"
                    val occurrence = occurrences.merge(key, 1, Int::plus) ?: 1
                    named(if (occurrence == 1) key else "$key:occurrence:$occurrence")
                }
                require(identifier !in entries) { "Duplicate transaction." }
                val dayText = day.toString()
                entries[identifier] = Entry(identifier, budget.id, account.id, category.id, type, amount.storage(), code, code, currency, dayText,
                    accountAmount = amount.storage(), budgetAmount = amount.storage().takeIf { code == currency },
                    accountRate = "1", accountRateDate = dayText, accountRateSource = "Same currency",
                    budgetRate = "1".takeIf { code == currency }, budgetRateDate = dayText.takeIf { code == currency },
                    budgetRateSource = "Same currency".takeIf { code == currency }, note = note)
            } catch (e: Exception) {
                rejected++
                if (errors.size < 200) errors += "Row ${index + 2} · ${e.message ?: "Invalid transaction."}"
            }
        }
        return ImportPreview(Archive(budgets = listOf(BudgetArchive(budget, accounts.values.toList(), categories.values.toList(),
            entries.values.toList(), emptyList()))), errors, rejected)
    }

    private fun encodeCsv(value: Archive): String = buildString {
        appendLine("id,budget,date,type,amount,currency,account,category,note,budget_currency,budget_amount,budget_rate,budget_rate_date,budget_rate_source,destination,destination_amount")
        value.budgets.forEach { scoped ->
            val accounts = scoped.accounts.associateBy { it.id }
            val categories = scoped.categories.associateBy { it.id }
            scoped.entries.forEach { e ->
                appendLine(listOf(e.id, scoped.budget.name, e.date, e.type.name, e.amount, e.currency,
                    accounts[e.accountId]?.name, categories[e.categoryId]?.name, e.note, e.budgetCurrency, e.budgetAmount,
                    e.budgetRate, e.budgetRateDate, e.budgetRateSource, accounts[e.destinationId]?.name, e.destinationAmount)
                    .joinToString(",") { field -> "\"" + field.orEmpty().replace("\"", "\"\"") + "\"" })
            }
        }
    }

    private fun String.requireId() { require(runCatching { UUID.fromString(this).toString() == this }.getOrDefault(false)) }
}

internal suspend fun LedgerRepository.storeArchiveRates(values: List<Rate>) {
    values.forEach { rate ->
        if (dao.rate(rate.base, rate.target, rate.requestedDate) == null) {
            dao.put(rate)
            if (rate.source != MANUAL_RATE && LocalDate.parse(rate.date) >= LocalDate.parse(rate.requestedDate).minusDays(RATE_LOOKBACK)) {
                dao.put(RateCheck(rate.base, rate.target, rate.requestedDate, rate.date, rate.source, rate.fetchedAt))
            }
        }
    }
}

private fun named(value: String): String = UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)).toString()

fun csvRows(text: String): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    val row = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var justClosed = false
    var index = 0
    fun finishField() { row.add(field.toString()); field.clear(); justClosed = false }
    fun finishRow() { finishField(); if (row.any { it.isNotBlank() }) rows.add(row.toList()); row.clear() }
    while (index < text.length) {
        val char = text[index++]
        if (quoted) {
            if (char == '"' && text.getOrNull(index) == '"') {
                field.append('"'); index++
            } else if (char == '"') { quoted = false; justClosed = true }
            else field.append(char)
        } else {
            when {
                char == '"' && field.isEmpty() && !justClosed -> quoted = true
                char == ',' -> finishField()
                char == '\r' || char == '\n' -> {
                    if (char == '\r' && text.getOrNull(index) == '\n') index++
                    finishRow()
                }
                char == '"' || justClosed -> throw IllegalArgumentException("Malformed quoted CSV value.")
                else -> field.append(char)
            }
        }
    }
    require(!quoted) { "CSV ends inside a quoted field." }
    finishRow()
    return rows
}
