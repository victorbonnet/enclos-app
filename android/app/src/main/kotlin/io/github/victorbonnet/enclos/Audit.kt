package io.github.victorbonnet.enclos

/** One host audit entry; times are seconds since the Unix epoch. */
data class HostAuditEntry(
    val seq: Long,
    val time: Long,
    val user: String,
    val host: String,
    val command: String,
    val target: String?,
    val result: String,
    val message: String?,
    val finished: Long?,
)

/** A page request belongs to one host; late responses cannot replace newer data. */
data class HostAudit(
    val entries: List<HostAuditEntry> = emptyList(),
    val nextBefore: Long? = null,
    val loaded: Boolean = false,
    val request: String? = null,
    val before: Long? = null,
    val error: String? = null,
) {
    fun loading(id: String, before: Long?) = copy(request = id, before = before, error = null)

    fun received(of: String, page: List<HostAuditEntry>, next: Long?): HostAudit {
        if (request != of) return this
        val all = if (before == null) page else entries + page
        return copy(entries = all.distinctBy { it.seq }.sortedByDescending { it.seq }, nextBefore = next, loaded = true, request = null, error = null)
    }

    fun failed(of: String, why: String): HostAudit =
        if (request == of) copy(request = null, error = why) else this
}
