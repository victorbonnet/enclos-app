package io.github.victorbonnet.enclos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure JVM tests for raising agents and conversations with queued or running
 * tasks in the Inbox ([raisedBy] and [byAgent]).
 */
class InboxRaiseTest {
    private val host = "enclos-org"
    private val now = 1_764_140_000L
    private val pmAddress = "$host/org/pm"
    private val designerAddress = "$host/org/designer"
    private val engineerAddress = "$host/org/engineer"

    private val designerHome = Conversation(host, "org/designer", "home.org.designer", title = "Home", home = true)
    private val pmHome = Conversation(host, "org/pm", "home.org.pm", title = "Home", home = true)
    private val engineerHome = Conversation(host, "org/engineer", "home.org.engineer", title = "Home", home = true)

    private val reply = Conversation(host, "org/pm", "c-reply", title = "Reply to one message")
    private val extra1 = Conversation(host, "org/pm", "c-extra-1", title = "Extra 1")
    private val extra2 = Conversation(host, "org/pm", "c-extra-2", title = "Extra 2")
    private val extra3 = Conversation(host, "org/pm", "c-extra-3", title = "Extra 3")
    private val extra4 = Conversation(host, "org/pm", "c-extra-4", title = "Extra 4")
    private val extra5 = Conversation(host, "org/pm", "c-extra-5", title = "Extra 5")
    private val weather = Conversation(host, "org/pm", "c-weather", title = "Weather in Lyon")
    private val designerOther = Conversation(host, "org/designer", "c-design", title = "Mockups")

    /**
     * Working fixture:
     * - designer: Home 30 min
     * - pm: Reply to one message 45 min (unread 2), extras 46..50 min, Home 2 h, Weather in Lyon 26 h (unread 0)
     * - engineer: Home 3 h
     * Today's Agents order: designer, pm, engineer.
     */
    private val times = mapOf(
        designerHome.id to now - 30 * 60,
        reply.id to now - 45 * 60,
        extra1.id to now - 46 * 60,
        extra2.id to now - 47 * 60,
        extra3.id to now - 48 * 60,
        extra4.id to now - 49 * 60,
        extra5.id to now - 50 * 60,
        pmHome.id to now - 2 * 3600,
        engineerHome.id to now - 3 * 3600,
        weather.id to now - 26 * 3600,
        designerOther.id to now - 4 * 3600,
    )

    private val baseConversations = listOf(
        designerHome,
        reply,
        extra1,
        extra2,
        extra3,
        extra4,
        pmHome,
        engineerHome,
        weather,
    )

    private val baseUnread = mapOf(reply.id to 2)

    private fun sortedByTime(list: List<Conversation>) =
        list.sortedByDescending { times[it.id] ?: 0L }

    private fun task(
        id: String,
        assignee: String = "org/pm",
        conversation: String? = weather.id,
        state: TaskState = TaskState.Running,
        since: Long = now - 12 * 60,
    ) = AgentTask(
        host = host,
        id = id,
        title = "Task $id",
        assignee = assignee,
        askedBy = null,
        conversation = conversation,
        state = state,
        since = since,
        summary = null,
    )

    private fun entries(
        conversations: List<Conversation> = baseConversations,
        found: List<Found>? = null,
        expanded: Set<String> = emptySet(),
        unread: Map<String, Int> = baseUnread,
        narrowed: Boolean = false,
        raised: Raised = Raised.None,
    ): List<AgentEntry> {
        val sorted = sortedByTime(conversations)
        val matching = found ?: sorted.map { Found(it, null) }
        return byAgent(
            sorted = sorted,
            found = matching,
            expanded = expanded,
            unread = { unread[it] ?: 0 },
            narrowed = narrowed,
            raised = raised,
            lastAt = { times[it.id] ?: 0L },
        )
    }

    @Test
    fun c1_running_task_in_old_conversation_raises_agent_and_thread() {
        // Without a raise, today's order is designer, pm, engineer.
        val before = entries()
        assertEquals(listOf(designerAddress, pmAddress, engineerAddress), before.map { it.address })
        assertEquals(
            listOf(reply.id, extra1.id, extra2.id, extra3.id, extra4.id),
            before[1].under.map { it.conversation.id },
        )

        val raised = raisedBy(listOf(designerAddress, pmAddress, engineerAddress)) { addr ->
            if (addr == pmAddress) listOf(task("t1", conversation = weather.id)) else emptyList()
        }
        val after = entries(raised = raised)
        assertEquals(listOf(pmAddress, designerAddress, engineerAddress), after.map { it.address })
        assertEquals(
            listOf(weather.id, reply.id, extra1.id, extra2.id, extra3.id, extra4.id),
            after[0].under.map { it.conversation.id },
        )
    }

    @Test
    fun c3_folded_shows_five_newest_plus_raised_and_hidden_count_drops_by_one() {
        // 7 non-Home conversations under pm: reply, extra1..5, weather (26 h old).
        val seven = baseConversations + extra5
        val before = entries(conversations = seven)
        val pmBefore = before.single { it.address == pmAddress }
        assertEquals(7, pmBefore.others)
        assertEquals(5, pmBefore.under.size)
        assertEquals(2, pmBefore.others - pmBefore.under.size)

        // Raising weather (outside the 5 newest) shows 6 and drops hidden count from 2 to 1.
        val raisedOld = raisedBy(listOf(pmAddress)) { listOf(task("t1", conversation = weather.id)) }
        val pmRaised = entries(conversations = seven, raised = raisedOld).single { it.address == pmAddress }
        assertEquals(7, pmRaised.others)
        assertEquals(6, pmRaised.under.size)
        assertEquals(1, pmRaised.others - pmRaised.under.size)
        assertEquals(
            listOf(weather.id, reply.id, extra1.id, extra2.id, extra3.id, extra4.id),
            pmRaised.under.map { it.conversation.id },
        )

        // Raising a conversation already among the 5 newest moves it to the front without changing hidden count.
        val raisedRecent = raisedBy(listOf(pmAddress)) { listOf(task("t2", conversation = extra3.id)) }
        val pmRecent = entries(conversations = seven, raised = raisedRecent).single { it.address == pmAddress }
        assertEquals(7, pmRecent.others)
        assertEquals(5, pmRecent.under.size)
        assertEquals(2, pmRecent.others - pmRecent.under.size)
        assertEquals(
            listOf(extra3.id, reply.id, extra1.id, extra2.id, extra4.id),
            pmRecent.under.map { it.conversation.id },
        )

        // Expanded shows all 7, raised first.
        val pmExpanded = entries(conversations = seven, expanded = setOf(pmAddress), raised = raisedOld)
            .single { it.address == pmAddress }
        assertEquals(
            listOf(weather.id, reply.id, extra1.id, extra2.id, extra3.id, extra4.id, extra5.id),
            pmExpanded.under.map { it.conversation.id },
        )
    }

    @Test
    fun c4_unplaced_task_raises_agent_only_and_no_kept_task_moves_nothing() {
        // No kept task: nothing moves.
        val none = raisedBy(listOf(designerAddress, pmAddress, engineerAddress)) { emptyList() }
        assertEquals(Raised.None, none)
        assertEquals(listOf(designerAddress, pmAddress, engineerAddress), entries(raised = none).map { it.address })

        // Conversation null, Home, unknown id, group id: pm rises, under order unchanged.
        for (conv in listOf(null, pmHome.id, "missing-conv", "group-standup")) {
            val raised = raisedBy(listOf(pmAddress)) { listOf(task("t1", conversation = conv)) }
            val result = entries(raised = raised)
            assertEquals("conv=$conv", listOf(pmAddress, designerAddress, engineerAddress), result.map { it.address })
            assertEquals(
                "conv=$conv",
                listOf(reply.id, extra1.id, extra2.id, extra3.id, extra4.id),
                result[0].under.map { it.conversation.id },
            )
        }

        // Task asked in another agent's conversation raises only the agent doing it,
        // never raising that conversation under the other agent (even if the other agent is also raised).
        val conversations = baseConversations + designerOther
        val raised = raisedBy(listOf(pmAddress, designerAddress)) { addr ->
            when (addr) {
                pmAddress -> listOf(task("t-pm", assignee = "org/pm", conversation = designerOther.id, since = now - 60))
                designerAddress -> listOf(task("t-des", assignee = "org/designer", conversation = null, since = now - 120))
                else -> emptyList()
            }
        }
        val result = entries(conversations = conversations, raised = raised)
        assertEquals(listOf(pmAddress, designerAddress, engineerAddress), result.map { it.address })
        assertEquals(listOf(reply.id, extra1.id, extra2.id, extra3.id, extra4.id), result[0].under.map { it.conversation.id })
        assertEquals(listOf(designerOther.id), result[1].under.map { it.conversation.id })
    }

    @Test
    fun c5_task_ended_restores_original_order_and_fold() {
        val seven = baseConversations + extra5
        for (ended in listOf(TaskState.Done, TaskState.Failed, TaskState.Cancelled)) {
            val raised = raisedBy(listOf(pmAddress)) { listOf(task("t1", conversation = weather.id, state = ended)) }
            assertEquals(Raised.None, raised)
            val result = entries(conversations = seven, raised = raised)
            assertEquals(listOf(designerAddress, pmAddress, engineerAddress), result.map { it.address })
            val pm = result[1]
            assertEquals(5, pm.under.size)
            assertEquals(2, pm.others - pm.under.size)
        }
    }

    @Test
    fun c6_two_agents_running_orders_by_newest_start_then_last_message_then_address() {
        val raised = raisedBy(listOf(pmAddress, engineerAddress)) { addr ->
            when (addr) {
                pmAddress -> listOf(task("t-pm", assignee = "org/pm", conversation = weather.id, since = now - 12 * 60))
                engineerAddress -> listOf(task("t-eng", assignee = "org/engineer", conversation = null, since = now - 2 * 60))
                else -> emptyList()
            }
        }
        val result = entries(raised = raised)
        assertEquals(listOf(engineerAddress, pmAddress, designerAddress), result.map { it.address })
        assertEquals(weather.id, result[1].under.first().conversation.id)

        // Equal task starts -> newest last message (pm 45 min vs engineer 3 h) -> pm first.
        val tiedStart = raisedBy(listOf(pmAddress, engineerAddress)) { addr ->
            when (addr) {
                pmAddress -> listOf(task("t-pm", assignee = "org/pm", conversation = weather.id, since = now - 5 * 60))
                engineerAddress -> listOf(task("t-eng", assignee = "org/engineer", conversation = null, since = now - 5 * 60))
                else -> emptyList()
            }
        }
        assertEquals(listOf(pmAddress, engineerAddress, designerAddress), entries(raised = tiedStart).map { it.address })

        // Equal task starts and equal lastAt -> address ascending (org/engineer < org/pm).
        val equalLastAt = byAgent(
            sorted = sortedByTime(baseConversations),
            found = sortedByTime(baseConversations).map { Found(it, null) },
            expanded = emptySet(),
            unread = { baseUnread[it] ?: 0 },
            narrowed = false,
            raised = tiedStart,
            lastAt = { 100L },
        )
        assertEquals(listOf(engineerAddress, pmAddress, designerAddress), equalLastAt.map { it.address })
    }

    @Test
    fun c7_two_tasks_in_one_agent_orders_by_newest_start_then_last_message() {
        val raised = raisedBy(listOf(pmAddress)) {
            listOf(
                task("t-a", conversation = weather.id, since = now - 12 * 60),
                task("t-b", conversation = reply.id, since = now - 60),
            )
        }
        val pm = entries(raised = raised).first()
        assertEquals(pmAddress, pm.address)
        assertEquals(
            listOf(reply.id, weather.id, extra1.id, extra2.id, extra3.id, extra4.id),
            pm.under.map { it.conversation.id },
        )

        // Equal task starts -> newest last message via lastAt (reply 45 min > weather 26 h).
        val tied = raisedBy(listOf(pmAddress)) {
            listOf(
                task("t-a", conversation = weather.id, since = now - 5 * 60),
                task("t-b", conversation = reply.id, since = now - 5 * 60),
            )
        }
        assertEquals(
            listOf(reply.id, weather.id, extra1.id, extra2.id, extra3.id, extra4.id),
            entries(raised = tied).first().under.map { it.conversation.id },
        )
    }

    @Test
    fun c8_c9_c10_live_turn_blocked_and_search_keep_todays_order() {
        // C8: empty Raised (live turn / routine / group round) changes nothing.
        assertEquals(listOf(designerAddress, pmAddress, engineerAddress), entries(raised = Raised.None).map { it.address })

        // C9: blocked task does not raise.
        val blocked = raisedBy(listOf(pmAddress)) { listOf(task("t-blocked", state = TaskState.Blocked)) }
        assertEquals(Raised.None, blocked)
        assertEquals(listOf(designerAddress, pmAddress, engineerAddress), entries(raised = blocked).map { it.address })

        // C10: search passes Raised.None -> designer (30 min) before pm (26 h).
        val searchFound = listOf(Found(designerHome, null), Found(weather, null))
        val searched = entries(found = searchFound, narrowed = true, raised = Raised.None)
        assertEquals(listOf(designerAddress, pmAddress), searched.map { it.address })
    }

    @Test
    fun c11_unread_only_keeps_read_conversation_out_while_agent_still_rises() {
        val raised = raisedBy(listOf(pmAddress)) { listOf(task("t1", conversation = weather.id)) }
        val unreadBoth = mapOf(designerHome.id to 1, reply.id to 2, weather.id to 3)
        val foundUnreadBoth = sortedByTime(listOf(designerHome, reply, weather)).map { Found(it, null) }
        val withWeatherUnread = entries(found = foundUnreadBoth, unread = unreadBoth, narrowed = true, raised = raised)
        assertEquals(listOf(pmAddress, designerAddress), withWeatherUnread.map { it.address })
        assertEquals(listOf(weather.id, reply.id), withWeatherUnread[0].under.map { it.conversation.id })

        // Weather made read (unread 0): excluded from found, stays out of under, while pm still rises.
        val unreadWithoutWeather = mapOf(designerHome.id to 1, reply.id to 2)
        val foundWithoutWeather = sortedByTime(listOf(designerHome, reply)).map { Found(it, null) }
        val withWeatherRead = entries(found = foundWithoutWeather, unread = unreadWithoutWeather, narrowed = true, raised = raised)
        assertEquals(listOf(pmAddress, designerAddress), withWeatherRead.map { it.address })
        assertEquals(listOf(reply.id), withWeatherRead[0].under.map { it.conversation.id })
    }

    @Test
    fun c12_archived_view_raises_archived_conversation_without_active_home() {
        val archivedWeather = weather.copy(archived = true)
        val archivedDesigner = designerOther.copy(archived = true)
        val archived = sortedByTime(listOf(archivedDesigner, archivedWeather))
        val raised = raisedBy(listOf(pmAddress, designerAddress)) { addr ->
            if (addr == pmAddress) listOf(task("t1", conversation = archivedWeather.id)) else emptyList()
        }
        val result = byAgent(
            sorted = archived,
            found = archived.map { Found(it, null) },
            expanded = emptySet(),
            unread = { 0 },
            narrowed = true,
            raised = raised,
            lastAt = { times[it.id] ?: 0L },
        )
        assertEquals(listOf(pmAddress, designerAddress), result.map { it.address })
        assertNull(result[0].home)
        assertEquals(listOf(archivedWeather.id), result[0].under.map { it.conversation.id })

        // Active view excludes archivedWeather.
        val activeConversations = baseConversations.filter { it.id != weather.id }
        val activeResult = entries(conversations = activeConversations, raised = raised)
        assertEquals(listOf(reply.id, extra1.id, extra2.id, extra3.id, extra4.id), activeResult[0].under.map { it.conversation.id })
    }

    @Test
    fun c15_raise_never_adds_an_agent_or_conversation_outside_the_view() {
        val archivedOnlyAgent = "$host/org/archivist"
        val raised = raisedBy(listOf(archivedOnlyAgent, pmAddress)) { addr ->
            when (addr) {
                archivedOnlyAgent -> listOf(task("t-arch", assignee = "org/archivist", conversation = "c-arch", since = now))
                else -> emptyList()
            }
        }
        val result = entries(raised = raised)
        assertEquals(listOf(designerAddress, pmAddress, engineerAddress), result.map { it.address })
    }

    @Test
    fun queued_state_also_raises() {
        val raised = raisedBy(listOf(pmAddress)) { listOf(task("t-q", state = TaskState.Queued)) }
        val result = entries(raised = raised)
        assertEquals(listOf(pmAddress, designerAddress, engineerAddress), result.map { it.address })
        assertEquals(weather.id, result[0].under.first().conversation.id)
    }
}
