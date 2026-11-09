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
}

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
