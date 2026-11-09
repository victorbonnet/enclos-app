package io.github.victorbonnet.enclos.ui

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.pdf.PdfDocument
import androidx.pdf.PdfPasswordException
import androidx.pdf.content.ExternalLink
import androidx.pdf.view.PdfView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Read-only PDF content. AndroidX parses and renders in its isolated service;
 * PdfView keeps bitmaps near the viewport and supplies its own scroll and zoom.
 * The containing viewer owns Back, Share and Open with.
 */
@Composable
fun PdfPreview(file: File, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val session = remember(file, context) { PdfPreviewSession() }
    LaunchedEffect(session) {
        var opened: OpenedPdfDocument? = null
        try {
            opened = openPdfDocument(context, file)
            session.document = opened.document
            // PdfView has no public render-error callback. A document which
            // opened but cannot paint its first page still needs a way out.
            val displayed = withTimeoutOrNull(30_000) { session.firstContent.await() }
            if (displayed == true) {
                awaitCancellation()
            } else if (session.notice == null) {
                session.notice = "This PDF could not be displayed. You can still open it in another app or share it."
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            session.notice = when (error) {
                is PdfPasswordException -> "This PDF needs a password. Open it in another app to unlock it."
                else -> "This PDF could not be opened. You can still open it in another app or share it."
            }
        } finally {
            // Detach the view before closing the document it renders. beta01's
            // pdfDocument setter ignores null, so discard the child view instead.
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                session.document = null
                session.detach()
            }
            withContext(NonCancellable + Dispatchers.IO) {
                opened?.close()
            }
        }
    }
    key(session) {
        Box(modifier.background(palette.bg)) {
            val document = session.document
            if (document != null && session.notice == null) {
                AndroidView(
                    factory = { viewContext ->
                        FrameLayout(viewContext).also { host ->
                            session.host = host
                            try {
                                val view = PdfView(viewContext).apply {
                                    isFormFillingEnabled = false
                                    setLinkClickListener(object : PdfView.LinkClickListener {
                                        // The default listener opens arbitrary URI schemes.
                                        // Keep document links inside this read-only preview.
                                        override fun onLinkClicked(externalLink: ExternalLink) = true
                                    })
                                    addOnFirstContentLoadListener {
                                        session.opening = false
                                        session.firstContent.complete(true)
                                    }
                                }
                                host.addView(view, ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                ))
                                // Attach first so a failed document assignment
                                // receives the same detach cleanup as a shown PDF.
                                view.pdfDocument = document
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                host.removeAllViews()
                                session.notice = "The PDF viewer is unavailable. You can still open it in another app or share it."
                                session.firstContent.complete(false)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { host -> session.detach(host) },
                )
            }
            val notice = session.notice
            if (notice != null || session.opening) {
                Box(
                    Modifier.fillMaxSize().background(palette.bg).padding(24.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(notice ?: "Opening the PDF…", style = Type.body, color = palette.textMuted)
                }
            }
        }
    }
}

/** Main-thread view ownership, separate from the worker-thread document lifetime. */
private class PdfPreviewSession {
    var document by mutableStateOf<PdfDocument?>(null)
    var opening by mutableStateOf(true)
    var notice by mutableStateOf<String?>(null)
    val firstContent = CompletableDeferred<Boolean>()
    var host: FrameLayout? = null

    fun detach(view: FrameLayout? = host) {
        view?.removeAllViews()
        if (host === view) host = null
    }
}
