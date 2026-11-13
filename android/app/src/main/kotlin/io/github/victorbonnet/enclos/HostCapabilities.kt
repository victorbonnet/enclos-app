package io.github.victorbonnet.enclos

import org.json.JSONObject

/** The authenticated connection's whole feature set; an older bridge offers none. */
internal fun hostCapabilitiesIn(message: JSONObject): Set<String> {
    val list = message.optJSONArray("capabilities") ?: return emptySet()
    return (0 until list.length()).mapNotNull { list.opt(it) as? String }.toSet()
}
