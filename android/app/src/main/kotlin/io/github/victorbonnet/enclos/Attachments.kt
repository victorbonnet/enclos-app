package io.github.victorbonnet.enclos

/**
 * The rules for a message's files on the phone, apart from Android so they
 * are tested on the JVM: which ids are kept, where a file is written, how a
 * fetch goes on, and how large an image is decoded and drawn.
 */
object Attachments {
    /**
     * On a metered network (mobile data, a hotspot), a file above this size
     * is fetched when the person taps it, not when it is shown.
     */
    const val FETCH_ON_TAP = 10_000_000L

    /** The largest file the bridge sends (25 MiB): on an unmetered network, any file up to it comes by itself. */
    const val MOST_SIZE = 25L * 1024 * 1024

    /**
     * Whether a file of [size] bytes is fetched as soon as it is shown, or
     * waits for a tap: on an unmetered network, any file up to [MOST_SIZE];
     * on a [metered] one, a file up to [FETCH_ON_TAP], so an agent cannot
     * make the phone pull megabytes over mobile data unasked.
     */
    fun fetchesItself(size: Long, metered: Boolean): Boolean =
        size <= if (metered) FETCH_ON_TAP else MOST_SIZE

    /** The longest name a file is kept under on the phone, its extension included. */
    const val MOST_NAME = 80

    private val idForm = Regex("[A-Za-z0-9-]{1,64}")

    /**
     * Whether [id] is one the app keeps: the bridge's ids are UUIDs, and an
     * id names a folder, so `..`, a slash or anything else is refused.
     */
    fun validId(id: String): Boolean = idForm.matches(id)

    /**
     * [text] as one path segment: letters, digits, `.`, `_`, space and `-`
     * only, not starting with a dot, at most [MOST_NAME] characters with the
     * extension kept, and [fallback] when nothing is left.
     */
    fun segment(text: String, fallback: String): String {
        val plain = text.replace(Regex("[^A-Za-z0-9._ -]"), "_").trimStart('.').trim()
        if (plain.isEmpty()) return fallback
        if (plain.length <= MOST_NAME) return plain
        val dot = plain.lastIndexOf('.')
        val ext = if (dot > 0 && plain.length - dot <= 16) plain.substring(dot) else ""
        return plain.take(MOST_NAME - ext.length).trimEnd() + ext
    }

    /** The name a file is kept under, which sharing shows: [segment] of the agent's name. */
    fun fileName(name: String): String = segment(name, "file")

    /** The folder of [host]'s files under the attachments folder: one per host, so two hosts' ids never meet. */
    fun hostFolder(host: String): String = segment(host, "host")
}

/** What to do with a part of a file being fetched (`attachment.data`). */
sealed interface Part {
    /** An earlier part again: dropped, the one asked for is still to come. */
    data object Stale : Part
    /** A part beyond the one asked for, or an empty one before the end: the fetch fails, to be tapped again. */
    data object Wrong : Part
    /** Written; then the next part from [at] is asked for, or the file is complete. */
    data class Write(val at: Long, val complete: Boolean) : Part
}

/**
 * A part of [bytes] at [offset], for a file of [size] bytes whose next part
 * starts at [expected] (what is written so far).
 */
fun part(expected: Long, offset: Long, bytes: Int, size: Long): Part {
    if (offset < expected) return Part.Stale
    if (offset > expected || (bytes == 0 && expected < size)) return Part.Wrong
    val at = expected + bytes
    return Part.Write(at, at >= size)
}

/** How large an image is decoded and drawn: within what Android and Compose can hold, whatever the file. */
object Images {
    /**
     * The most bytes a decoded image may take: Android refuses to draw a
     * bitmap above 100 MB, and memory runs out sooner on small phones.
     */
    const val MOST_BYTES = 64L * 1024 * 1024

    /** The widest and tallest an image is drawn in a bubble, as width over height. */
    const val MOST_RATIO = 3f

    /**
     * The power of two to sample a [width] by [height] image by, so that
     * its longest side is at most [longest] pixels and it takes at most
     * [MOST_BYTES] at 4 bytes a pixel.
     */
    fun sampleSize(width: Int, height: Int, longest: Int): Int {
        var sample = 1
        while (true) {
            val w = (width.toLong() + sample - 1) / sample
            val h = (height.toLong() + sample - 1) / sample
            if (maxOf(w, h) <= longest && w * h * 4 <= MOST_BYTES) return sample
            sample *= 2
        }
    }

    /** Whether a decoded image of [bytes] may be drawn. */
    fun drawable(bytes: Long): Boolean = bytes in 1..MOST_BYTES

    /**
     * The shape of an image's box in a bubble: its own, between 1:3 and
     * 3:1, the image fitted inside. A thin, tall image is never taller than
     * three times the bubble's width.
     */
    fun ratio(width: Int, height: Int): Float =
        (width.coerceAtLeast(1).toFloat() / height.coerceAtLeast(1)).coerceIn(1 / MOST_RATIO, MOST_RATIO)
}
