package io.github.victorbonnet.enclos

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
