package io.github.victorbonnet.enclos

import io.github.victorbonnet.enclos.core.PairLink
import io.github.victorbonnet.enclos.core.parsePairLink
import java.security.MessageDigest

/** A pairing link as the app reads it: what the core parsed, and the bridge's [address] (`name[:port]`). */
class ReadLink(val link: PairLink, val address: String) {
    /** The address the app connects to, as it was shown. */
    val url: String get() = "https://$address"
}

/**
 * Reads a pairing link once, for the card that shows it and for the pairing
 * itself, so both see the same host and address. Fails with the app's own
 * reason ([linkProblem]).
 */
fun readPairLink(link: String): Result<ReadLink> {
    val parsed = try {
        parsePairLink(link)
    } catch (e: Exception) {
        return Result.failure(IllegalArgumentException(linkProblem(e.message)))
    }
    val address = bridgeAddress(parsed.url)
        ?: return Result.failure(IllegalArgumentException("Its address is not a bridge's: it has a path, a query or no host name."))
    return Result.success(ReadLink(parsed, address))
}

/**
 * The bridge's address in a pairing link's `url`: `name` or `name:port`,
 * after `https://`, with at most a `/` after it. Null for any other URL (a
 * path, a query, credentials, no host name): a bridge's address has none,
 * and the app shows the person, whole, the address it connects to.
 */
internal fun bridgeAddress(url: String): String? {
    if (!url.startsWith("https://")) return null
    val authority = url.removePrefix("https://").removeSuffix("/")
    val name = authority.substringBefore(':')
    val port = authority.substringAfter(':', missingDelimiterValue = "")
    val nameOk = name.isNotEmpty() &&
        name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '.' } &&
        name.split('.').none { it.isEmpty() || it.startsWith('-') || it.endsWith('-') }
    val portOk = ':' !in authority || (port.length in 1..5 && port.all { it in '0'..'9' } && port.toInt() in 1..65535)
    return if (nameOk && portOk) authority else null
}

/**
 * Why a link is not a pairing link, in the app's own words: the core's
 * reason can quote the link's text, which whoever made the link chose.
 */
internal fun linkProblem(reason: String?): String {
    val r = reason.orEmpty()
    return when {
        "does not start with enclos://pair?" in r -> "It does not start with enclos://pair?"
        "is not version 1" in r -> "It is from a newer enclos: update the app."
        "is missing" in r -> "A part of it is missing."
        "is not name=value" in r || "unexpected character" in r -> "It holds text a pairing link does not carry."
        else -> "Its key, code, address or expiry cannot be read."
    }
}

/** A link's SHA-256, in hex: what the app keeps of a link it handled, not the link and its code. */
internal fun linkHash(link: String): String =
    MessageDigest.getInstance("SHA-256").digest(link.toByteArray()).joinToString("") { "%02x".format(it) }

/** How many handled links' hashes are kept: a link works for 5 minutes only. */
internal const val HANDLED_LINKS = 20

/**
 * Whether a link the system opened the app with is shown: not while a
 * pairing is [underWay], nor when its [hash] is among the [handled] ones
 * (the system hands a link again when it restarts the app's task). Returns
 * the hashes to keep once it is shown, the newest last, or null to ignore it.
 */
internal fun handledAfter(underWay: Boolean, handled: List<String>, hash: String): List<String>? =
    if (underWay || hash in handled) null else handled.takeLast(HANDLED_LINKS - 1) + hash
