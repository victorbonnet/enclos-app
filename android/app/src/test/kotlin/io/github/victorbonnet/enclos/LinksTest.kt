package io.github.victorbonnet.enclos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun a_page_link_is_confirmed_by_its_ascii_host_and_whole_address() {
        assertEquals(
            LinkDestination("xn--bcher-kva.de", "https://xn--bcher-kva.de/%C3%A9t%C3%A9?q=%C3%A9#x"),
            WebLinks.destination("https://bücher.de/été?q=é#x"),
        )
        // A Cyrillic "а" cannot pass for the Latin one.
        assertEquals("xn--pple-43d.com", WebLinks.destination("https://аpple.com/")?.host)
        assertEquals(
            LinkDestination("example.com", "http://example.com:8080/a?d=secret"),
            WebLinks.destination("HTTP://Example.COM:8080/a?d=secret"),
        )
        assertEquals("[::1]", WebLinks.destination("http://[::1]:8080/")?.host)
        // An invisible direction mark shows as its bytes, not as nothing.
        assertEquals("https://example.com/a%E2%80%AEb", WebLinks.destination("https://example.com/a\u202Eb")?.address)
        listOf("javascript:alert(1)", "intent://open", "https://user:pw@example.com/").forEach {
            assertEquals(it, null, WebLinks.destination(it))
        }
    }

    @Test
    fun an_address_longer_than_the_confirmation_shows_is_refused() {
        val path = "a".repeat(WebLinks.MAX_ADDRESS - "https://example.com/".length)
        assertEquals("https://example.com/$path", WebLinks.destination("https://example.com/$path")?.address)
        assertNull(WebLinks.destination("https://example.com/${path}a"))
        assertNull(WebLinks.destination("https://example.com/?d=" + "x".repeat(2_000_000)))
        // Percent-encoding makes the address longer than the link: what is shown counts.
        assertNull(WebLinks.destination("https://example.com/" + "\u00E9".repeat(WebLinks.MAX_ADDRESS / 3)))
    }

    @Test
    fun a_message_link_opens_at_once_only_when_its_text_is_the_address_sent() {
        assertEquals(MessageLink.Open("https://enclos.dev/a?b=c"), WebLinks.fromMessage("https://enclos.dev/a?b=c", "https://enclos.dev/a?b=c"))
        // A bare www link reads without its scheme.
        assertEquals(MessageLink.Open("http://www.example.com"), WebLinks.fromMessage("www.example.com", "http://www.example.com"))
        assertEquals(MessageLink.Open("https://example.com/"), WebLinks.fromMessage("https://example.com", "https://example.com/"))
    }

    @Test
    fun a_message_link_whose_text_hides_its_address_is_confirmed_first() {
        val leak = "https://collector.example/?d=what-it-read"
        assertEquals(MessageLink.Confirm(LinkDestination("collector.example", leak)), WebLinks.fromMessage("Open the report", leak))
        assertEquals(MessageLink.Confirm(LinkDestination("collector.example", leak)), WebLinks.fromMessage("https://example.com", leak))
        // An image's alt text, and an address the network spells otherwise, are not the address either.
        assertTrue(WebLinks.fromMessage("chart \u2197", "https://example.com/c.png") is MessageLink.Confirm)
        assertEquals(
            MessageLink.Confirm(LinkDestination("xn--pple-43d.com", "https://xn--pple-43d.com/")),
            WebLinks.fromMessage("https://\u0430pple.com/", "https://\u0430pple.com/"),
        )
    }

    @Test
    fun a_message_link_the_confirmation_cannot_show_is_refused() {
        assertEquals(MessageLink.Refused, WebLinks.fromMessage("report", "https://example.com/?d=" + "x".repeat(WebLinks.MAX_ADDRESS)))
        assertEquals(MessageLink.Refused, WebLinks.fromMessage("x", "javascript:alert(1)"))
    }
}
