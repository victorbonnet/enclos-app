package io.github.victorbonnet.enclos

import org.json.JSONException
import org.json.JSONObject

/** The protocol version the app speaks: every message's `v` (enclos docs/protocol.md, Versions). */
internal const val PROTOCOL_VERSION = 1L

/**
 * Every `type` the app knows from a bridge: those `AppModel.received` and its
 * feature clients read, and the pairing answers. It is enclos-protocol's
 * `TO_DEVICE_TYPES`: a JVM test checks it against a copy of that list
 * (`to-device-types.txt`), and core-ffi's tests check the copy against the
 * pinned crate. Any other type is answered `unknown_type` ([inbound]).
 */
internal val BRIDGE_TYPES: Set<String> = setOf(
    "ack", "agent.avatar", "agent.name", "agents.list", "approval", "attachment.data", "audit.list",
    "authenticated", "commands", "conversation", "conversation.deleted", "error", "group.state", "message",
    "operation.progress", "pair.accepted", "pair.refused", "question", "question.result", "routine.response",
    "runtime.installed", "runtimes.list", "sandbox.created", "sandbox.policy", "sandbox.policy.preview",
    "sandboxes.list", "synced", "tool", "typing",
)

/** Why the app skipped a message of its bridge, as the host's screens show it ([unreadableShown]), and whether it was a history `message`. */
data class Unreadable(val why: String, val history: Boolean)

/** What the app does with one message of its bridge before acting on it (enclos docs/protocol.md, Messages). */
internal sealed interface Inbound {
    /** A message of a type the app knows, in the version it speaks: read it. */
    data class Use(val message: JSONObject) : Inbound

    /**
     * Send [answer] (none to an `error`), skip the message and go on reading.
     * [unreadable] says why, for the host's screens; it is null for a type
     * the app does not know, which is only ignored, so a newer bridge can
     * send optional messages.
     */
    data class Skip(val answer: JSONObject?, val unreadable: Unreadable?) : Inbound

    /** Send [answer], then end the connection saying [why]: a `v` the app does not speak. */
    data class End(val answer: JSONObject?, val why: String) : Inbound
}

/**
 * Sorts a decrypted message of the bridge as enclos-protocol's `decode`
 * does: not JSON, no integer `v` or no `type` is a bad message; another `v`
 * ends the connection; an unknown `type` is answered `unknown_type`. A
 * message of a known type with a field missing or wrong is found only as it
 * is read ([readOrSkip]).
 */
internal fun inbound(plain: String): Inbound {
    val m = try {
        JSONObject(plain)
    } catch (e: JSONException) {
        return Inbound.Skip(answerTo(null, "bad_message"), skipped(null, e.message))
    }
    // An integer, as JSON numbers without a fraction are read; not 1.0 or "1".
    val v = m.opt("v")
    val version = (if (v is Int) v.toLong() else v as? Long)?.takeIf { it >= 0 }
    if (version == null) return Inbound.Skip(answerTo(m, "bad_message"), skipped(m, "no version"))
    if (version != PROTOCOL_VERSION) {
        val update = if (version > PROTOCOL_VERSION) "the app" else "enclos on the host"
        return Inbound.End(answerTo(m, "version"), "the bridge speaks protocol version $version, the app $PROTOCOL_VERSION: update $update")
    }
    val type = m.opt("type") as? String
        ?: return Inbound.Skip(answerTo(m, "bad_message"), skipped(m, "no type"))
    if (type !in BRIDGE_TYPES) return Inbound.Skip(answerTo(m, "unknown_type"), null)
    return Inbound.Use(m)
}

/**
 * Reads [m] with [read]; a field missing or wrong ([JSONException]) skips
 * it, answered `bad_message`, and the connection goes on. Null once read.
 */
internal fun readOrSkip(m: JSONObject, read: (JSONObject) -> Unit): Inbound.Skip? = try {
    read(m)
    null
} catch (e: JSONException) {
    Inbound.Skip(answerTo(m, "bad_message"), skipped(m, e.message))
}

private fun skipped(m: JSONObject?, why: String?) = Unreadable(unreadableShown(why), history = m?.opt("type") == "message")

/** An id as the protocol carries one (docs/protocol.md, Names): not empty, at most 128 bytes, no control character. */
internal fun protocolId(value: Any?): String? = (value as? String)?.takeIf {
    it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= 128 && it.none(Char::isISOControl)
}

/**
 * The `error` the app answers a bridge message it cannot use with (enclos
 * docs/protocol.md, Messages): [code] `unknown_type` for a `type` it does not
 * know, `bad_message` for one it cannot read ([unread] null: not even JSON),
 * `version` for a `v` it does not speak. `of` names the message when its id
 * can be read. An `error` is never answered, so two peers never trade them.
 */
internal fun answerTo(unread: JSONObject?, code: String): JSONObject? {
    if (unread?.opt("type") == "error") return null
    val text = when (code) {
        "unknown_type" -> "the enclos app does not know this type"
        "version" -> "the enclos app speaks version $PROTOCOL_VERSION"
        else -> "the enclos app cannot read this message"
    }
    return message("error", "of" to protocolId(unread?.opt("id")), "code" to code, "message" to text)
}

/** Why the app could not read a message, as the host's screens show it: one line, nothing hidden, at most 200 characters. */
internal fun unreadableShown(why: String?): String =
    Visible.stripped(why.orEmpty().replace(Regex("\\s+"), " ")).trim().ifEmpty { "no reason given" }.take(200)

/**
 * What the host's screens say once the app skipped a message of its bridge.
 * Only a history `message` needs Clear cache: a sync starts after the newest
 * message kept, so a skipped one is not fetched again otherwise.
 */
internal fun unreadableNotice(skipped: Unreadable): String =
    "The app skipped a message from this host it cannot read (${skipped.why}). " +
        if (skipped.history) "Update the app, then Clear cache to fetch it again." else "Update the app."
