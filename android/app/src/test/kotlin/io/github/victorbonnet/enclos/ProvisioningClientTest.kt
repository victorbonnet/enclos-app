package io.github.victorbonnet.enclos

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProvisioningClientTest {
    private class Fixture {
        var connected = true
        var sending = true
        val sent = mutableListOf<Pair<String, JSONObject>>()
        val client = ProvisioningClient(connected = { connected }, send = { host, request -> sent += host to request; sending })
        fun ready(host: String = "mac") {
            client.authenticated(host, setOf("sandboxes"))
            client.received(host, JSONObject("""{"type":"sandboxes.list","sandboxes":[{"sandbox":"home","runtime":null,"state":"created"}]}"""))
            client.received(host, JSONObject("""{"type":"runtimes.list","runtimes":[{"runtime":"hermes","name":"Hermes","description":"Install Hermes"}]}"""))
        }
        fun install(host: String = "mac"): SandboxOperation {
            assertTrue(client.installRuntime(host, "home", "hermes"))
            return client.provisioning.getValue("$host/home")
        }
    }

    private fun result(operation: SandboxOperation, type: String = "runtime.installed", sandbox: String = "home", runtime: String = "hermes") =
        JSONObject().put("type", type).put("of", operation.request).put("sandbox", sandbox).put("runtime", runtime).put("login", "enclos connect home -- hermes setup")

    @Test fun mismatched_terminal_replies_fail_instead_of_leaving_progress_busy() {
        for (reply in listOf<(SandboxOperation) -> JSONObject>(
            { result(it, runtime = "other") },
            { result(it, sandbox = "different") },
            { result(it, type = "sandbox.created") },
        )) {
            val f = Fixture(); f.ready(); val operation = f.install()
            assertTrue(f.client.received("mac", reply(operation)))
            val failed = f.client.provisioning.getValue("mac/home")
            assertFalse(failed.busy)
            assertFalse(failed.done)
            assertNotNull(failed.error)
            assertFalse(f.client.received("mac", result(operation)))
            assertNull(f.client.sandboxesOf("mac").single().runtime)
        }
    }

    @Test fun reconnect_without_capability_drops_host_person_metadata_and_old_requests_only_for_that_host() {
        val f = Fixture(); f.ready(); f.ready("pi")
        val old = f.install(); f.install("pi")
        f.client.authenticated("mac", emptySet())
        assertFalse(f.client.canManageSandboxes("mac"))
        assertTrue(f.client.sandboxesOf("mac").isEmpty())
        assertNull(f.client.runtimes["mac"])
        assertNull(f.client.provisioning["mac/home"])
        assertTrue(f.client.provisioning.getValue("pi/home").busy)
        assertFalse(f.client.received("mac", result(old)))
    }

    @Test fun disconnect_and_replaced_connection_fail_pending_work_without_replaying_it() {
        for (replace in listOf(false, true)) {
            val f = Fixture(); f.ready(); val old = f.install()
            if (replace) f.client.authenticated("mac", setOf("sandboxes")) else f.client.ended("mac")
            val failed = f.client.provisioning.getValue("mac/home")
            assertFalse(failed.busy)
            assertFalse(failed.done)
            assertNotNull(failed.error)
            assertFalse(f.client.received("mac", result(old)))
            assertEquals(1, f.sent.count { it.second.optString("type") == "runtime.install" })
        }
    }

    @Test fun errors_are_consumed_only_for_the_matching_host_and_request() {
        val f = Fixture(); f.ready(); val operation = f.install()
        val error = JSONObject().put("type", "error").put("of", operation.request).put("message", "Installer failed")
        assertFalse(f.client.received("pi", error))
        assertTrue(f.client.provisioning.getValue("mac/home").busy)
        assertFalse(f.client.received("mac", JSONObject(error.toString()).put("of", "archive-request")))
        assertTrue(f.client.received("mac", error))
        assertEquals("Installer failed", f.client.provisioning.getValue("mac/home").error)
        assertFalse(f.client.received("mac", error))
        assertFalse(f.client.received("mac", result(operation)))
    }

    @Test fun matching_completion_confirms_runtime_and_remaining_setup_then_refreshes_inventory() {
        val f = Fixture(); f.ready(); val operation = f.install()
        assertFalse(f.client.received("pi", result(operation)))
        assertTrue(f.client.received("mac", result(operation)))
        val completed = f.client.provisioning.getValue("mac/home")
        assertTrue(completed.done)
        assertFalse(completed.busy)
        assertEquals("enclos connect home -- hermes setup", completed.login)
        assertEquals("hermes", f.client.sandboxesOf("mac").single().runtime)
        assertEquals(listOf("sandboxes", "runtimes", "agents"), f.sent.takeLast(3).map { it.second.getString("type") })
    }

    @Test fun create_sends_selected_template_and_keeps_an_empty_sandbox_after_confirmation() {
        val f = Fixture(); f.ready()
        assertTrue(f.client.createSandbox("mac", "new-home", "assistant"))
        val command = f.sent.last().second
        assertEquals("sandbox.create", command.getString("type"))
        assertEquals("assistant", command.getString("template"))
        assertTrue(f.client.received("mac", JSONObject().put("type", "sandbox.created").put("of", command.getString("id")).put("sandbox", "new-home")))
        assertNull(f.client.sandboxesOf("mac").first { it.name == "new-home" }.runtime)
    }
    @Test fun progress_and_send_failures_cannot_leave_untracked_requests_busy() {
        val f = Fixture(); f.ready(); val operation = f.install()
        val progress = JSONObject().put("type", "operation.progress").put("of", operation.request)
            .put("operation", "runtime.install").put("sandbox", "home").put("message", "Downloading")
        assertFalse(f.client.received("pi", progress))
        assertFalse(f.client.received("mac", JSONObject(progress.toString()).put("of", "different")))
        assertTrue(f.client.received("mac", JSONObject(progress.toString()).put("sandbox", "other")))
        assertEquals(operation.message, f.client.provisioning.getValue("mac/home").message)
        assertTrue(f.client.received("mac", progress))
        assertEquals("Downloading", f.client.provisioning.getValue("mac/home").message)
        f.client.ended("mac")
        f.ready(); f.sending = false
        assertFalse(f.client.installRuntime("mac", "home", "hermes"))
        val failed = f.client.provisioning.getValue("mac/home")
        assertFalse(failed.busy)
        assertNotNull(failed.error)
        assertFalse(f.client.received("mac", result(failed)))
    }

}
