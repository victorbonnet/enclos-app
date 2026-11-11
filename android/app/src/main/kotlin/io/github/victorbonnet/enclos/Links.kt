package io.github.victorbonnet.enclos

import java.net.URI

/** Admission shared by message links and a page's deliberate navigation. */
object WebLinks {
    fun accepts(url: String): Boolean = runCatching {
        val uri = URI(url)
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.isOpaque) return@runCatching false
        // URL's host accepts international names, which URI.host leaves null.
        val web = uri.toURL()
        !web.host.isNullOrEmpty() && web.userInfo == null && web.port in -1..65535
    }.getOrDefault(false)

    fun opensFromPage(url: String, documentUrl: String, mainFrame: Boolean, hasGesture: Boolean, redirect: Boolean) =
        mainFrame && hasGesture && !redirect && accepts(url) &&
            !URI(url).toURL().host.trimEnd('.').equals(URI(documentUrl).host.trimEnd('.'), ignoreCase = true)

    /**
     * What the person confirms before a page's link leaves the phone: its
     * host as the network spells it (punycode, so `аpple.com` in Cyrillic
     * cannot pass for `apple.com`), and the whole address, non-ASCII
     * percent-encoded, with nothing a page can hide in it.
     */
    fun destination(url: String): LinkDestination? = runCatching {
        if (!accepts(url)) return@runCatching null
        val uri = URI(url)
        val web = uri.toURL()
        val host = if (web.host.startsWith('[')) web.host.lowercase()
            else java.net.IDN.toASCII(web.host.trimEnd('.'), java.net.IDN.ALLOW_UNASSIGNED).lowercase()
        // What follows the authority (path, query, fragment), as the page wrote it.
        val authority = "${uri.scheme}://${uri.rawAuthority}"
        require(url.startsWith(authority))
        val port = if (web.port == -1) "" else ":${web.port}"
        LinkDestination(host, "${uri.scheme.lowercase()}://$host$port${ascii(url.substring(authority.length))}")
    }.getOrNull()

    /** Non-ASCII characters percent-encoded as UTF-8; ASCII as it is. */
    private fun ascii(text: String) = buildString {
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val b = byte.toInt() and 0xff
            if (b < 0x80) append(b.toChar()) else append('%').append("%02X".format(b))
        }
    }
}

/** A web link's host (ASCII) and full address (ASCII), as the confirmation shows them. */
data class LinkDestination(val host: String, val address: String)

/**
 * A pairing link as the core reads it, `enclos://pair?…`, from what the
 * person scanned, typed or pasted: that link, or the https link to a
 * bridge's pairing page (`https://<host>/pair#…`, enclos `docs/protocol.md`,
 * Pairing), which carries the same parameters after `#`. Anything else is
 * returned as it is, for the core to refuse.
 */
fun pairLink(text: String): String {
    val link = text.trim()
    if (!link.startsWith("https://")) return link
    val at = link.indexOf("/pair#")
    // The page's path right after the address: not a "/pair#" further down.
    if (at < 0 || link.substring("https://".length, at).contains('/')) return link
    return "enclos://pair?" + link.substring(at + "/pair#".length)
}
