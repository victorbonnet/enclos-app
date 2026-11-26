package io.github.victorbonnet.enclos

/*
 * What the agents view shows (docs/spec.md, Agents): the total of the agents
 * this phone lists for its paired hosts, and the hosts, sandboxes and agents
 * a search and Working only leave. AppModel's state goes in; nothing here
 * keeps any.
 */

/**
 * Whether the agent is working on [conversation]: from the person's message
 * until the agent answers ([awaiting]), while its runtime says it is writing
 * ([typing]) or runs a tool not done ([steps]), and while its runtime reports
 * it answering there ([activities]).
 */
internal fun conversationWorking(
    conversation: String,
    typing: Map<String, Boolean>,
    awaiting: Map<String, Boolean>,
    steps: Map<String, List<Step>>,
    conversations: List<Conversation>,
    activities: Activities,
): Boolean =
    typing[conversation] == true || awaiting[conversation] == true || steps[conversation].orEmpty().any { !it.done } ||
        conversations.firstOrNull { it.id == conversation }?.let { activities.answering(it.host, conversation) } == true

/**
 * Whether an agent shows at work: a conversation it is working on, or what
 * its runtime reports it doing. Its row and Working only both decide by
 * this, so they never disagree; no task record, usage, capability,
 * connection or recent message adds to it.
 */
fun showsWorking(workingOn: List<Conversation>, activity: AgentActivity?): Boolean = workingOn.isNotEmpty() || activity != null

/**
 * The agents this phone lists for its [paired] hosts, each once: an agent is
 * its host and its `sandbox/agent`, whatever its name shows. The total and
 * the rows both come from it.
 */
fun roster(paired: Collection<String>, agents: List<Agent>): List<Agent> {
    val hosts = paired.toSet()
    return agents.filter { it.host in hosts }.distinctBy { it.host to it.name }
}

/** `0 agents`, `1 agent`, `4 agents`. */
fun agentCount(total: Int): String = if (total == 1) "1 agent" else "$total agents"

/** The words of an agents search, folded as every search of the app is ([fold]). */
fun searchWords(query: String): List<String> = fold(query).split(' ').filter { it.isNotEmpty() }

/** Whether [agent]'s name, address or runtime has every one of [words]. */
fun agentFound(agent: Agent, words: List<String>): Boolean = words.all { it in fold("${agent.label} ${agent.address} ${agent.runtime}") }

/** Whether [sandbox]'s address or runtime has every one of [words]. */
fun sandboxFound(sandbox: Sandbox, words: List<String>): Boolean = words.all { it in fold("${sandbox.address} ${sandbox.runtime.orEmpty()}") }

/** A sandbox heading of the agents view and the agents under it. */
data class SandboxGroup(val sandbox: Sandbox, val agents: List<Agent>)

/**
 * A host of the agents view: [open] unless folded away, [foldable] unless a
 * search or Working only is on (they unfold every host they show, leaving
 * what was folded as it was); [create] when it may offer Create sandbox.
 */
data class HostGroup(val host: String, val open: Boolean, val foldable: Boolean, val create: Boolean, val sandboxes: List<SandboxGroup>)

/** Why the agents view shows nothing under its hosts. */
enum class AgentsEmpty {
    /** A search, Working only off, found no sandbox and no agent. */
    NoMatch,
    /** Working only, no search: no agent shows at work. */
    NoneWorking,
    /** Working only and a search: no agent at work matches it. */
    NoWorkingMatch,
}

/** What the agents view shows: [total] before any search or filter, then its hosts. */
data class AgentsView(val total: Int, val hosts: List<HostGroup>, val empty: AgentsEmpty?)

/**
 * The agents view of [paired] hosts, in their order: [roster] of [agents],
 * found by [query] and, with [workingOnly], at work by [working] (the rows'
 * own decision). With Working only, a host or sandbox with no agent left is
 * not shown, nor Create sandbox; a host [folded] away opens while a search
 * or Working only is on, and folds again once both are off.
 */
fun agentsView(
    paired: List<String>,
    agents: List<Agent>,
    query: String,
    workingOnly: Boolean,
    folded: (String) -> Boolean,
    sandboxesOf: (String) -> List<Sandbox>,
    canManage: (String) -> Boolean,
    working: (Agent) -> Boolean,
): AgentsView {
    val all = roster(paired, agents)
    val words = searchWords(query)
    val searching = words.isNotEmpty()
    val filtering = searching || workingOnly
    val shown = all.filter { agentFound(it, words) && (!workingOnly || working(it)) }
    var sandboxFoundAnywhere = false
    val hosts = paired.mapNotNull { host ->
        val its = shown.filter { it.host == host }
        val sandboxes = sandboxesOf(host).filter { sandbox ->
            val has = its.any { it.sandbox == sandbox.name }
            val found = searching && sandboxFound(sandbox, words)
            if (found) sandboxFoundAnywhere = true
            // Working only shows a sandbox for its agents at work alone.
            has || (!workingOnly && (!searching || found))
        }
        // A search or Working only shows only the hosts with something found.
        if (filtering && sandboxes.isEmpty()) return@mapNotNull null
        HostGroup(
            host = host,
            open = filtering || !folded(host),
            foldable = !filtering,
            create = !filtering && canManage(host),
            sandboxes = sandboxes.map { s -> SandboxGroup(s, its.filter { it.sandbox == s.name }) },
        )
    }
    val empty = when {
        workingOnly && shown.isEmpty() -> if (searching) AgentsEmpty.NoWorkingMatch else AgentsEmpty.NoneWorking
        !workingOnly && searching && shown.isEmpty() && !sandboxFoundAnywhere -> AgentsEmpty.NoMatch
        else -> null
    }
    return AgentsView(all.size, hosts, empty)
}
