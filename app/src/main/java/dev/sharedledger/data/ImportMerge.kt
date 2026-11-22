package dev.sharedledger.data

import androidx.room.withTransaction
import dev.sharedledger.domain.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.text.Normalizer
import java.time.LocalDate
import java.util.UUID

sealed interface ImportDestination {
    data class Existing(val budgetId: String) : ImportDestination
    data object NewPersonal : ImportDestination
    data class NewShared(val userId: String) : ImportDestination
}

data class ImportReview internal constructor(
    val budget: Budget,
    val transactions: Int,
    val accountsCreated: Int,
    val accountsReused: Int,
    val categoriesCreated: Int,
    val categoriesReused: Int,
    val duplicates: Int,
    val attention: Int,
    val warnings: List<String>,
    val accounts: List<String>,
    val categories: List<String>,
    val sample: Entry?,
    internal val fingerprint: String
)

internal fun importOperation(kind: String, body: String): String {
    val fields = ledgerJson.parseToJsonElement(body).jsonObject
    return importId("operation:$kind:${JsonObject(fields - setOf("createdAt", "updatedAt"))}")
}

private fun importId(value: String) = UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)).toString()
private val wordSpacing = Regex("\\s+")
private fun String.matchName() = Normalizer.normalize(trim(), Normalizer.Form.NFC).replace(wordSpacing, " ").lowercase()
private fun Account.importKey() = Triple(name.matchName(), type, currency)
private fun Category.importKey() = type to name.matchName()

internal class ImportMerge(private val repo: LedgerRepository) {
    private val dao get() = repo.dao

    suspend fun review(preview: ImportPreview, destination: ImportDestination): ImportReview =
        repo.db.withTransaction { plan(preview, destination).review }

    suspend fun apply(preview: ImportPreview, destination: ImportDestination, reviewed: ImportReview): ImportReview =
        repo.db.withTransaction {
            val plan = plan(preview, destination)
            require(plan.review == reviewed) { "The destination changed after preview. Review the import again." }
            val budget = plan.review.budget
            if (plan.newBudget) repo.store(budget, budget, imported = true)
            for (record in plan.records) repo.store(budget, record, imported = true)
            dao.putImportLinks(plan.links)
            repo.storeArchiveRates(preview.archive.rates)
            repo.preferences { it.copy(activeBudget = budget.id, lastAccount = null) }
            plan.review
        }

    private data class Plan(val review: ImportReview, val newBudget: Boolean, val records: List<LedgerRecord>, val links: List<ImportLink>)

    private suspend fun plan(preview: ImportPreview, destination: ImportDestination): Plan {
        val source = requireNotNull(preview.archive.budgets.singleOrNull()) {
            "Merge one source budget at a time. Use Restore backup for archives with multiple budgets."
        }
        require(preview.count > 0 || preview.rejectedRows == 0) { "The file contains no valid transactions." }
        val template = when (destination) {
            is ImportDestination.Existing -> repo.writable(destination.budgetId)
            ImportDestination.NewPersonal -> source.budget.copy(id = importId("personal-import:${source.budget.id}"),
                type = BudgetType.PERSONAL, role = Role.OWNER, principal = null, owner = null, deletedAt = null)
            is ImportDestination.NewShared -> {
                require(repo.principal.value == destination.userId) { "Sign in to create a shared budget." }
                source.budget.copy(id = importId("shared-import:${destination.userId}:${source.budget.id}"),
                    type = BudgetType.SHARED, role = Role.OWNER, principal = destination.userId, owner = destination.userId, deletedAt = null)
            }
        }
        val existing = dao.budget(template.id)
        val budget = existing?.let {
            require(it.principal == template.principal && it.type == template.type) { "This import's budget identifier is already in use." }
            repo.writable(it.id)
        } ?: template
        val context = Mapping(budget.id, source.budget.id, dao.importLinks(budget.id, source.budget.id))
        val savedAccounts = dao.accounts(budget.id)
        val savedCategories = dao.categories(budget.id)
        val accountMap = context.match("account", source.accounts, savedAccounts, Account::importKey, { a, b ->
            a.currency == b.currency && a.type == b.type && b.deletedAt == null
        }, { !it.archived && it.deletedAt == null }, dao::account)
        val categoryMap = context.match("category", source.categories, savedCategories, Category::importKey, { a, b ->
            a.type == b.type && b.deletedAt == null
        }, { !it.archived && it.deletedAt == null }, dao::category)
        fun account(id: String) = accountMap.getValue(id).id
        fun category(id: String) = categoryMap.getValue(id).id
        val accountOrder = savedAccounts.maxOfOrNull(Account::order)
        val categoryOrder = savedCategories.maxOfOrNull(Category::order)
        val accounts = source.accounts.sortedBy(Account::order).filter { accountMap.getValue(it.id).existing == null }
            .mapIndexed { i, a -> a.copy(id = account(a.id), budgetId = budget.id, name = a.name.cleanName(), order = appendOrder(accountOrder, i),
                opening = requireNotNull(parseMoney(a.opening)).storage(),
                icon = a.icon.takeIf { it.length in 1..40 } ?: "wallet", color = a.color.takeIf { it in 0..0xffffffffL } ?: categoryColors.first()) }
        val categories = source.categories.sortedBy(Category::order).filter { categoryMap.getValue(it.id).existing == null }
            .mapIndexed { i, c -> c.copy(id = category(c.id), budgetId = budget.id, name = c.name.cleanName(), order = appendOrder(categoryOrder, i),
                icon = c.icon.takeIf { it.length in 1..40 } ?: "more", color = c.color.takeIf { it in 0..0xffffffffL } ?: categoryColors.first()) }
            .toMutableList()
        if (existing == null) {
            val available = categories.filter { !it.archived && it.deletedAt == null }.mapTo(hashSetOf(), Category::importKey)
            for (default in startingCategories(budget.id).filter { it.importKey() !in available }) {
                val id = importId("default-category:${budget.id}:${default.type}:${default.name}")
                require(dao.category(id) == null && categories.none { it.id == id }) { "A default category identifier is already in use." }
                categories.add(default.copy(id = id, order = categories.size))
            }
        }

        val savedRules = dao.rules(budget.id).associateBy(Rule::id)
        val rules = mutableListOf<Rule>()
        val ruleIds = source.rules.associate { rule ->
            val mapped = context.find("rule", rule.id, savedRules, dao::rule)
            context.remember("rule", rule.id, mapped.id, mapped.existing)
            require(parseMoney(rule.amount)?.signum() == 1) { "A recurring rule has an unsupported amount." }
            val linked = rule.copy(id = mapped.id, budgetId = budget.id, accountId = account(rule.accountId),
                categoryId = category(rule.categoryId), payer = null, creator = null)
            require(mapped.existing?.let { it.type == linked.type && it.accountId == linked.accountId && it.categoryId == linked.categoryId } != false) {
                "An imported recurring rule conflicts with a rule in the destination."
            }
            if (mapped.existing == null) rules.add(linked)
            rule.id to mapped.id
        }
        val savedEntries = dao.entries(budget.id).associateBy(Entry::id)
        val occurrences = savedEntries.values.mapNotNull { entry -> entry.ruleId?.let { (it to entry.date) to entry } }.toMap()
        val entries = mutableListOf<Entry>()
        val warnings = preview.errors.toMutableList()
        var attention = preview.rejectedRows
        var duplicates = 0
        var changedCurrency = 0
        val taken = hashSetOf<String>()
        for (entry in source.entries) {
            val rule = entry.ruleId?.let(ruleIds::getValue)
            val occurrence = rule?.let { occurrences[it to entry.date] }
            val preferred = rule?.takeIf { it != entry.ruleId }?.let { occurrenceId(it, LocalDate.parse(entry.date)) }
            val mapped = if (occurrence != null) context.remember("entry", entry.id, occurrence.id, occurrence)
                else context.find("entry", entry.id, savedEntries, dao::entry, preferred).also {
                    context.remember("entry", entry.id, it.id, it.existing)
                }
            val linked = entry.copy(id = mapped.id, budgetId = budget.id, accountId = account(entry.accountId),
                categoryId = entry.categoryId?.let(::category), destinationId = entry.destinationId?.let(::account),
                ruleId = rule, payer = null, creator = null, amount = entry.amount.importDecimal(),
                accountAmount = entry.accountAmount?.importDecimal(), budgetAmount = entry.budgetAmount?.importDecimal(),
                destinationAmount = entry.destinationAmount?.importDecimal(),
                bankConversion = entry.bankConversion?.copy(learningEligible = false), budgetRateConfirmedAt = null)
            require(taken.add(linked.id)) { "The file maps more than one transaction to the same record." }
            if (mapped.existing != null) {
                if (mapped.existing.deletedAt == null && mapped.existing.sameImport(linked)) duplicates++
                else {
                    attention++
                    if (warnings.size < 200) warnings += "Transaction on ${entry.date} (${entry.id}) already exists with different details or was deleted. Review it in the destination."
                }
                continue
            }
            val adjusted = linked.forImportedBudget(budget.currency)
            if (linked.type != EntryType.TRANSFER && linked.budgetCurrency != budget.currency) changedCurrency++
            if (budget.principal != null) validateCloudEntry(adjusted)
            entries.add(adjusted)
        }
        if (changedCurrency > 0) warnings += "$changedCurrency transactions will use the destination currency for budget reporting. Missing historical conversions stay pending."
        val accountDetails = accountMap.values.mapNotNull { it.existing }.map { "Reuse ${it.name} (${it.currency})" } +
            accounts.map { "Create ${it.name} (${it.currency})" }
        val categoryDetails = categoryMap.values.mapNotNull { it.existing }.map { "Reuse ${it.name} (${it.type.name.lowercase()})" } +
            categories.map { "Create ${it.name} (${it.type.name.lowercase()})" }
        val fingerprint = importId(context.links.joinToString("|") { "${it.kind}:${it.sourceId}:${it.targetId}" } +
            entries.joinToString("|", prefix = "|") { it.id } + (existing == null))
        return Plan(ImportReview(budget, entries.size, accounts.size, accountMap.size - accounts.size,
            categories.size, categoryMap.values.count { it.existing != null }, duplicates, attention, warnings,
            accountDetails, categoryDetails, entries.firstOrNull(), fingerprint),
            existing == null, accounts + categories + rules + entries, context.links)
    }
}

private data class Mapped<T>(val id: String, val existing: T?)

private class Mapping(private val budget: String, private val source: String, links: List<ImportLink>) {
    private val saved = links.associateBy { it.kind to it.sourceId }
    val links = mutableListOf<ImportLink>()

    fun <T> remember(kind: String, sourceId: String, id: String, existing: T?): Mapped<T> {
        links.add(ImportLink(budget, source, kind, sourceId, id))
        return Mapped(id, existing)
    }

    suspend fun <T : LedgerRecord> find(
        kind: String, sourceId: String, existing: Map<String, T>, load: suspend (String) -> T?, preferred: String? = null
    ): Mapped<T> {
        val old = saved[kind to sourceId]?.targetId
        val candidate = old ?: preferred ?: if (budget == source) sourceId else importId("$budget:$sourceId")
        val record = existing[candidate]
        if (record != null || load(candidate) == null) return Mapped(candidate, record)
        val scoped = importId("import:$budget:$source:$kind:$sourceId")
        require(existing[scoped] != null || load(scoped) == null) { "An import identifier belongs to another budget." }
        return Mapped(scoped, existing[scoped])
    }

    suspend fun <T : LedgerRecord, K> match(
        kind: String, incoming: List<T>, local: List<T>, key: (T) -> K,
        compatible: (T, T) -> Boolean, available: (T) -> Boolean, load: suspend (String) -> T?
    ): Map<String, Mapped<T>> {
        val localById = local.associateBy(LedgerRecord::id)
        val localByKey = local.filter(available).groupBy(key)
        val sourceByKey = incoming.groupingBy(key).eachCount()
        val claimed = hashSetOf<String>()
        return incoming.associate { record ->
            // Prefer a saved link or previously allocated stable ID before using a name.
            val allocated = find(kind, record.id, localById, load)
            val matchKey = key(record)
            val match = allocated.existing?.takeIf { compatible(record, it) && it.id !in claimed }
                ?: localByKey[matchKey]?.singleOrNull()?.takeIf { sourceByKey[matchKey] == 1 && it.id !in claimed }
            val mapped = when {
                match != null -> remember(kind, record.id, match.id, match)
                allocated.existing == null && allocated.id !in claimed -> remember<T>(kind, record.id, allocated.id, null)
                else -> {
                    val id = importId("distinct:$budget:$source:$kind:${record.id}")
                    val previous = localById[id]
                    require(id !in claimed && (previous?.let { compatible(record, it) } ?: (load(id) == null))) {
                        "An imported account or category identifier conflicts with existing data."
                    }
                    remember(kind, record.id, id, previous)
                }
            }
            claimed.add(mapped.id)
            record.id to mapped
        }
    }
}

private fun appendOrder(previous: Int?, index: Int) =
    ((previous ?: -1).toLong() + 1L + index).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

private fun String.importDecimal() = requireNotNull(parseMoney(this)).storage()

private fun Entry.sameImport(other: Entry) = type == other.type && date == other.date && currency == other.currency &&
    parseMoney(amount)?.storage() == other.amount && accountId == other.accountId &&
    categoryId == other.categoryId && destinationId == other.destinationId && destinationCurrency == other.destinationCurrency &&
    destinationAmount?.let(::parseMoney)?.storage() == other.destinationAmount &&
    ruleId == other.ruleId && note == other.note

private fun Entry.forImportedBudget(base: String): Entry {
    if (type == EntryType.TRANSFER) {
        require(destinationId != accountId && ruleId == null) { "A transfer must keep two distinct accounts." }
        val source = if (accountAmount == null) copy(accountAmount = amount, accountRate = "1", accountRateDate = date,
            accountRateSource = "Same currency") else this
        return source.copy(budgetCurrency = base)
    }
    if (budgetCurrency == base) return this
    return when (base) {
        currency -> copy(budgetCurrency = base, budgetAmount = amount, budgetRate = "1", budgetRateDate = date, budgetRateSource = "Same currency")
        accountCurrency -> copy(budgetCurrency = base, budgetAmount = accountAmount, budgetRate = accountRate,
            budgetRateDate = accountRateDate, budgetRateSource = accountRateSource)
        else -> copy(budgetCurrency = base, budgetAmount = null, budgetRate = null, budgetRateDate = null, budgetRateSource = null)
    }
}

private fun validateCloudEntry(entry: Entry) {
    require(listOfNotNull(entry.accountRateSource, entry.budgetRateSource).all { it.length in 1..120 }) {
        "A historical exchange-rate source exceeds the cloud field limit."
    }
    require(if (entry.type == EntryType.TRANSFER) entry.accountAmount?.toBigDecimal()?.compareTo(entry.amount.toBigDecimal()) == 0
        else entry.destinationCurrency == null && entry.destinationAmount == null && entry.transferRate == null) {
        "The file contains an invalid transfer relationship."
    }
}
