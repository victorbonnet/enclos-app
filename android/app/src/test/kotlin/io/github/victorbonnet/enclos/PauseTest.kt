package io.github.victorbonnet.enclos

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PauseTest {
    private val bothCaps = setOf(PAUSE_CAPABILITY, PAUSE_SUBSET_CAPABILITY)

    private fun pauseStateJson(
        paused: Boolean = false,
        partly: Boolean = false,
        revision: Long = 8L,
        sandboxes: List<JSONObject>,
    ): JSONObject = JSONObject()
        .put("v", 1)
        .put("id", "ps-$revision")
        .put("type", "pause.state")
        .put("paused", paused)
        .put("partly", partly)
        .put("since", "2026-11-25T17:00:00Z")
        .put("by", "owner@example.org")
        .put("revision", revision)
        .put("sandboxes", JSONArray(sandboxes))

    private fun sandboxRowJson(
        sandbox: String,
        paused: Boolean,
        state: String = "running",
        why: String? = null,
    ): JSONObject = JSONObject()
        .put("sandbox", sandbox)
        .put("paused", paused)
        .put("state", state)
        .apply { if (why != null) put("why", why) }

    @Test
    fun device_auth_announces_pause_and_pause_subset() {
        val caps = authFields("d-1", "c2ln").toMap()["capabilities"] as List<*>
        assertTrue(PAUSE_CAPABILITY in caps)
        assertTrue(PAUSE_SUBSET_CAPABILITY in caps)
    }

    @Test
    fun capability_gating_requires_both_pause_and_pause_subset() {
        val sent = mutableListOf<JSONObject>()
        val client = PauseClient(connected = { true }, send = { _, m -> sent += m; true })

        client.authenticated("mac", emptySet())
        assertFalse(client.canControl("mac"))
        assertFalse(client.pause("mac", "coder-lab"))

        client.authenticated("mac", setOf(PAUSE_CAPABILITY))
        assertFalse(client.canControl("mac"))
        assertFalse(client.pause("mac", "coder-lab"))

        client.authenticated("mac", setOf(PAUSE_SUBSET_CAPABILITY))
        assertFalse(client.canControl("mac"))
        assertFalse(client.pause("mac", "coder-lab"))
        assertTrue("nothing sent without both capabilities", sent.isEmpty())

        client.authenticated("mac", bothCaps)
        assertTrue(client.canControl("mac"))
        assertTrue(client.pause("mac", "coder-lab"))
        assertEquals(1, sent.size)
    }

    @Test
    fun pause_and_resume_request_json_carry_one_sandbox_and_optional_revision() {
        val noRev = pauseRequest("coder-lab", null)
        assertEquals(1, noRev.getInt("v"))
        assertEquals("pause", noRev.getString("type"))
        assertTrue(noRev.getString("id").isNotBlank())
        assertFalse(noRev.has("revision"))
        assertEquals(listOf("coder-lab"), listOf(noRev.getJSONArray("sandboxes").getString(0)))
        assertEquals(1, noRev.getJSONArray("sandboxes").length())

        val withRev = resumeRequest("coder-lab", 42L)
        assertEquals(1, withRev.getInt("v"))
        assertEquals("resume", withRev.getString("type"))
        assertEquals(42L, withRev.getLong("revision"))
        assertEquals(1, withRev.getJSONArray("sandboxes").length())
        assertEquals("coder-lab", withRev.getJSONArray("sandboxes").getString(0))
    }

    @Test
    fun protocol_fixtures_parse_cleanly() {
        val fromReq = JSONObject(javaClass.getResource("/protocol/from_device_pause.json")!!.readText())
        assertEquals("pause", fromReq.getString("type"))
        assertEquals(7L, fromReq.getLong("revision"))
        assertEquals(2, fromReq.getJSONArray("sandboxes").length())

        val stateJson = JSONObject(javaClass.getResource("/protocol/to_device_pause_state.json")!!.readText())
        val state = parsePauseState(stateJson)
        assertFalse(state.unreadable)
        assertFalse(state.paused)
        assertTrue(state.partly)
        assertEquals(8L, state.revision)
        assertEquals(true, state.row("lab")?.paused)
        assertEquals("running", state.row("lab")?.state)
        assertEquals(false, state.row("home")?.paused)
        assertEquals("not_covered", state.row("home")?.why)

        val resultJson = JSONObject(javaClass.getResource("/protocol/to_device_pause_result.json")!!.readText())
        val result = parsePauseResult(resultJson)
        assertFalse(result.unreadable)
        assertEquals("p-1", result.of)
        assertFalse(result.paused)
        assertTrue(result.partly)
        assertEquals(8L, result.revision)
        assertEquals(
            listOf(PauseResultRow("lab", "paused"), PauseResultRow("home", "nothing_to_pause")),
            result.sandboxes,
        )
    }

    @Test
    fun unknown_why_or_outcome_marks_unreadable_never_success() {
        val state = parsePauseState(
            pauseStateJson(
                sandboxes = listOf(
                    sandboxRowJson("coder-lab", paused = false, state = "running", why = "invented_reason"),
                    sandboxRowJson("other", paused = true, state = "running"),
                ),
            ),
        )
        assertFalse(state.unreadable)
        val badRow = state.row("coder-lab")!!
        assertTrue(badRow.unreadable)
        assertEquals("unreadable", badRow.state)
        assertFalse(badRow.paused)
        assertFalse(state.row("other")!!.unreadable)

        val result = parsePauseResult(
            JSONObject()
                .put("v", 1)
                .put("id", "pr-2")
                .put("type", "pause.result")
                .put("of", "p-1")
                .put("paused", false)
                .put("partly", true)
                .put("revision", 9)
                .put("sandboxes", JSONArray().put(JSONObject().put("sandbox", "coder-lab").put("outcome", "mystery"))),
        )
        assertTrue(result.unreadable)
    }

    @Test
    fun every_row_of_the_approved_state_table_matches_exact_words() {
        val h = "mac"
        val s = "coder-lab"
        val runningState = parsePauseState(pauseStateJson(paused = false, partly = false, sandboxes = listOf(sandboxRowJson(s, paused = false, state = "running"))))
        val partlyPaused = parsePauseState(
            pauseStateJson(
                paused = false,
                partly = true,
                sandboxes = listOf(sandboxRowJson(s, paused = true), sandboxRowJson("other", paused = false)),
            ),
        )
        val wholeHostPaused = parsePauseState(
            pauseStateJson(
                paused = true,
                partly = false,
                sandboxes = listOf(sandboxRowJson(s, paused = true), sandboxRowJson("other", paused = true)),
            ),
        )
        val oneOtherPaused = parsePauseState(
            pauseStateJson(
                paused = false,
                partly = true,
                sandboxes = listOf(sandboxRowJson(s, paused = false), sandboxRowJson("other", paused = true)),
            ),
        )
        val twoOthersPaused = parsePauseState(
            pauseStateJson(
                paused = false,
                partly = true,
                sandboxes = listOf(
                    sandboxRowJson(s, paused = false),
                    sandboxRowJson("a", paused = true),
                    sandboxRowJson("b", paused = true),
                ),
            ),
        )
        val stoppedState = parsePauseState(pauseStateJson(sandboxes = listOf(sandboxRowJson(s, paused = false, state = "stopped"))))
        val unreadableRowState = parsePauseState(pauseStateJson(sandboxes = listOf(sandboxRowJson(s, paused = false, state = "running", why = "unknown_word"))))

        // 1. running, nothing of it paused
        val r1 = sandboxPauseBlock(h, s, true, true, false, runningState, "running", true, null, null)!!
        assertEquals("PAUSE CODER-LAB", r1.heading)
        assertEquals("Pause coder-lab", r1.headingDescription)
        assertEquals("Running · nothing of coder-lab is paused.", r1.stateLine)
        assertEquals(PauseBlockButton("Pause every agent in coder-lab", true, PauseAction.Pause), r1.button)
        assertNull(r1.notice)

        // 2. paused, rest of host runs (host partly)
        val r2 = sandboxPauseBlock(h, s, true, true, false, partlyPaused, "running", true, null, null)!!
        assertEquals("coder-lab is paused. Its runtime is stopped.", r2.stateLine)
        assertEquals(PauseBlockButton("Resume every agent in coder-lab", true, PauseAction.Resume), r2.button)
        assertEquals("The rest of mac keeps running either way.", r2.notice)

        // 3. paused, whole host paused (host paused)
        val r3 = sandboxPauseBlock(h, s, true, true, false, wholeHostPaused, "running", true, null, null)!!
        assertEquals("coder-lab is paused. Its runtime is stopped.", r3.stateLine)
        assertEquals(PauseBlockButton("Resume every agent in coder-lab", true, PauseAction.Resume), r3.button)
        assertEquals("The rest of mac stays paused; the host's page resumes all of it.", r3.notice)

        // 4. this one resumed, others still held (singular and plural)
        val r4a = sandboxPauseBlock(h, s, true, true, false, oneOtherPaused, "running", true, null, PauseNotice.ResumedByThisDevice)!!
        assertEquals("Running · nothing of coder-lab is paused.", r4a.stateLine)
        assertEquals(PauseBlockButton("Pause every agent in coder-lab", true, PauseAction.Pause), r4a.button)
        assertEquals("Resumed · coder-lab runs again, 1 sandbox stays paused.", r4a.notice)

        val r4b = sandboxPauseBlock(h, s, true, true, false, twoOthersPaused, "running", true, null, PauseNotice.ResumedByThisDevice)!!
        assertEquals("Resumed · coder-lab runs again, 2 sandboxes stay paused.", r4b.notice)

        // 5. pause request in flight
        val r5 = sandboxPauseBlock(h, s, true, true, false, runningState, "running", true, PauseInFlight("p-1", h, s, PauseAction.Pause), null)!!
        assertEquals("Running · nothing of coder-lab is paused.", r5.stateLine)
        assertEquals(PauseBlockButton("Pausing…", false, PauseAction.Pause), r5.button)
        assertNull(r5.notice)

        // 6. resume request in flight
        val r6 = sandboxPauseBlock(h, s, true, true, false, partlyPaused, "running", true, PauseInFlight("r-1", h, s, PauseAction.Resume), null)!!
        assertEquals("coder-lab is paused. Its runtime is stopped.", r6.stateLine)
        assertEquals(PauseBlockButton("Resuming…", false, PauseAction.Resume), r6.button)
        assertNull(r6.notice)

        // 7. pause did not stop it (would_not_stop / operation_failed)
        val r7 = sandboxPauseBlock(h, s, true, true, false, runningState, "running", true, null, PauseNotice.WouldNotStop)!!
        assertEquals("Still running · coder-lab could not be paused.", r7.stateLine)
        assertEquals(PauseBlockButton("Pause every agent in coder-lab", true, PauseAction.Pause), r7.button)
        assertEquals("mac could not stop coder-lab. Pausing again tries it. The rest of mac is unaffected.", r7.notice)

        // 8. resume did not start (would_not_start)
        val r8 = sandboxPauseBlock(h, s, true, true, false, partlyPaused, "running", true, null, PauseNotice.WouldNotStart)!!
        assertEquals("Still stopped · coder-lab did not start.", r8.stateLine)
        assertEquals(PauseBlockButton("Resume every agent in coder-lab", true, PauseAction.Resume), r8.button)
        assertEquals("Resuming again tries it. The rest of mac stays paused.", r8.notice)

        // 9. sandbox stopped/failed/no runtime
        val r9 = sandboxPauseBlock(h, s, true, true, false, stoppedState, "stopped", true, null, null)!!
        assertEquals("coder-lab is stopped. There is nothing running to pause.", r9.stateLine)
        assertNull(r9.button)
        assertEquals("The rest of mac is unaffected.", r9.notice)

        // 10. not connected
        val r10 = sandboxPauseBlock(h, s, false, false, false, null, "running", true, null, null)!!
        assertEquals("Connect to mac to see whether coder-lab is paused.", r10.stateLine)
        assertNull(r10.button)
        assertNull(r10.notice)

        // 11. host cannot read its own state / internal / unreadable row
        val r11 = sandboxPauseBlock(h, s, true, true, false, unreadableRowState, "running", true, null, null)!!
        assertEquals("mac could not tell whether coder-lab is paused. Nothing was changed.", r11.stateLine)
        assertNull(r11.button)
        assertNull(r11.notice)

        // 12. bridge without both capabilities
        val r12Paused = sandboxPauseBlock(h, s, true, false, false, partlyPaused, "paused", true, null, null)!!
        assertEquals("coder-lab is paused. Its runtime is stopped.", r12Paused.stateLine)
        assertNull(r12Paused.button)
        assertNull(r12Paused.notice)
        val r12NotPaused = sandboxPauseBlock(h, s, true, false, false, null, "running", true, null, null)
        assertNull(r12NotPaused)

        // 13. shared person / not_allowed
        val r13 = sandboxPauseBlock(h, s, true, true, true, runningState, "running", true, null, null)!!
        assertEquals("Running · nothing of coder-lab is paused.", r13.stateLine)
        assertNull(r13.button)
        assertNull(r13.notice)
    }

    @Test
    fun conflict_not_found_not_allowed_disconnect_and_stale_result_handling() {
        var isConnected = true
        val sent = mutableListOf<JSONObject>()
        val refreshed = mutableListOf<String>()
        val client = PauseClient(
            connected = { isConnected },
            send = { _, m -> sent += m; true },
            refreshSandboxes = { refreshed += it },
        )
        client.authenticated("mac", bothCaps)
        client.received("mac", pauseStateJson(revision = 5L, sandboxes = listOf(sandboxRowJson("coder-lab", paused = false))))

        // Stale result with another `of` is ignored while request is in flight.
        assertTrue(client.pause("mac", "coder-lab"))
        val firstId = sent.last().getString("id")
        assertEquals(5L, sent.last().getLong("revision"))
        // One request at a time per host.
        assertFalse(client.pause("mac", "other"))
        client.received(
            "mac",
            JSONObject()
                .put("v", 1).put("id", "pr-old").put("type", "pause.result")
                .put("of", "other-id").put("paused", false).put("partly", true).put("revision", 6)
                .put("sandboxes", JSONArray().put(JSONObject().put("sandbox", "coder-lab").put("outcome", "would_not_stop"))),
        )
        assertNotNull("stale result does not clear in-flight", client.inFlight["mac"])
        assertNull(client.notices["mac/coder-lab"])

        // Conflict error shows refreshing notice, cleared by fresh pause.state.
        client.received("mac", JSONObject().put("v", 1).put("id", "e-1").put("type", "error").put("of", firstId).put("code", "conflict"))
        assertNull(client.inFlight["mac"])
        assertEquals(PauseNotice.Conflict, client.notices["mac/coder-lab"])
        val conflictBlock = sandboxPauseBlock("mac", "coder-lab", true, true, false, client.states["mac"], "running", true, null, client.notices["mac/coder-lab"])!!
        assertEquals("mac's state changed since you looked. Refreshing…", conflictBlock.notice)
        client.received("mac", pauseStateJson(revision = 6L, sandboxes = listOf(sandboxRowJson("coder-lab", paused = false))))
        assertNull(client.notices["mac/coder-lab"])

        // not_found refreshes sandbox list.
        assertTrue(client.pause("mac", "coder-lab"))
        val secondId = sent.last().getString("id")
        client.received("mac", JSONObject().put("v", 1).put("id", "e-2").put("type", "error").put("of", secondId).put("code", "not_found"))
        assertEquals(PauseNotice.NotFound, client.notices["mac/coder-lab"])
        assertEquals(listOf("mac"), refreshed)

        // not_allowed removes control.
        assertTrue(client.pause("mac", "coder-lab"))
        val thirdId = sent.last().getString("id")
        client.received("mac", JSONObject().put("v", 1).put("id", "e-3").put("type", "error").put("of", thirdId).put("code", "not_allowed"))
        assertFalse(client.canControl("mac"))

        // Disconnect drops state and records disconnect notice without resending.
        client.authenticated("mac", bothCaps)
        client.received("mac", pauseStateJson(revision = 7L, sandboxes = listOf(sandboxRowJson("coder-lab", paused = false))))
        assertTrue(client.pause("mac", "coder-lab"))
        val sentBeforeDisconnect = sent.size
        isConnected = false
        client.ended("mac")
        assertNull("state dropped on disconnect", client.states["mac"])
        assertNull(client.inFlight["mac"])
        assertEquals(PauseNotice.Disconnected(PauseAction.Pause), client.notices["mac/coder-lab"])
        assertEquals("never resent automatically", sentBeforeDisconnect, sent.size)
        val discBlock = sandboxPauseBlock("mac", "coder-lab", false, false, false, null, "running", true, null, client.notices["mac/coder-lab"])!!
        assertEquals("The pause may have reached mac. Refreshing…", discBlock.notice)
    }

    @Test
    fun confirmation_dialog_text_matches_approved_paragraphs_and_omits_absent_usage() {
        val withUsageAndWaiting = pauseConfirmParagraphs(
            host = "mac",
            sandbox = "coder-lab",
            agentCount = 3,
            lastDayUsage = "1.2M tokens",
            waitingQuestions = 1,
            waitingHeldTasks = 1,
        )
        assertEquals(
            listOf(
                "3 agents run in coder-lab, and they used 1.2M tokens in the last 24 hours. Pausing stops the runtime of coder-lab now, including any answer being written. The rest of mac keeps running and keeps spending.",
                "Its files stay on mac: a pause does not write into any sandbox home. A question an agent is waiting on stops with it.",
                "Until the next update, mac can still start coder-lab again.",
                "Waiting for you in coder-lab: 1 question and 1 held task. Pausing does not answer them.",
            ),
            withUsageAndWaiting,
        )

        val noUsageNoWaiting = pauseConfirmParagraphs(
            host = "mac",
            sandbox = "coder-lab",
            agentCount = 1,
            lastDayUsage = null,
            waitingQuestions = 0,
            waitingHeldTasks = 0,
        )
        assertEquals(
            listOf(
                "1 agent runs in coder-lab. Pausing stops the runtime of coder-lab now, including any answer being written. The rest of mac keeps running and keeps spending.",
                "Its files stay on mac: a pause does not write into any sandbox home. A question an agent is waiting on stops with it.",
                "Until the next update, mac can still start coder-lab again.",
            ),
            noUsageNoWaiting,
        )

        val pluralQuestionsOnly = pauseConfirmParagraphs("mac", "coder-lab", 2, null, waitingQuestions = 2, waitingHeldTasks = 0)
        assertEquals("Waiting for you in coder-lab: 2 questions. Pausing does not answer them.", pluralQuestionsOnly.last())

        val zeroUsage = RecentUsage(agents = mapOf("coder-lab/main" to AgentUsage("coder-lab/main", UsageTotal(0, Tokens()), false)))
        assertNull("never show zero usage", sandboxLastDayUsageText(zeroUsage, "coder-lab"))

        val someUsage = RecentUsage(agents = mapOf("coder-lab/main" to AgentUsage("coder-lab/main", UsageTotal(3, Tokens(input = 1_200_000)), false)))
        assertEquals("1.2M tokens", sandboxLastDayUsageText(someUsage, "coder-lab"))
    }

    @Test
    fun sandbox_heading_agent_row_and_agent_page_helpers() {
        val sb = Sandbox("mac", "coder-lab", "hermes", state = "running", runtimeVersion = "0.23.1")
        assertEquals("paused · Hermes 0.23.1", sandboxHeadingNote(sb, paused = true))
        assertEquals("Hermes 0.23.1", sandboxHeadingNote(sb, paused = false))

        val pausedHost = parsePauseState(pauseStateJson(partly = true, sandboxes = listOf(sandboxRowJson("coder-lab", paused = true))))
        val stoppedHost = parsePauseState(pauseStateJson(sandboxes = listOf(sandboxRowJson("coder-lab", paused = false, state = "stopped"))))
        val unreadableHost = parsePauseState(pauseStateJson(sandboxes = listOf(sandboxRowJson("coder-lab", paused = false, state = "running", why = "bad"))))
        val runningHost = parsePauseState(pauseStateJson(sandboxes = listOf(sandboxRowJson("coder-lab", paused = false, state = "running"))))

        assertEquals("paused", sandboxAgentRowStatus(pausedHost, "running", "coder-lab"))
        assertEquals("stopped", sandboxAgentRowStatus(stoppedHost, "stopped", "coder-lab"))
        assertEquals("not known", sandboxAgentRowStatus(unreadableHost, "running", "coder-lab"))
        assertNull(sandboxAgentRowStatus(runningHost, "running", "coder-lab"))
        assertEquals("coder-lab is paused. Its runtime is stopped.", agentPausedNotice("coder-lab"))
    }

    @Test
    fun new_strings_contain_none_of_the_forbidden_phrases() {
        val forbidden = listOf(
            "until you resume",
            "does not run",
            "nothing runs",
            "finishes first",
            "answered when you resume",
            "waiting for the answer",
            "no answer yet",
            "Pausing ·",
            "Trying again may work",
        )
        val running = parsePauseState(pauseStateJson(sandboxes = listOf(sandboxRowJson("coder-lab", paused = false, state = "running"))))
        val partly = parsePauseState(pauseStateJson(partly = true, sandboxes = listOf(sandboxRowJson("coder-lab", paused = true), sandboxRowJson("a", paused = false))))
        val whole = parsePauseState(pauseStateJson(paused = true, sandboxes = listOf(sandboxRowJson("coder-lab", paused = true))))
        val resumed = parsePauseState(pauseStateJson(partly = true, sandboxes = listOf(sandboxRowJson("coder-lab", paused = false), sandboxRowJson("a", paused = true))))
        val stopped = parsePauseState(pauseStateJson(sandboxes = listOf(sandboxRowJson("coder-lab", paused = false, state = "stopped"))))
        val unreadable = parsePauseState(pauseStateJson(sandboxes = listOf(sandboxRowJson("coder-lab", paused = false, why = "bad"))))

        val blocks = listOfNotNull(
            sandboxPauseBlock("mac", "coder-lab", true, true, false, running, "running", true, null, null),
            sandboxPauseBlock("mac", "coder-lab", true, true, false, partly, "running", true, null, null),
            sandboxPauseBlock("mac", "coder-lab", true, true, false, whole, "running", true, null, null),
            sandboxPauseBlock("mac", "coder-lab", true, true, false, resumed, "running", true, null, PauseNotice.ResumedByThisDevice),
            sandboxPauseBlock("mac", "coder-lab", true, true, false, running, "running", true, PauseInFlight("1", "mac", "coder-lab", PauseAction.Pause), null),
            sandboxPauseBlock("mac", "coder-lab", true, true, false, partly, "running", true, PauseInFlight("2", "mac", "coder-lab", PauseAction.Resume), null),
            sandboxPauseBlock("mac", "coder-lab", true, true, false, running, "running", true, null, PauseNotice.WouldNotStop),
            sandboxPauseBlock("mac", "coder-lab", true, true, false, partly, "running", true, null, PauseNotice.WouldNotStart),
            sandboxPauseBlock("mac", "coder-lab", true, true, false, stopped, "stopped", true, null, null),
            sandboxPauseBlock("mac", "coder-lab", false, false, false, null, "running", true, null, PauseNotice.Disconnected(PauseAction.Pause)),
            sandboxPauseBlock("mac", "coder-lab", true, true, false, unreadable, "running", true, null, null),
            sandboxPauseBlock("mac", "coder-lab", true, true, false, running, "running", true, null, PauseNotice.Conflict),
            sandboxPauseBlock("mac", "coder-lab", true, true, false, running, "running", true, null, PauseNotice.NotFound),
        )
        val allStrings = buildList {
            blocks.forEach {
                add(it.heading)
                add(it.headingDescription)
                add(it.stateLine)
                it.button?.let { b -> add(b.label) }
                it.notice?.let(::add)
            }
            addAll(pauseConfirmParagraphs("mac", "coder-lab", 2, "1.2M tokens", 1, 2))
            addAll(pauseConfirmParagraphs("mac", "coder-lab", 1, null, 0, 0))
            add(agentPausedNotice("coder-lab"))
        }
        for (text in allStrings) {
            for (bad in forbidden) {
                assertFalse("Forbidden phrase '$bad' found in '$text'", text.contains(bad, ignoreCase = true))
            }
        }
    }
}
