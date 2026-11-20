package dev.sharedledger.data

import androidx.room.withTransaction
import dev.sharedledger.domain.*
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Recurring @Inject constructor(private val repo: LedgerRepository) {
    private val dao get() = repo.dao

    suspend fun save(rule: Rule) = repo.db.withTransaction {
        val budget = repo.writable(rule.budgetId)
        val old = dao.rule(rule.id)
        val start = LocalDate.parse(rule.start)
        require(rule.type != EntryType.TRANSFER && rule.interval in 1..366 && (rule.end == null || LocalDate.parse(rule.end) >= start)) {
            "Check the repeat interval and dates."
        }
        require(old == null || old.budgetId == rule.budgetId && old.type == rule.type) { "Create a new rule for a different transaction type." }
        require(old == null || old.nextIndex == 0L ||
            (old.start == rule.start && old.frequency == rule.frequency && old.interval == rule.interval)) {
            "Create a new rule to change the schedule after its first occurrence."
        }
        val account = requireNotNull(dao.account(rule.accountId)?.takeIf { it.budgetId == budget.id }) { "Choose an account." }
        val prepared = repo.prepare(budget, account, draft(rule, start),
            old?.let { Entry("", budget.id, it.accountId, it.categoryId, it.type, it.amount, it.currency, account.currency, budget.currency, it.start, payer = it.payer) })
        repo.store(budget, rule.copy(currency = prepared.currency, amount = prepared.amount, note = prepared.note,
            payer = prepared.payer, nextIndex = old?.nextIndex ?: rule.nextIndex,
            creator = old?.creator ?: prepared.creator, updatedAt = System.currentTimeMillis()))
    }

    suspend fun pause(rule: Rule, delete: Boolean = false) = repo.db.withTransaction {
        val budget = repo.writable(rule.budgetId)
        val current = requireNotNull(dao.rule(rule.id))
        val now = System.currentTimeMillis()
        repo.store(budget, current.copy(active = if (delete) false else !current.active,
            updatedAt = now, deletedAt = if (delete) now else current.deletedAt))
    }

    suspend fun runDue(today: LocalDate = LocalDate.now()): List<Rule> {
        val reminders = mutableListOf<Rule>()
        for (observed in dao.activeRules()) {
            val budget = dao.budget(observed.budgetId) ?: continue
            if (budget.deletedAt != null || budget.role == Role.VIEWER || budget.principal != null && budget.principal != repo.principal.value) continue
            if (observed.nextDate()?.let { it <= today } != true) continue
            if (observed.remind) reminders.add(observed)
            if (observed.automatic) runCatching {
                repeat(100) { if (!advance(observed.id, today)) return@runCatching }
            }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        }
        return reminders
    }

    suspend fun confirm(rule: Rule, today: LocalDate = LocalDate.now()) {
        require(advance(rule.id, today)) { "No occurrence is due." }
    }

    private suspend fun advance(ruleId: String, today: LocalDate): Boolean = repo.db.withTransaction {
        val rule = dao.rule(ruleId) ?: return@withTransaction false
        val day = rule.nextDate()?.takeIf { it <= today && rule.active && rule.deletedAt == null } ?: return@withTransaction false
        val budget = repo.writable(rule.budgetId)
        val account = requireNotNull(dao.account(rule.accountId))
        val category = requireNotNull(dao.category(rule.categoryId))
        require(!account.archived && !category.archived && category.deletedAt == null) { "Choose an active account and category for this rule." }
        val entryId = occurrenceId(ruleId, day)
        if (dao.entry(entryId) == null) {
            val input = draft(rule, day).let {
                if (it.payer != null && dao.member(budget.id, it.payer) == null) it.copy(payer = null) else it
            }
            val entry = repo.prepare(budget, account, input)
            repo.store(budget, entry.copy(id = entryId, ruleId = ruleId))
        }
        repo.store(budget, rule.copy(nextIndex = rule.nextIndex + 1, updatedAt = System.currentTimeMillis()))
        true
    }

    private fun draft(rule: Rule, day: LocalDate) =
        EntryDraft(rule.type, rule.amount.decimal(), day, rule.accountId, rule.categoryId, rule.currency, rule.note, rule.payer)
}

fun Rule.nextDate(): LocalDate? = occurrence(LocalDate.parse(start), frequency, interval.toLong(), nextIndex)
    .takeIf { end == null || it.toString() <= end }
