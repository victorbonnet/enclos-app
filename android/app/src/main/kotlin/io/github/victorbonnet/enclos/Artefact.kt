package io.github.victorbonnet.enclos

import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

/** One self-contained UTF-8 page. Its address identifies only this in-memory document. */
internal class ArtefactDocument(val bytes: ByteArray) {
    val url = "https://artefact.invalid/${UUID.randomUUID()}/index.html"

    /** The only navigation a page may make is to an anchor of itself. */
    fun isAnchor(target: String) = target.startsWith("$url#")

    fun isDocument(target: String, mainFrame: Boolean, method: String) =
        mainFrame && method == "GET" && target.substringBefore('#') == url

    companion object {
        // The bridge's per-file limit; also bound what the viewer reads into memory.
        const val MAX_BYTES = 25 * 1024 * 1024
        const val CSP = "sandbox; default-src 'none'; style-src 'unsafe-inline'; img-src data:; font-src data:; base-uri 'none'; form-action 'none'; frame-src 'none'"

        fun read(file: File): ArtefactDocument {
            require(file.length() <= MAX_BYTES) { "The page is larger than 25 MiB." }
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
            require(bytes.size <= MAX_BYTES) { "The page is larger than 25 MiB." }
            checkUtf8(bytes)
            return ArtefactDocument(bytes)
        }

        /**
         * Reports a broken encoding rather than showing corrupted text; an
         * optional UTF-8 BOM is valid. Decodes through a small buffer that is
         * reused, so a 25 MiB page never also takes 50 MiB of characters.
         */
        internal fun checkUtf8(bytes: ByteArray, chunk: Int = 8192) {
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val input = ByteBuffer.wrap(bytes)
            val output = CharBuffer.allocate(chunk)
            while (true) {
                val result = decoder.decode(input, output, true)
                if (result.isError) result.throwException()
                output.clear()
                if (result.isUnderflow) break
            }
            while (true) {
                val result = decoder.flush(output)
                if (result.isError) result.throwException()
                output.clear()
                if (result.isUnderflow) break
            }
        }
    }
}
