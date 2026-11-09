package io.github.victorbonnet.enclos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun browser_links_need_a_web_scheme_and_an_unambiguous_host() {
        listOf("https://enclos.dev/path?q=a#part", "http://localhost:8310/", "HTTPS://example.com", "http://[::1]:8080/", "https://bücher.de/été")
            .forEach { assertTrue(it, WebLinks.accepts(it)) }
        listOf("javascript:alert(1)", "content://app/private", "file:///private", "intent://open", "mailto:a@example.com", "//example.com", "https:/example.com", "https://", "https://user:password@example.com", "https://example.com\\@other.test", "https://example.com\n", "http://example.com:70000")
            .forEach { assertFalse(it, WebLinks.accepts(it)) }
    }

    @Test
    fun pages_can_open_only_a_deliberate_main_frame_web_navigation() {
        val url = "https://enclos.dev"
        val document = ArtefactDocument(byteArrayOf()).url
        assertTrue(WebLinks.opensFromPage(url, document, mainFrame = true, hasGesture = true, redirect = false))
        assertFalse(WebLinks.opensFromPage(url, document, mainFrame = false, hasGesture = true, redirect = false))
        assertFalse(WebLinks.opensFromPage(url, document, mainFrame = true, hasGesture = false, redirect = false))
        assertFalse(WebLinks.opensFromPage(url, document, mainFrame = true, hasGesture = true, redirect = true))
        assertFalse(WebLinks.opensFromPage("file:///private", document, mainFrame = true, hasGesture = true, redirect = false))
        assertFalse(WebLinks.opensFromPage("mailto:a@example.com", document, mainFrame = true, hasGesture = true, redirect = false))
        assertFalse(WebLinks.opensFromPage("https://artefact.invalid/relative-link", document, mainFrame = true, hasGesture = true, redirect = false))
        assertFalse(WebLinks.opensFromPage("http://ARTEFACT.INVALID:8080/relative-link", document, mainFrame = true, hasGesture = true, redirect = false))
        assertFalse(WebLinks.opensFromPage("https://artefact.invalid./relative-link", document, mainFrame = true, hasGesture = true, redirect = false))
    }
}
