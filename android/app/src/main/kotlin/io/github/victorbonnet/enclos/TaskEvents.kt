package io.github.victorbonnet.enclos

import org.json.JSONObject

/** The device capability that says this app draws a runtime's task events as task lines (enclos ADR 0071). */
internal const val TASK_EVENTS = "task_events"

/** The `kind` of a message that is a task event. */
internal const val TASK_EVENT = "task_event"

/**
 * What happened to a task, as a task line says it (enclos `docs/protocol.md`,
 * Task events); [Changed] is also what an event a later version adds reads as.
 */
enum class TaskEventKind(val wire: String, val label: String) {
    Blocked("blocked", "blocked"),
    GaveUp("gave_up", "gave up"),
    Crashed("crashed", "crashed"),
    TimedOut("timed_out", "timed out"),
    Done("done", "done"),
    Review("review", "ready for review"),
    ChangesRequested("changes_requested", "changes requested"),
    Triage("triage", "sent to triage"),
    Changed("changed", "changed");

    companion object {
        fun of(wire: String): TaskEventKind = entries.firstOrNull { it.wire == wire } ?: Changed
    }
}

/**
 * A task event: a change of a task that its runtime announced to a
 * conversation (Hermes's kanban ping), drawn as a task line, never as the
 * agent talking. Every field comes from the sandbox: shown as plain text, on
 * one line, never as markdown or links.
 */
data class TaskEvent(
    /** The runtime's id of the task, as `tasks` and `task.get` name it. */
    val task: String,
    val title: String,
    /** The agent doing it, `sandbox/agent`, when it has one. */
    val assignee: String?,
    val event: TaskEventKind,
    /** Where the task is now, when the runtime says one the app knows. */
    val state: TaskState?,
) {
    /** As the phone keeps it: the wire's fields. */
    fun json(): JSONObject = JSONObject().put("task", task).put("title", title).put("event", event.wire)
        .apply {
            assignee?.let { put("assignee", it) }
            state?.let { put("state", it.wire) }
        }

    companion object {
        /**
         * The task event [value] holds, read leniently and cleaned again to
         * the bridge's bounds: none without a task id (one without a hidden
         * character), a title or an event; an assignee that is not
         * `sandbox/agent` is left out, an event the app does not know reads
         * as [TaskEventKind.Changed] and a state it does not know as none.
         */
        fun from(value: Any?): TaskEvent? {
            val o = value as? JSONObject ?: return null
            val task = protocolId(o.opt("task"))?.takeUnless { Visible.hasHidden(it) } ?: return null
            val title = (o.opt("title") as? String)?.let { oneLine(it, AgentTask.MAX_TITLE) }?.ifEmpty { null } ?: return null
            val event = (o.opt("event") as? String)?.let(TaskEventKind::of) ?: return null
            return TaskEvent(
                task = task,
                title = title,
                assignee = AgentTask.agentName(o.opt("assignee"))?.takeUnless { Visible.hasHidden(it) },
                event = event,
                state = (o.opt("state") as? String)?.let(TaskState::of),
            )
        }
    }
}

/**
 * A message's kind and task event, as the app keeps and draws it: `task_event`
 * only with a task event it can read; one whose details are missing or
 * unreadable is a `notify`, its text alone, as an app without task lines
 * shows it. A task event on any other kind is left out.
 */
internal fun taskEventOf(kind: String, value: Any?): Pair<String, TaskEvent?> {
    if (kind != TASK_EVENT) return kind to null
    val event = TaskEvent.from(value) ?: return "notify" to null
    return TASK_EVENT to event
}

/** A task event's line: the runtime about one of its tasks, not the agent talking. */
val Line.taskLine: Boolean get() = taskEvent != null

/**
 * The runtime speaking, not the agent: a status line or a task line. Quiet:
 * never unread, and the inbox previews the last other message when there is one.
 */
val Line.quiet: Boolean get() = status || taskLine

/**
 * A task line's words, without its time: the task's id and title, its
 * assignee as the app names it ([assignee]; none when the event names none),
 * what happened, and where the task is now when that says more
 * (`t_a09759c7 S2a · Reviewer · sent to triage → queued`).
 */
fun taskEventText(event: TaskEvent, assignee: String?): String = "${taskEventSubject(event)} · ${taskEventWhat(event, assignee)}"

/** The task a task line is about: its id and title (`t_a09759c7 S2a`). */
fun taskEventSubject(event: TaskEvent): String = "${event.task} ${event.title}"

/** What a task line says happened: its assignee when it has one, the event, and the state when that says more (`Reviewer · sent to triage → queued`). */
fun taskEventWhat(event: TaskEvent, assignee: String?): String = buildString {
    assignee?.let { append(it).append(" · ") }
    append(event.event.label)
    event.state?.takeUnless { it.wire == event.event.wire }?.let { append(" → ").append(it.wire) }
}

/**
 * The task a task line of [host] opens: the one this phone keeps from the
 * bridge's `tasks` ([kept]) under the event's assignee and id. None without an
 * assignee, or when the phone does not keep that task (the bridge would answer
 * `task.get` with `not_found`): tasks reach the host's person only, so only
 * they are offered Open task.
 */
fun taskOpenedBy(host: String, event: TaskEvent, kept: (TaskKey) -> AgentTask?): TaskKey? {
    val assignee = event.assignee ?: return null
    return kept(TaskKey(host, assignee.substringBefore('/'), event.task))?.takeIf { it.assignee == assignee }?.key
}
