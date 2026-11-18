package io.github.victorbonnet.enclos

import androidx.compose.runtime.mutableStateMapOf

/** Browser destinations the person saved on this phone, keyed by the paired host and agent. */
internal class TaskBoardLinks(private val keep: (Map<String, String?>) -> Unit) {
    private val links = mutableStateMapOf<String, LinkDestination>()

    fun restore(stored: Map<String, *>) {
        stored.forEach { (key, address) ->
            if (address is String) taskBoardDestination(address)?.let { links[key] = it }
        }
    }

    operator fun get(agent: AgentKey): LinkDestination? = links[agent.address]

    fun set(agent: AgentKey, destination: LinkDestination) {
        links[agent.address] = destination
        keep(mapOf(agent.address to destination.address))
    }

    fun forget(agent: AgentKey) {
        if (links.remove(agent.address) != null) keep(mapOf(agent.address to null))
    }

    fun forget(host: String) {
        val keys = links.keys.filter { it.startsWith("$host/") }
        if (keys.isEmpty()) return
        keys.forEach { links.remove(it) }
        keep(keys.associateWith { null })
    }
}

internal sealed interface TaskBoardRoute {
    data class Saved(val link: LinkDestination) : TaskBoardRoute
    data object Bridge : TaskBoardRoute
    data object UpdateHost : TaskBoardRoute
}

/** A saved browser address is usable independently of the app's bridge connection. */
internal fun taskBoardRoute(connected: Boolean, supportsBoard: Boolean, saved: LinkDestination?): TaskBoardRoute = when {
    supportsBoard -> TaskBoardRoute.Bridge
    saved != null -> TaskBoardRoute.Saved(saved)
    connected -> TaskBoardRoute.UpdateHost
    else -> TaskBoardRoute.Bridge
}
