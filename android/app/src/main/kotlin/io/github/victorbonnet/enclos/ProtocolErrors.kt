package io.github.victorbonnet.enclos

import org.json.JSONObject

/** An id as the protocol carries one (docs/protocol.md, Names): not empty, at most 128 bytes, no control character. */
internal fun protocolId(value: Any?): String? = (value as? String)?.takeIf {
    it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= 128 && it.none(Char::isISOControl)
}

/**
 * The `error` the app answers a bridge message it cannot use with (enclos
 * docs/protocol.md, Messages): [code] `unknown_type` for a `type` it does not
 * know, `bad_message` for one it cannot read ([unread] null: not even JSON).
 * `of` names the message when its id can be read. The connection goes on.
 * An `error` is never answered, so two peers never trade them.
 */
internal fun answerTo(unread: JSONObject?, code: String): JSONObject? {
    if (unread?.opt("type") == "error") return null
    val text = if (code == "unknown_type") "the enclos app does not know this type" else "the enclos app cannot read this message"
    return message("error", "of" to protocolId(unread?.opt("id")), "code" to code, "message" to text)
}

/** Why the app could not read a message, as the host's screens show it: one line, nothing hidden, at most 200 characters. */
internal fun unreadableShown(why: String?): String =
    Visible.stripped(why.orEmpty().replace(Regex("\\s+"), " ")).trim().ifEmpty { "no reason given" }.take(200)

/** What the host's screens say once the app skipped a message of its bridge it could not read ([why], from [unreadableShown]). */
internal fun unreadableNotice(why: String): String =
    "The app skipped a message from this host it cannot read ($why). Update the app, then Clear cache to fetch it again."
