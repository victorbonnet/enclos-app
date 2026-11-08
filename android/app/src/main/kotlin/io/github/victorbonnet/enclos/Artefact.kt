package io.github.victorbonnet.enclos

import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
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
            // Report a broken encoding rather than showing corrupted text; optional UTF-8 BOM is valid.
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes))
            return ArtefactDocument(bytes)
        }
    }
}
