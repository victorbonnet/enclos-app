package io.github.victorbonnet.enclos

/** Closing dismisses the host's request without making a policy decision. */
enum class PolicyRequestAction {
    Approve, Deny, Close;

    val type get() = if (this == Close) "approval.close" else "approval.decide"

    fun fields(request: PolicyRequest): List<Pair<String, Any?>> = if (this == Close) {
        listOf("request" to request.id)
    } else {
        listOf("request" to request.id, "revision" to request.revision, "approve" to (this == Approve))
    }

    fun allowed(request: PolicyRequest, connected: Boolean, fresh: Boolean, supportsClose: Boolean, busy: Boolean): Boolean {
        if (!connected || !fresh || busy) return false
        return when (this) {
            Approve -> request.pending && request.available
            Deny -> request.pending
            Close -> supportsClose && (request.pending || request.state == "failed")
        }
    }
}

data class PendingPolicyRequestAction(val messageId: String, val action: PolicyRequestAction)

/** A closed request is terminal, even if an earlier broadcast arrives after it. */
fun newestPolicyRequest(current: PolicyRequest?, incoming: PolicyRequest): PolicyRequest =
    if (current != null && current.key == incoming.key && current.state == "closed" && incoming.state != "closed") current else incoming
