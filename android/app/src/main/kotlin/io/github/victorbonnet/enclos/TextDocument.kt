package io.github.victorbonnet.enclos

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Bounded, literal UTF-8 text for the document viewer. */
internal object TextDocument {
    const val MAX_BYTES = 1024 * 1024
    /** The most characters one block of the viewer's lazy list lays out at once. */
    const val BLOCK_CHARS = 4096

    /** U+FEFF, an optional byte order mark at the start. */
    private val BOM = Char(0xFEFF).toString()
    private const val TOO_LARGE = "This text file is too large for an in-app preview."

    fun read(file: File): String {
        require(file.length() <= MAX_BYTES) { TOO_LARGE }
        val bytes = file.inputStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var remaining = MAX_BYTES + 1
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (count < 0) break
                output.write(buffer, 0, count)
                remaining -= count
            }
            output.toByteArray()
        }
        require(bytes.size <= MAX_BYTES) { TOO_LARGE }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix(BOM)
    }

    /**
     * [text] cut into blocks of at most [max] characters, each drawn by one
     * item of a lazy list, so a megabyte is never laid out at once. A block
     * ends after a line break when one fits, else at [max] (a long line, as
     * minified JSON has, continues in the next block), never inside a
     * surrogate pair. The blocks put together are [text].
     */
    fun blocks(text: String, max: Int = BLOCK_CHARS): List<String> {
        require(max >= 2)
        val out = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + max, text.length)
            if (end < text.length) {
                val newline = text.lastIndexOf('\n', end - 1)
                if (newline >= start) end = newline + 1
                else if (Character.isHighSurrogate(text[end - 1])) end -= 1
            }
            out += text.substring(start, end)
            start = end
        }
        return out
    }
}
