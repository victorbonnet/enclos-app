package io.github.victorbonnet.enclos

import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONArray
import org.json.JSONObject

/** Requests keep their original controls and authority; this is only their inbox index. */
data class OpenItem(
    val host: String, val id: String, val kind: String, val agent: String?,
    val conversation: String?, val summary: String, val since: Long, val expired: Boolean,
) {
    val key get() = "$host/$kind/${agent.orEmpty()}/$id"
    fun json() = JSONObject().put("id", id).put("kind", kind).put("agent", agent)
        .put("conversation", conversation).put("summary", summary).put("since", since).put("expired", expired)
    companion object {
        fun from(host: String, value: JSONObject): OpenItem {
            val id = value.getString("id")
            val kind = value.getString("kind")
            require(value.isNull("agent") || value.get("agent") is String)
            require(value.isNull("conversation") || value.get("conversation") is String)
            val agent = (value.opt("agent") as? String)?.takeIf { it.isNotBlank() }
            val conversation = (value.opt("conversation") as? String)?.takeIf { it.isNotBlank() }
            val summary = value.getString("summary")
            require(id.isNotBlank() && id.toByteArray().size <= 128 && !Visible.hasHidden(id))
            require(kind in setOf("question", "runtime_approval", "held_task", "policy_request"))
            require(agent == null || (agent.toByteArray().size <= 512 && !Visible.hasHidden(agent)))
            require(conversation == null || (conversation.toByteArray().size <= 128 && !Visible.hasHidden(conversation)))
            require(summary.toByteArray().size <= 1200 && !Visible.hasHidden(summary))
            val rawSince = value.get("since")
            require(rawSince is Long || rawSince is Int)
            require(value.get("expired") is Boolean)
            require(kind == "policy_request" || (agent != null && conversation != null))
            return OpenItem(host, id, kind, agent, conversation, summary, (rawSince as Number).toLong().also { require(it >= 0) }, value.getBoolean("expired"))
        }
    }
}

/** A complete newer snapshot replaces one host atomically, never another host's rows. */
class NeedsYou(private val changed: (String, List<OpenItem>) -> Unit = { _, _ -> }) {
    val items = mutableStateMapOf<String, OpenItem>()
    val staleHosts = mutableStateMapOf<String, Boolean>()
    private data class Incoming(val revision: Long, val items: MutableMap<String, OpenItem>)
    private val incoming = mutableMapOf<String, Incoming>()
    private val revisions = mutableMapOf<String, Long>()
    fun restore(rows: List<OpenItem>) { rows.forEach { items[it.key] = it } }
    fun authenticated(host: String) { revisions.remove(host); incoming.remove(host) }
    fun ended(host: String) { incoming.remove(host) }
    fun forgetConversation(host: String, conversation: String) { items.entries.removeAll { it.value.host == host && it.value.conversation == conversation } }
    fun forget(host: String) { ended(host); revisions.remove(host); staleHosts.remove(host); items.entries.removeAll { it.value.host == host } }
    fun count(agentAddress: String? = null): Int = if (agentAddress == null) items.size
        else items.values.count { it.agent != null && "${it.host}/${it.agent}" == agentAddress }
    fun received(host: String, frame: JSONObject): Boolean = try {
        receive(host, frame)
    } catch (_: Exception) {
        incoming.remove(host)
        staleHosts[host] = true
        false
    }

    private fun receive(host: String, frame: JSONObject): Boolean {
        val rawRevision = frame.get("revision")
        require(rawRevision is Long || rawRevision is Int)
        val revision = (rawRevision as Number).toLong().also { require(it >= 0) }
        require(frame.get("reset") is Boolean && frame.get("complete") is Boolean)
        if (revision <= (revisions[host] ?: -1)) return false
        require(!frame.has("unavailable") || frame.get("unavailable") is Boolean)
        if (frame.optBoolean("unavailable", false)) {
            staleHosts[host] = true; incoming.remove(host); revisions[host] = revision
            return false
        }
        val partial = incoming[host]
        if (partial != null && revision < partial.revision) return false
        val snapshot = if (frame.getBoolean("reset")) Incoming(revision, mutableMapOf()).also { incoming[host] = it }
            else partial?.takeIf { it.revision == revision } ?: return false
        val values = frame.getJSONArray("items")
        require(values.length() <= 24)
        val parsed = (0 until values.length()).map { OpenItem.from(host, values.getJSONObject(it)) }
        require(snapshot.items.size + parsed.size <= 8192)
        parsed.forEach { snapshot.items[it.key] = it }
        if (!frame.getBoolean("complete")) return false
        val complete = snapshot.items.values.toList()
        items.entries.removeAll { it.value.host == host }
        items.putAll(snapshot.items)
        revisions[host] = revision
        staleHosts.remove(host)
        incoming.remove(host)
        changed(host, complete)
        return true
    }
}

/** Invalid optional metadata leaves the readable message alone. */
fun suggestedReplies(value: Any?): List<String> {
    val array = value as? JSONArray ?: return emptyList()
    if (array.length() !in 1..4) return emptyList()
    val replies = (0 until array.length()).map { array.opt(it) as? String ?: return emptyList() }
    if (replies.any { it.isBlank() || it.toByteArray().size > 256 || Visible.hasHidden(it) } || replies.map(Visible::bare).distinct().size != replies.size) return emptyList()
    return replies
}

fun offeredReplies(line: Line, history: List<Line>): List<String> = line.suggestedReplies.takeIf {
    line.from == "agent" && history.none { other -> other.seq > line.seq && other.from != "agent" && !other.status }
}.orEmpty()

/** A draft only. Its fresh conversation identity keeps older/newer native waiters separate. */
fun questionReplyDraft(question: QuestionInfo, answer: QuestionAnswer): String? {
    if (question.state != QuestionState.Expired || question.approval != null || answer == QuestionAnswer.Skip) return null
    if (runCatching { question.validateAnswer(answer) }.isFailure) return null
    val text = when (answer) {
        is QuestionAnswer.Choices -> answer.indices.joinToString("; ") { question.choices[it] }
        is QuestionAnswer.Text -> answer.text
        else -> return null
    }
    return "Reply to your earlier question:\n${question.text}\n\nAnswer: $text"
}

/** Older bridges still contribute their existing sandbox requests. */
fun AppModel.attentionItems(): List<OpenItem> = (needsYou.items.values + approvals.values.filter { it.attention }.map { a ->
    OpenItem(a.host, a.id, "policy_request", a.agent.takeIf { it.isNotBlank() }?.let { if ('/' in it) it else "${a.sandbox}/$it" }, a.conversation, a.title, a.time, false)
}).distinctBy { it.key }.sortedWith(compareByDescending<OpenItem> { it.since }.thenBy { it.key })
