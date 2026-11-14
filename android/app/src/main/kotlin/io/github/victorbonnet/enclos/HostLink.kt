package io.github.victorbonnet.enclos

/**
 * Where the app's connection to one host stands. Only the bridge's
 * `authenticated`, over the Noise session, makes it [Connected]: why a
 * connection ended is text from either side (a WebSocket close reason is not
 * sealed), so no text is ever read back as a state.
 */
sealed interface HostLink {
    /** Opening, until the bridge says `authenticated`; [lastEnded] is why the previous attempt ended, kept on screen while retrying. */
    data class Connecting(val lastEnded: String? = null) : HostLink

    /** Authenticated, speaking for [person]. */
    data class Connected(val person: String) : HostLink

    /** Ended, saying [why]. */
    data class Ended(val why: String) : HostLink
}

/** Whether this host is connected and authenticated. */
val HostLink?.connected: Boolean get() = this is HostLink.Connected

/** A new attempt: why the last one ended stays on screen while it is under way. */
fun HostLink?.reopening(): HostLink.Connecting = when (this) {
    is HostLink.Ended -> HostLink.Connecting(why)
    is HostLink.Connecting -> this
    is HostLink.Connected, null -> HostLink.Connecting()
}

/** The line the host's screens show for it. */
fun HostLink?.shown(): String = when (this) {
    null -> "not connected"
    is HostLink.Connecting -> lastEnded ?: "connecting"
    is HostLink.Connected -> "connected as $person"
    is HostLink.Ended -> why
}
