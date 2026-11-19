package io.github.victorbonnet.enclos

/** A sandbox listed by its host, including one that has no runtime or agent yet. */
data class Sandbox(
    val host: String,
    val name: String,
    val runtime: String?,
    val state: String? = null,
    /** Its runtime's version installed, when the host knows it (enclos ADR 0074). */
    val runtimeVersion: String? = null,
    /** Its runtime's latest release, when the host looked it up. */
    val release: RuntimeRelease? = null,
) {
    val address: String get() = "$host/$name"

    /** Its runtime and the version installed, `Hermes 0.23.1`; the runtime alone when the host does not know it. */
    val runtimeShown: String? get() = runtime?.let { listOfNotNull(runtimeName(it), runtimeVersion).joinToString(" ") }

    /** Its runtime's latest release, when it is newer than the version installed. */
    val update: RuntimeRelease? get() = release?.takeIf { it.newer == true }
}

/** A runtime's latest release, the one its installer installs by default, as the host found it. */
data class RuntimeRelease(
    val version: String,
    /** Whether the host's enclos was checked with it. */
    val checked: Boolean,
    /** Whether it is newer than the version installed: null when the host cannot tell. */
    val newer: Boolean?,
) {
    /** `0.23.2 available · checked with this host's enclos`. */
    val shown: String
        get() = "$version available · " + if (checked) "checked with this host's enclos" else "not checked with this host's enclos yet"
}

/** A runtime's name as people write it. */
fun runtimeName(runtime: String): String = when (runtime) {
    "hermes" -> "Hermes"
    "openclaw" -> "OpenClaw"
    else -> runtime.replaceFirstChar { it.uppercase() }
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
