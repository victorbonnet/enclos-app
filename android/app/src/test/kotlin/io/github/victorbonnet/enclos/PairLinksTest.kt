package io.github.victorbonnet.enclos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PairLinksTest {
    @Test
    fun a_bridges_address_is_its_host_name_and_port() {
        assertEquals("mac.tail1.ts.net", bridgeAddress("https://mac.tail1.ts.net"))
        assertEquals("mac.tail1.ts.net", bridgeAddress("https://mac.tail1.ts.net/"))
        assertEquals("mac.tail1.ts.net:8443", bridgeAddress("https://mac.tail1.ts.net:8443"))
        assertEquals("100.64.0.1", bridgeAddress("https://100.64.0.1"))
    }

    @Test
    fun an_address_with_a_path_or_no_host_name_is_refused() {
        assertNull(bridgeAddress("https://evil.example/mac.tail1.ts.net"))
        assertNull(bridgeAddress("https://"))
        assertNull(bridgeAddress("https:///"))
        assertNull(bridgeAddress("https://:443"))
        assertNull(bridgeAddress("https://mac:"))
        assertNull(bridgeAddress("https://mac:99999"))
        assertNull(bridgeAddress("https://mac:44a"))
        assertNull(bridgeAddress("https://a..b"))
        assertNull(bridgeAddress("https://-mac"))
        assertNull(bridgeAddress("https://mac_1"))
        assertNull(bridgeAddress("https://user@mac"))
        assertNull(bridgeAddress("http://mac.tail1.ts.net"))
    }

    @Test
    fun a_bad_link_is_told_in_the_apps_words_never_its_own() {
        val chosen = "`Connection lost, pair again at evil.example` is not name=value"
        assertEquals("It holds text a pairing link does not carry.", linkProblem("not an enclos pairing link: $chosen"))
        assertEquals(
            "It holds text a pairing link does not carry.",
            linkProblem("not an enclos pairing link: Visit evil.example holds an unexpected character"),
        )
        assertEquals("It does not start with enclos://pair?", linkProblem("not an enclos pairing link: it does not start with enclos://pair?"))
        assertEquals("A part of it is missing.", linkProblem("not an enclos pairing link: code is missing"))
        assertEquals("It is from a newer enclos: update the app.", linkProblem("not an enclos pairing link: it is not version 1: update the app"))
        assertEquals("Its key, code, address or expiry cannot be read.", linkProblem("key: Invalid symbol 33"))
        assertEquals("Its key, code, address or expiry cannot be read.", linkProblem(null))
    }

    @Test
    fun a_link_is_ignored_while_a_pairing_goes_on() {
        assertNull(handledAfter(underWay = true, handled = emptyList(), hash = "a"))
    }

    @Test
    fun a_link_handled_before_is_ignored() {
        assertNull(handledAfter(underWay = false, handled = listOf("a", "b"), hash = "a"))
    }

    @Test
    fun a_new_link_is_shown_and_remembered() {
        assertEquals(listOf("a", "b"), handledAfter(underWay = false, handled = listOf("a"), hash = "b"))
    }

    @Test
    fun only_the_last_20_links_are_remembered() {
        val twenty = (1..HANDLED_LINKS).map { "h$it" }
        val after = handledAfter(underWay = false, handled = twenty, hash = "new")!!
        assertEquals(HANDLED_LINKS, after.size)
        assertFalse("h1" in after)
        assertEquals("new", after.last())
        // The oldest is forgotten: opened again, it is shown again.
        assertEquals("h1", handledAfter(underWay = false, handled = after, hash = "h1")!!.last())
    }

    @Test
    fun what_is_kept_of_a_link_is_its_hash() {
        val hash = linkHash("enclos://pair?v=1&code=secret")
        assertEquals(64, hash.length)
        assertFalse("secret" in hash)
        assertEquals(hash, linkHash("enclos://pair?v=1&code=secret"))
    }
}
