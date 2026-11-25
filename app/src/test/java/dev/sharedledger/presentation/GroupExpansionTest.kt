package dev.sharedledger.presentation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupExpansionTest {
    @Test fun manualExpansionStaysWithItsKeyAndNewGroupsStartCollapsed() {
        val initial = GroupExpansion()
        assertFalse(initial.isExpanded("groceries"))
        val expanded = initial.toggle("groceries")
        assertTrue(expanded.isExpanded("groceries"))
        assertFalse(expanded.isExpanded("transport"))
        assertFalse(expanded.isExpanded("from-sync"))
        assertFalse(expanded.toggle("groceries").isExpanded("groceries"))
    }

    @Test fun globalActionsChangeVisibleGroupsAndFutureDefaultsWhilePreservingHiddenGroups() {
        val known = setOf("groceries", "transport", "salary")
        val visible = setOf("groceries", "transport")
        val expanded = GroupExpansion().setVisible(visible, known, expand = true)
        assertTrue(expanded.isExpanded("groceries"))
        assertTrue(expanded.isExpanded("transport"))
        assertFalse(expanded.isExpanded("salary"))
        assertTrue(expanded.isExpanded("from-sync"))
        val collapsed = expanded.setVisible(setOf("transport"), known, expand = false)
        assertTrue(collapsed.isExpanded("groceries"))
        assertFalse(collapsed.isExpanded("transport"))
        assertFalse(collapsed.isExpanded("salary"))
        assertFalse(collapsed.isExpanded("later-sync"))
    }
}
