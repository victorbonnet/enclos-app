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

/** What becomes of a push: shown, or dropped, and why when that is worth a log line. */
sealed interface PushDecision {
    data class Show(val preview: PushPreview) : PushDecision

    /** [why] is null for a push already shown, which is no fault. */
    data class Drop(val why: String?) : PushDecision
}

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
        val sealed = data["preview"]?.let {
            try {
                decode(it)
            } catch (_: Exception) {
                null
            }
        } ?: return null
        return PushData(host, seq, sealed)
    }

    /**
     * Opens [push] with the push key of each paired host, under the push's
     * own host name, the host of that name first: the host whose key opens
     * it is the one it came from. Null when none does, so a push no paired
     * host sealed shows nothing. [open] returns null, or throws, for a key
     * that does not open it; [unwrap] gives a host's push key from the key
     * store, and a host whose key it cannot give is told to [unwrapFailed]
     * and passed over. Only exceptions are caught: an `Error` (a native
     * library missing) is not a key that does not fit.
     */
    fun open(
        push: PushData,
        hosts: List<Host>,
        unwrap: (ByteArray) -> ByteArray,
        open: (key: ByteArray, host: String, seq: Long, sealed: ByteArray) -> ByteArray?,
        unwrapFailed: (Host, Exception) -> Unit = { _, _ -> },
    ): PushPreview? {
        for (host in hosts.sortedBy { if (it.name == push.host) 0 else 1 }) {
            val key = try {
                unwrap(host.pushKeyWrapped)
            } catch (e: Exception) {
                unwrapFailed(host, e)
                continue
            }
            val plain = try {
                open(key, push.host, push.seq, push.sealed)
            } catch (_: Exception) {
                null
            } ?: continue
            // The key fits: this host sealed it, whatever its preview holds.
            return preview(host.name, push.seq, plain)
        }
        return null
    }

    /** The preview's JSON, or null when it is not one the bridge makes. */
    fun preview(host: String, seq: Long, plain: ByteArray): PushPreview? {
        val json = try {
            JSONObject(String(plain, Charsets.UTF_8))
        } catch (_: Exception) {
            return null
        }
        val agent = json.optString("agent").takeIf { it.isNotEmpty() && it.length <= ID_CHARS && !Visible.hasHidden(it) } ?: return null
        val conversation = json.optString("conversation").takeIf { it.isNotEmpty() && it.length <= ID_CHARS && !Visible.hasHidden(it) } ?: return null
        return PushPreview(host, agent, conversation, json.optString("text"), seq)
    }

    /**
     * What becomes of FCM's [data]: shown, or dropped. [lastShown] gives the
     * `seq` last shown in a host's conversation; a push not newer than it is
     * dropped, while one for another conversation is not, however FCM orders
     * them.
     */
    fun decide(
        data: Map<String, String>,
        hosts: List<Host>,
        decode: (String) -> ByteArray,
        unwrap: (ByteArray) -> ByteArray,
        open: (ByteArray, String, Long, ByteArray) -> ByteArray?,
        lastShown: (host: String, conversation: String) -> Long?,
        unwrapFailed: (Host, Exception) -> Unit = { _, _ -> },
    ): PushDecision {
        val push = parse(data, decode) ?: return PushDecision.Drop("a push the app cannot read (version ${data["v"]})")
        val preview = open(push, hosts, unwrap, open, unwrapFailed)
            ?: return PushDecision.Drop("a push no paired host opens")
        if (seen(preview, lastShown(preview.host, preview.conversation))) return PushDecision.Drop(null)
        return PushDecision.Show(preview)
    }

    /**
     * The notification's words: the preview's, without the markdown's marks
     * or hidden characters, at most [PREVIEW_CHARS] characters (cut between
     * two, never inside one); [GENERIC] when [locked] (the app lock is on:
     * its content stays behind it) or when nothing is left.
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

    /** A push already shown, or older than one shown in its conversation. */
    fun seen(push: PushPreview, lastShown: Long?): Boolean = lastShown != null && push.seq <= lastShown

    /** The key under which the `seq` last shown in a host's conversation is kept. */
    fun shownKey(host: String, conversation: String) = "shown.$host/$conversation"

    /** The prefix of every [shownKey] of [host]: a host's name holds no `/`. */
    fun shownPrefix(host: String) = "shown.$host/"

    /** The notification channel of [agent] on [host]: one per agent. */
    fun channel(host: String, agent: String) = "agent:$host/$agent"

    /** The prefix of every [channel] of [host]. */
    fun channelPrefix(host: String) = "agent:$host/"

    /**
     * A conversation's notification, within its host's (the notification's
     * tag): a new message replaces the last.
     */
    fun notificationId(conversation: String) = conversation.hashCode()

    /** The `push.token` message, or a pairing request's `push`, for [token]. */
    fun token(token: String): JSONObject = JSONObject().put("kind", "fcm").put("token", token)
}
