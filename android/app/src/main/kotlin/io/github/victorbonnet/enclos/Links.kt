package io.github.victorbonnet.enclos

/**
 * A pairing link as the core reads it, `enclos://pair?…`, from what the
 * person scanned, typed or pasted: that link, or the https link to a
 * bridge's pairing page (`https://<host>/pair#…`, enclos `docs/protocol.md`,
 * Pairing), which carries the same parameters after `#`. The page link is
 * read only when its own address is the `url` it carries, as a bridge
 * prints it: the person checks the address at the start of the link, and
 * the app must connect there. Anything else is returned as it is, for the
 * core to refuse.
 */
fun pairLink(text: String): String {
    val link = text.trim()
    if (!link.startsWith("https://")) return link
    val at = link.indexOf("/pair#")
    if (at < 0) return link
    val authority = link.substring("https://".length, at)
    // The page's path right after the address: not a "/pair#" further down.
    if (authority.contains('/')) return link
    val fragment = link.substring(at + "/pair#".length)
    val url = fragment.split('&').firstOrNull { it.startsWith("url=") }?.removePrefix("url=") ?: return link
    if (!url.removePrefix("https://").removeSuffix("/").equals(authority, ignoreCase = true) || !url.startsWith("https://")) return link
    return "enclos://pair?$fragment"
}
