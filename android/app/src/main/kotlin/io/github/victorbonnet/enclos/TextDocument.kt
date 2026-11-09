package io.github.victorbonnet.enclos

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Bounded, literal UTF-8 text for the document viewer. */
internal object TextDocument {
    const val MAX_BYTES = 1024 * 1024

    fun read(file: File): String {
        require(file.length() <= MAX_BYTES) { "This text file is too large for an in-app preview. Open it in another app." }
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
        require(bytes.size <= MAX_BYTES) { "This text file is too large for an in-app preview. Open it in another app." }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
    }
}
