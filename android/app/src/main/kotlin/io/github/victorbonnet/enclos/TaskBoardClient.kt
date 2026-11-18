package io.github.victorbonnet.enclos

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.net.URI

internal const val HELD_TASK_BOARD = "held_task.board"
internal const val HELD_TASK_BOARD_LINK = "held_task.board.link"
private const val BOARD_REPLY_TIMEOUT_MS = 30_000L
private val boardPath = Regex("/task-board/open#[A-Za-z0-9_-]{43}")

/** A board link can use only the HTTPS origin established when this host was paired. */
private fun pairedOrigin(address: String): String? = runCatching {
    val uri = URI(address)
    if (uri.scheme != "https" || uri.host == null || uri.rawUserInfo != null ||
        uri.rawQuery != null || uri.rawFragment != null || uri.path !in listOf("", "/") ||
        (uri.port != -1 && uri.port !in 1..65535)
    ) return@runCatching null
    "https://${uri.rawAuthority}"
}.getOrNull()

/** The one-shot request from the visible held-task sheet. No ticket is stored. */
internal class TaskBoardClient(
    private val send: (String, JSONObject) -> Boolean,
    private val current: (HeldTask) -> Boolean,
    private val later: (Long, () -> Unit) -> Unit,
) {
    private class Request(val card: HeldTask, val id: String, val origin: String, val launch: (String) -> Unit)

    private var pending by mutableStateOf<Request?>(null)
    private var problem by mutableStateOf<Pair<HeldTaskKey, String>?>(null)

    fun isOpening(key: HeldTaskKey) = pending?.card?.key == key

    fun error(key: HeldTaskKey): String? = problem?.takeIf { it.first == key }?.second

    fun open(card: HeldTask, bridgeAddress: String, launch: (String) -> Unit) {
        if (pending != null || !current(card)) return
        val origin = pairedOrigin(bridgeAddress)
        if (origin == null) {
            problem = card.key to "This host's paired address cannot open a task board."
            return
        }
        val command = message(HELD_TASK_BOARD, "agent" to card.agent, "task" to card.task, "revision" to card.revision)
        val request = Request(card, command.getString("id"), origin, launch)
        problem = null
        pending = request
        if (!send(card.host, command)) {
            fail(request, "Could not ask the host to open the task board. Try again.")
            return
        }
        later(BOARD_REPLY_TIMEOUT_MS) {
            if (pending === request) fail(request, "The host did not reply. Try again.")
        }
    }

    fun received(host: String, message: JSONObject): Boolean {
        val type = message.opt("type")
        if (type != HELD_TASK_BOARD_LINK && type != "error") return false
        val request = pending
        if (request == null || request.card.host != host || request.id != message.opt("of")) {
            return type == HELD_TASK_BOARD_LINK
        }
        if (!current(request.card)) {
            cancel(request.card.key)
            return true
        }
        if (type == "error") {
            fail(request, when (message.opt("code") as? String) {
                "conflict", "not_found" -> "The held task changed. Open the current request to try again."
                "unsupported", "unknown_type" -> "This host cannot open the task board for this agent."
                "not_allowed" -> "This device does not have access to the task board."
                "agent_unavailable" -> "The agent is unavailable. Try again when it is connected."
                "busy" -> "The host is busy opening task boards. Try again shortly."
                else -> "The host could not open the task board. Try again."
            })
            return true
        }
        val path = message.opt("path") as? String
        if (path == null || !boardPath.matches(path)) {
            fail(request, "The host sent an invalid task board link.")
            return true
        }
        pending = null
        request.launch(request.origin + path)
        return true
    }

    fun cancel(key: HeldTaskKey) {
        if (pending?.card?.key == key) pending = null
        if (problem?.first == key) problem = null
    }

    fun clear() {
        pending = null
        problem = null
    }

    fun ended(host: String) {
        val request = pending?.takeIf { it.card.host == host } ?: return
        fail(request, "The connection ended before the task board opened. Try again when connected.")
    }

    private fun fail(request: Request, reason: String) {
        pending = null
        problem = request.card.key to reason
    }
}
