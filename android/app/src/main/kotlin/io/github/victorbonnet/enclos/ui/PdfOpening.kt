package io.github.victorbonnet.enclos.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.pdf.PdfDocument
import androidx.pdf.PdfLoader
import androidx.pdf.SandboxedPdfLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.CoroutineContext

private val PdfOpeningDispatcher = Dispatchers.IO.limitedParallelism(1)
private val PdfDocumentDispatcher = Dispatchers.IO.limitedParallelism(1)
private val PdfOpeningScope = CoroutineScope(SupervisorJob() + PdfOpeningDispatcher)
private val PdfCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
// Keep this held across suspensions too: the dispatcher alone only limits
// executing threads, so several pending service connections could otherwise open.
private val PdfOpeningLock = Mutex()

/** A document and its binding, released together and at most once. Close on IO. */
internal class OpenedPdfDocument(val document: PdfDocument, private val release: () -> Unit) : Closeable {
    override fun close() = release()
}

/**
 * AndroidX beta01 has no public loader cancellation/close API. Own its public
 * Context bindings and descriptor so a failed or dismissed open releases both.
 * A synchronous Binder call cannot be interrupted; its eventual result is closed
 * without making the viewer wait, and only one such open can run at a time.
 */
internal suspend fun openPdfDocument(
    context: Context,
    file: File,
    timeoutMillis: Long = 30_000,
    createLoader: (Context, CoroutineDispatcher) -> PdfLoader = { owner, dispatcher -> SandboxedPdfLoader(owner, dispatcher) },
): OpenedPdfDocument {
    // Own resources outside the timeout too: it can discard an already-resumed
    // value while returning, after the continuation's cancellation hook ran.
    val resources = PdfOpeningResources(PdfServiceContext(context.applicationContext))
    try {
        return withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                val worker = PdfOpeningScope.launch(start = CoroutineStart.LAZY) {
                    try {
                        PdfOpeningLock.withLock {
                            coroutineContext.ensureActive()
                            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                            if (!resources.attachDescriptor(descriptor)) return@withLock
                            val document = createLoader(resources.context, resources.documentDispatcher).openDocument(
                                Uri.fromFile(file), descriptor, password = null,
                            )
                            if (!resources.attachDocument(document)) return@withLock
                            val opened = OpenedPdfDocument(document, resources::close)
                            continuation.resume(opened) { _, _, _ -> resources.cancel() }
                        }
                    } catch (error: CancellationException) {
                        resources.cancel()
                        continuation.cancel(error)
                    } catch (error: Exception) {
                        resources.cancel()
                        continuation.resumeWithException(error)
                    }
                }
                resources.worker = worker
                continuation.invokeOnCancellation {
                    resources.cancel()
                }
                worker.start()
            }
        } ?: throw IOException("Opening the PDF timed out")
    } catch (error: Exception) {
        resources.cancel()
        throw error
    }
}

private class PdfOpeningResources(val context: PdfServiceContext) {
    var worker: Job? = null
    private var closed = false
    private var documentOperationsStarted = false
    private var descriptor: ParcelFileDescriptor? = null
    private var document: PdfDocument? = null

    // Pinned beta01 SandboxedPdfLoader's descriptor overload first suspends in
    // connect(uri), then enters withContext(loaderDispatcher) to create the PDF.
    // Keep connect cancellable; after this dispatch, preserve the eventual result
    // across the library's own dispatcher return so we can close it if dismissed.
    val documentDispatcher = object : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext) = true
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            synchronized(this@PdfOpeningResources) {
                if (!closed) documentOperationsStarted = true
            }
            PdfDocumentDispatcher.dispatch(context, block)
        }
    }

    fun attachDescriptor(value: ParcelFileDescriptor): Boolean {
        val accepted = synchronized(this) {
            if (closed) false else { descriptor = value; true }
        }
        if (!accepted) closeDescriptor(value)
        return accepted
    }

    fun attachDocument(value: PdfDocument): Boolean {
        val accepted = synchronized(this) {
            if (closed) false else { document = value; true }
        }
        if (!accepted) closeDocument(value)
        return accepted
    }

    fun close() { release()?.let(::closeDocument) }

    fun cancel() {
        // Do not queue cleanup behind a potentially blocked PDF opener.
        release()?.let { document -> PdfCleanupScope.launch { closeDocument(document) } }
    }

    private fun release(): PdfDocument? {
        val owned = synchronized(this) {
            if (closed) return null
            closed = true
            // Cancel under the same lock as the dispatcher stage marker, before
            // any document body can start. Binder work already started is bounded
            // to one opener and must return before its result can be disposed.
            if (!documentOperationsStarted) worker?.cancel()
            (descriptor to document).also { descriptor = null; document = null }
        }
        try { context.close() } finally { owned.first?.let(::closeDescriptor) }
        return owned.second
    }
}

/** Tracks only this loader's service bindings, including failure before a document exists. */
private class PdfServiceContext(context: Context) : ContextWrapper(context), Closeable {
    private var closed = false
    private val connections = Collections.newSetFromMap(IdentityHashMap<ServiceConnection, Boolean>())

    // SandboxedPdfLoader retains applicationContext; keep this ownership boundary.
    override fun getApplicationContext(): Context = this

    override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean = synchronized(this) {
        if (closed) throw CancellationException("The PDF viewer was closed")
        if (!super.bindService(service, connection, flags)) throw IOException("The PDF renderer is unavailable")
        connections.add(connection)
        true
    }

    override fun unbindService(connection: ServiceConnection) {
        if (synchronized(this) { connections.remove(connection) }) super.unbindService(connection)
    }

    override fun close() {
        val bound = synchronized(this) {
            if (closed) return
            closed = true
            connections.toList().also { connections.clear() }
        }
        bound.forEach { connection ->
            try { super.unbindService(connection) } catch (_: RuntimeException) {
                // A missing registration or framework error must not skip the
                // remaining bindings, descriptor or document during release.
            }
        }
    }
}

private fun closeDescriptor(descriptor: ParcelFileDescriptor) {
    try { descriptor.close() } catch (_: IOException) { /* Best-effort release. */ }
}

private fun closeDocument(document: PdfDocument) {
    try { document.close() } catch (_: IOException) { /* Best-effort release. */ }
    catch (_: RuntimeException) { /* The renderer or binding may already be gone. */ }
}
