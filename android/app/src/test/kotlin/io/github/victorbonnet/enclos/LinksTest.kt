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

    @Test
    fun a_page_link_that_names_another_address_stays_as_it_is() {
        val other = "v=1&host=mac&url=https://other.example&key=k&code=c&exp=1"
        assertEquals("https://mac.tail1.ts.net/pair#$other", pairLink("https://mac.tail1.ts.net/pair#$other"))
        // The same host name on another port is another address.
        assertEquals("https://mac.tail1.ts.net:8443/pair#$query", pairLink("https://mac.tail1.ts.net:8443/pair#$query"))
    }

    @Test
    fun a_page_link_with_its_port_reads_when_the_url_has_it_too() {
        val q = "v=1&host=mac&url=https://mac.tail1.ts.net:8443&key=k&code=c&exp=1"
        assertEquals("enclos://pair?$q", pairLink("https://mac.tail1.ts.net:8443/pair#$q"))
    }

    @Test
    fun a_page_link_with_no_parameters_or_a_query_stays_as_it_is() {
        assertEquals("https://mac.tail1.ts.net/pair#", pairLink("https://mac.tail1.ts.net/pair#"))
        assertEquals("https://mac.tail1.ts.net/pair?a=b#$query", pairLink("https://mac.tail1.ts.net/pair?a=b#$query"))
    }
}
