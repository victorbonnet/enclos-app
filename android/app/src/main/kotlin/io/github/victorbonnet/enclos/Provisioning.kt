package io.github.victorbonnet.enclos

/** A sandbox listed by its host, including one that has no runtime or agent yet. */
data class Sandbox(val host: String, val name: String, val runtime: String?, val state: String? = null) {
    val address: String get() = "$host/$name"
}

/** A runtime the host offers for installation, in the host's order. */
data class RuntimeOption(val runtime: String, val name: String, val description: String)

/** A host operation, correlated with the request that started it. */
data class SandboxOperation(
    val request: String,
    val operation: String,
    val sandbox: String,
    val runtime: String? = null,
    val busy: Boolean = true,
    val message: String,
    val error: String? = null,
    val done: Boolean = false,
    /** The host's remaining setup command, if its runtime needs an interactive login. */
    val login: String? = null,
) {
    fun progress(of: String, operation: String, sandbox: String, message: String): SandboxOperation =
        if (busy && request == of && this.operation == operation && this.sandbox == sandbox) copy(message = message) else this

    fun completed(of: String, operation: String, sandbox: String, message: String, login: String? = null): SandboxOperation =
        if (busy && request == of && this.operation == operation && this.sandbox == sandbox) {
            copy(busy = false, message = message, done = true, login = login)
        } else this

    fun failed(of: String, why: String): SandboxOperation =
        if (busy && request == of) copy(busy = false, message = why, error = why) else this

    fun disconnected(): SandboxOperation = failed(
        request,
        "The connection ended before the host confirmed the result. Reconnect to check the sandbox before trying again.",
    )
}

/** The host's sandbox naming rule: 2–41 ASCII characters, beginning with a lowercase letter. */
fun sandboxNameError(name: String): String? =
    if (Regex("[a-z][a-z0-9-]{1,40}").matches(name)) null
    else "Use 2 to 41 characters: a lowercase letter, then lowercase letters, digits or hyphens."
