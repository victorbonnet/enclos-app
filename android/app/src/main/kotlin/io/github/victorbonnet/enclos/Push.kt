package io.github.victorbonnet.enclos

import io.github.victorbonnet.enclos.ui.Markdown
import org.json.JSONObject

/**
 * A push from a host's bridge (enclos `docs/protocol.md`, Notifications):
 * FCM's data, all strings, with the preview sealed for one paired device.
 */
data class PushData(val host: String, val seq: Long, val sealed: ByteArray) {
    override fun equals(other: Any?) =
        other is PushData && host == other.host && seq == other.seq && sealed.contentEquals(other.sealed)

    override fun hashCode() = 31 * (31 * host.hashCode() + seq.hashCode()) + sealed.contentHashCode()
}

/** What a push's preview says, once opened by the host it came from. */
data class PushPreview(val host: String, val agent: String, val conversation: String, val text: String, val seq: Long)

object Push {
    /** The protocol version the app reads. */
    const val VERSION = "1"

    /** The most characters of a preview's text the bridge sends. */
    const val PREVIEW_CHARS = 120

    /** The protocol bounds a host name in a pairing link at 63 characters; longer is not one. */
    private const val HOST_CHARS = 63

    /** The most an agent's or a conversation's id may be, as the bridge's ids. */
    private const val ID_CHARS = 256

    /** What a notification says when the app lock is on, or the preview has no words. */
    const val GENERIC = "New message"

    /**
     * FCM's [data] as a push, or null for anything else: another version,
     * a host name or `seq` that is not one, a preview that is not base64url.
     */
    fun parse(data: Map<String, String>, decode: (String) -> ByteArray): PushData? {
        if (data["v"] != VERSION) return null
        val host = data["host"]?.takeIf { it.isNotEmpty() && it.length <= HOST_CHARS } ?: return null
        val seq = data["seq"]?.toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val sealed = data["preview"]?.let { runCatching { decode(it) }.getOrNull() } ?: return null
        return PushData(host, seq, sealed)
    }

    /**
     * Opens [push] with the push key of each paired host, under the push's
     * own host name: the host whose key opens it is the one it came from.
     * Null when none does, so a push no paired host sealed shows nothing.
     * [open] throws, or returns null, for a key that does not open it;
     * [unwrap] gives a host's push key from the key store.
     */
    fun open(
        push: PushData,
        hosts: List<Host>,
        unwrap: (ByteArray) -> ByteArray,
        open: (key: ByteArray, host: String, seq: Long, sealed: ByteArray) -> ByteArray?,
    ): PushPreview? {
        // The host of that name first: it is the one that sent it, unless
        // two hosts share a name, which pairing does not allow.
        val ordered = hosts.sortedBy { if (it.name == push.host) 0 else 1 }
        for (host in ordered) {
            val plain = runCatching { open(unwrap(host.pushKeyWrapped), push.host, push.seq, push.sealed) }.getOrNull() ?: continue
            return preview(host.name, push.seq, plain)
        }
        return null
    }

    /** The preview's JSON, or null when it is not one the bridge makes. */
    fun preview(host: String, seq: Long, plain: ByteArray): PushPreview? {
        val json = runCatching { JSONObject(String(plain, Charsets.UTF_8)) }.getOrNull() ?: return null
        val agent = json.optString("agent").takeIf { it.isNotEmpty() && it.length <= ID_CHARS && !Visible.hasHidden(it) } ?: return null
        val conversation = json.optString("conversation").takeIf { it.isNotEmpty() && it.length <= ID_CHARS && !Visible.hasHidden(it) } ?: return null
        return PushPreview(host, agent, conversation, json.optString("text"), seq)
    }

    /**
     * The notification's words: the preview's, without the markdown's marks
     * or hidden characters, cut to [PREVIEW_CHARS]; [GENERIC] when [locked]
     * (the app lock is on: its content stays behind it) or when nothing is
     * left.
     */
    fun shown(text: String, locked: Boolean): String {
        if (locked) return GENERIC
        val words = Visible.stripped(Markdown.plain(text)).replace(Regex("\\s+"), " ").trim()
        return when {
            words.isEmpty() -> GENERIC
            words.codePointCount(0, words.length) <= PREVIEW_CHARS -> words
            else -> words.substring(0, words.offsetByCodePoints(0, PREVIEW_CHARS - 1)).trimEnd() + "…"
        }
    }

    /** A push already shown, or older than one shown: a host's `seq` only grows. */
    fun seen(push: PushPreview, lastShown: Long?): Boolean = lastShown != null && push.seq <= lastShown

    /** The notification channel of [agent] on [host]: one per agent. */
    fun channel(host: String, agent: String) = "agent:$host/$agent"

    /** One notification per conversation: a new message replaces the last. */
    fun notificationId(host: String, conversation: String) = "$host/$conversation".hashCode()

    /** The `push.token` message, or a pairing request's `push`, for [token]. */
    fun token(token: String): JSONObject = JSONObject().put("kind", "fcm").put("token", token)
}
