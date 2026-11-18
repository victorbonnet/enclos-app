package io.github.victorbonnet.enclos.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationFiltersTest {
    @Test
    fun the_button_names_each_filter_that_is_on() {
        assertEquals("No filters active", filtersState(unreadOnly = false, archivedOnly = false))
        assertEquals("Unread only", filtersState(unreadOnly = true, archivedOnly = false))
        assertEquals("Archived conversations", filtersState(unreadOnly = false, archivedOnly = true))
        assertEquals("Unread only and Archived conversations", filtersState(unreadOnly = true, archivedOnly = true))
    }
}
