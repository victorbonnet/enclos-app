package io.github.victorbonnet.enclos

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The agents view's total and Working only (docs/spec.md, Agents), through
 * the production projection [agentsView] and the shared decision
 * [conversationWorking] / [showsWorking] AppModel and the rows use.
 */
class AgentsRosterTest {
    private fun agent(host: String, name: String, label: String? = null, runtime: String = "hermes", capabilities: List<String> = emptyList()) =
        Agent(host, name, runtime, capabilities, displayName = label)

    /** AppModel's live work state, held as it holds it, read by the production predicate. */
    private class Work {
        val typing = mutableMapOf<String, Boolean>()
        val awaiting = mutableMapOf<String, Boolean>()
        val steps = mutableMapOf<String, List<Step>>()
        val conversations = mutableListOf<Conversation>()
        val activities = Activities()

        fun working(id: String) = conversationWorking(id, typing, awaiting, steps, conversations, activities)
        fun workingOn(agent: Agent) = conversations.filter { "${it.host}/${it.agent}" == agent.address }.filter { working(it.id) }
        fun shows(agent: Agent) = showsWorking(workingOn(agent), activities.of(agent.address))
        fun activity(host: String, agent: String, vararg doing: Pair<String, String>) = activities.received(
            host,
            JSONObject().put("type", "agent.activity").put("agent", agent).put("since", 1).put(
                "doing",
                org.json.JSONArray(doing.map { (kind, id) -> JSONObject().put("kind", kind).put(if (kind == "answering") "conversation" else kind, id) }),
            ),
        )
    }

    private val leadH1 = agent("H1", "lab/lead", "Lead")
    private val helper = agent("H1", "lab/helper", "Helper")
    private val workLead = agent("H1", "work/lead", "Lead")
    private val leadH2 = agent("H2", "lab/lead", "Lead")
    private val canonical = listOf(leadH1, helper, workLead, leadH2)

    /** The canonical fixture (PM matrix): two waits and a task on H1/lab/lead, a routine on H1/work/lead. */
    private fun canonicalWork() = Work().apply {
        conversations += Conversation("H1", "lab/lead", "c1")
        conversations += Conversation("H1", "lab/lead", "c2")
        conversations += Conversation("H1", "lab/helper", "c3")
        conversations += Conversation("H1", "old/gone", "c4")
        awaiting["c1"] = true
        awaiting["c2"] = true
        // A conversation of an agent no longer listed is working: it adds no agent.
        typing["c4"] = true
        activity("H1", "lab/lead", "task" to "t1")
        activity("H1", "work/lead", "routine" to "r1")
    }

    private fun view(
        agents: List<Agent> = canonical,
        paired: List<String> = listOf("H1", "H2"),
        query: String = "",
        workingOnly: Boolean = false,
        folded: Set<String> = emptySet(),
        sandboxes: Map<String, List<Sandbox>> = emptyMap(),
        manage: Set<String> = emptySet(),
        work: Work = canonicalWork(),
    ) = agentsView(
        paired, agents, query, workingOnly,
        folded = { it in folded },
        sandboxesOf = { host -> sandboxes[host] ?: agents.filter { it.host == host }.groupBy { it.sandbox }.map { (s, a) -> Sandbox(host, s, a.first().runtime) } },
        canManage = { it in manage },
        working = work::shows,
    )

    private fun AgentsView.rows() = hosts.flatMap { h -> h.sandboxes.flatMap { it.agents } }.map { it.address }

    @Test
    fun countsAreZeroSingularPlural() {
        assertEquals("0 agents", agentCount(0))
        assertEquals("1 agent", agentCount(1))
        assertEquals("4 agents", agentCount(4))
        assertEquals(0, view(agents = emptyList()).total)
        assertEquals(1, view(agents = listOf(helper)).total)
        assertEquals(0, view(paired = emptyList()).total)
    }

    @Test
    fun canonicalFixtureCountsFourAndShowsTwoWorking() {
        val all = view()
        assertEquals(4, all.total)
        assertEquals(listOf("H1/lab/lead", "H1/lab/helper", "H1/work/lead", "H2/lab/lead"), all.rows())
        val working = view(workingOnly = true)
        assertEquals(4, working.total)
        assertEquals(listOf("H1/lab/lead", "H1/work/lead"), working.rows())
        // H2 has no agent at work: hidden, with its empty sandboxes.
        assertEquals(listOf("H1"), working.hosts.map { it.host })
        assertNull(working.empty)
    }

    @Test
    fun theSameAgentTwiceCountsOnceAndShowsOnce() {
        val twice = canonical + agent("H1", "lab/lead", "Lead again")
        assertEquals(4, view(agents = twice).total)
        assertEquals(1, view(agents = twice).rows().count { it == "H1/lab/lead" })
        assertEquals(1, view(agents = twice, workingOnly = true).rows().count { it == "H1/lab/lead" })
    }

    @Test
    fun anUnpairedHostsAgentsAreNotCounted() {
        // A hidden agent not in the supplied roster, and one of a host no longer paired, add nothing.
        assertEquals(4, view(agents = canonical + agent("H3", "lab/z")).total)
    }

    @Test
    fun searchAndFoldsLeaveTheTotal() {
        val found = view(query = "lead", folded = setOf("H1"))
        assertEquals(4, found.total)
        assertEquals(listOf("H1/lab/lead", "H1/work/lead", "H2/lab/lead"), found.rows())
        val one = view(query = "H1/lab/lead")
        assertEquals(listOf("H1/lab/lead"), one.rows())
        assertEquals(4, view(folded = setOf("H1")).total)
    }

    @Test
    fun searchIntersectsWorkingOnly() {
        assertEquals(listOf("H1/lab/lead", "H1/work/lead"), view(query = "LEAD", workingOnly = true).rows())
        // Accents and case fold as in every search; every word must be found.
        assertEquals(listOf("H1/work/lead"), view(query = "léad WORK", workingOnly = true).rows())
        assertEquals(listOf("H1/lab/helper"), view(query = "helper").rows())
        val none = view(query = "helper", workingOnly = true)
        assertEquals(emptyList<String>(), none.rows())
        assertEquals(AgentsEmpty.NoWorkingMatch, none.empty)
        // Searching by runtime.
        assertEquals(2, view(query = "hermes", workingOnly = true).rows().size)
    }

    @Test
    fun emptyStatesAreDistinct() {
        val idle = Work()
        assertEquals(AgentsEmpty.NoneWorking, view(workingOnly = true, work = idle).empty)
        assertEquals(AgentsEmpty.NoWorkingMatch, view(workingOnly = true, query = "lead", work = idle).empty)
        assertEquals(AgentsEmpty.NoMatch, view(query = "nothing-like-it").empty)
        assertNull(view(work = idle).empty)
        // Working only shows no host at all, yet its total stays.
        assertEquals(4, view(workingOnly = true, work = idle).total)
        assertTrue(view(workingOnly = true, work = idle).hosts.isEmpty())
    }

    @Test
    fun showAllTurnsOffOnlyTheFilterAndKeepsTheSearch() {
        // Show all agents leaves the query: the unfiltered search may still be empty.
        val emptySearch = view(query = "planner", workingOnly = false, work = Work())
        assertEquals(AgentsEmpty.NoMatch, emptySearch.empty)
    }

    @Test
    fun aSandboxFoundAloneShowsOnlyWithWorkingOnlyOff() {
        val sandboxes = mapOf("H1" to listOf(Sandbox("H1", "lab", "hermes"), Sandbox("H1", "work", "hermes"), Sandbox("H1", "spare", null)), "H2" to listOf(Sandbox("H2", "lab", "hermes")))
        val off = view(query = "spare", sandboxes = sandboxes)
        assertEquals(listOf("spare"), off.hosts.single().sandboxes.map { it.sandbox.name })
        assertNull(off.empty)
        val on = view(query = "spare", workingOnly = true, sandboxes = sandboxes)
        assertTrue(on.hosts.isEmpty())
        assertEquals(AgentsEmpty.NoWorkingMatch, on.empty)
        // Unfiltered and unsearched, the empty sandbox and Create sandbox show as before.
        val all = view(sandboxes = sandboxes, manage = setOf("H1"))
        assertTrue(all.hosts.first().create)
        assertTrue("spare" in all.hosts.first().sandboxes.map { it.sandbox.name })
        // Working only: no empty sandbox, no Create sandbox.
        val working = view(workingOnly = true, sandboxes = sandboxes, manage = setOf("H1"))
        assertFalse(working.hosts.single().create)
        assertEquals(listOf("lab", "work"), working.hosts.single().sandboxes.map { it.sandbox.name })
    }

    @Test
    fun foldsOpenWhileFilteringAndAreNotChanged() {
        val folded = setOf("H1")
        val plain = view(folded = folded)
        assertFalse(plain.hosts.first { it.host == "H1" }.open)
        assertTrue(plain.hosts.first { it.host == "H1" }.foldable)
        for (v in listOf(view(folded = folded, workingOnly = true), view(folded = folded, query = "lead"), view(folded = folded, query = "lead", workingOnly = true))) {
            val h1 = v.hosts.first { it.host == "H1" }
            assertTrue(h1.open)
            assertFalse(h1.foldable)
        }
        // Both off again: the saved fold holds (the set was never changed).
        assertFalse(view(folded = folded).hosts.first { it.host == "H1" }.open)
    }

    @Test
    fun eachSignalAloneMakesTheAgentWork() {
        val c = Conversation("H2", "lab/lead", "x")
        fun with(set: Work.() -> Unit) = Work().apply { conversations += c; set() }.shows(leadH2)
        assertFalse(with {})
        assertTrue(with { awaiting["x"] = true })
        assertTrue(with { typing["x"] = true })
        assertTrue(with { steps["x"] = listOf(Step("1", "bash", null)) })
        assertFalse(with { steps["x"] = listOf(Step("1", "bash", null, done = true)) })
        assertTrue(with { activity("H2", "lab/lead", "answering" to "x") })
        assertTrue(with { activity("H2", "lab/lead", "task" to "t") })
        assertTrue(with { activity("H2", "lab/lead", "routine" to "r") })
        // Unknown-only and empty activity are no sign of work, nor proof of idle.
        assertFalse(with { activity("H2", "lab/lead", "planning" to "p") })
        assertFalse(with { activity("H2", "lab/lead") })
    }

    @Test
    fun anotherSignalKeepsTheAgentWhenOneClears() {
        val w = canonicalWork()
        assertTrue(w.shows(leadH1))
        w.awaiting.remove("c1")
        w.awaiting.remove("c2")
        // Its task activity remains.
        assertTrue(w.shows(leadH1))
        w.activity("H1", "lab/lead")
        assertFalse(w.shows(leadH1))
        assertEquals(listOf("H1/work/lead"), view(workingOnly = true, work = w).rows())
    }

    @Test
    fun capabilitiesTasksAndMessagesAloneAreNotWork() {
        val tasking = agent("H2", "lab/lead", "Lead", capabilities = listOf("tasks", "typing", "activity", "routines"))
        val w = Work().apply { conversations += Conversation("H2", "lab/lead", "x") }
        assertFalse(w.shows(tasking))
        assertEquals(emptyList<String>(), view(agents = listOf(tasking), workingOnly = true, work = w).rows())
    }

    @Test
    fun anArchivedConversationsWorkCounts() {
        val w = Work().apply {
            conversations += Conversation("H2", "lab/lead", "x", archived = true)
            awaiting["x"] = true
        }
        assertTrue(w.shows(leadH2))
    }

    @Test
    fun aHostEndingRemovesOnlyItsActivity() {
        val w = canonicalWork()
        w.activity("H2", "lab/lead", "routine" to "r")
        w.activities.ended("H1")
        // H1's waits are conversation work, still there until AppModel.ended clears them.
        assertEquals(listOf("H1/lab/lead", "H2/lab/lead"), view(workingOnly = true, work = w).rows())
        assertEquals(4, view(workingOnly = true, work = w).total)
    }

    @Test
    fun aNewListClearsActivityUntilFreshReports() {
        val w = canonicalWork()
        w.activities.listed("H1")
        assertEquals(listOf("H1/lab/lead"), view(workingOnly = true, work = w).rows())
        w.activity("H1", "work/lead", "routine" to "r1")
        assertEquals(listOf("H1/lab/lead", "H1/work/lead"), view(workingOnly = true, work = w).rows())
    }

    @Test
    fun theSameNameOnTwoHostsIsTwoAgentsWithTheirOwnWork() {
        // A Home id clash: the second host's conversation is left out, its activity still counts.
        val w = Work().apply {
            conversations += Conversation("H1", "lab/lead", "home")
            awaiting["home"] = true
        }
        assertEquals(4, view(work = w).total)
        assertEquals(listOf("H1/lab/lead"), view(workingOnly = true, work = w).rows())
        w.activity("H2", "lab/lead", "routine" to "r")
        assertEquals(listOf("H1/lab/lead", "H2/lab/lead"), view(workingOnly = true, work = w).rows())
    }
}
