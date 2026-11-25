package dev.sharedledger.presentation

import androidx.compose.runtime.saveable.listSaver

internal data class GroupExpansion(
    val scope: String = "",
    val expandNew: Boolean = false,
    val exceptions: Set<String> = emptySet()
) {
    fun isExpanded(key: String): Boolean = expandNew != (key in exceptions)

    fun toggle(key: String): GroupExpansion =
        copy(exceptions = if (key in exceptions) exceptions - key else exceptions + key)

    fun setVisible(visible: Set<String>, known: Set<String>, expand: Boolean): GroupExpansion {
        val hidden = (known + exceptions) - visible
        return copy(expandNew = expand, exceptions = hidden.filterTo(mutableSetOf()) { isExpanded(it) != expand })
    }

    companion object {
        val Saver = listSaver<GroupExpansion, String>(
            save = { listOf(it.scope, it.expandNew.toString()) + it.exceptions },
            restore = { GroupExpansion(it[0], it[1].toBooleanStrict(), it.drop(2).toSet()) }
        )
    }
}
