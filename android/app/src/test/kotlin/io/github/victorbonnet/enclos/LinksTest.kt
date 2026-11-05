package io.github.victorbonnet.enclos

import org.junit.Assert.assertEquals
import org.junit.Test

class LinksTest {
    private val query = "v=1&host=mac&url=https://mac.tail1.ts.net&key=k&code=c&exp=1"

    @Test
    fun the_pairing_page_link_reads_as_the_app_link() {
        assertEquals("enclos://pair?$query", pairLink("  https://mac.tail1.ts.net/pair#$query\n"))
    }

    @Test
    fun the_app_link_and_anything_else_stay_as_they_are() {
        assertEquals("enclos://pair?$query", pairLink("enclos://pair?$query"))
        assertEquals("https://example.com/a", pairLink("https://example.com/a"))
        // Only the page's own path, right after the address.
        assertEquals("https://example.com/x/pair#$query", pairLink("https://example.com/x/pair#$query"))
    }
}
