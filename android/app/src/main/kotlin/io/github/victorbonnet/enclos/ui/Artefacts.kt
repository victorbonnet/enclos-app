package io.github.victorbonnet.enclos.ui

import android.content.Context
import android.view.View
import android.webkit.CookieManager
import android.widget.Toast
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import io.github.victorbonnet.enclos.ArtefactDocument
import io.github.victorbonnet.enclos.Attachment
import io.github.victorbonnet.enclos.LinkDestination
import io.github.victorbonnet.enclos.WebLinks
import io.github.victorbonnet.enclos.openWebLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.CharacterCodingException

/** A page has its own surface: the message itself is still native markdown. */
@Composable
fun ArtefactViewer(a: Attachment, file: File, close: () -> Unit) {
    val context = LocalContext.current
    val p = palette
    var shareMissing by remember(file) { mutableStateOf(false) }
    // A link the page asked to open, waiting for the person to confirm it.
    var leaving by remember(file) { mutableStateOf<LinkDestination?>(null) }
    var sharing by remember(file) { mutableStateOf(false) }
    val loaded by produceState<Result<ArtefactDocument>?>(null, file) {
        value = withContext(Dispatchers.IO) { runCatching { ArtefactDocument.read(file) } }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        val lightBars = p.bg.luminance() > 0.5f
        LaunchedEffect(view, lightBars) {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = lightBars
                WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = lightBars
            }
        }
        Column(Modifier.fillMaxSize().background(p.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(Glyphs.back, "Back", close)
                Text(a.name, style = Type.small, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Box(
                    Modifier.height(44.dp).clickable(onClickLabel = "Share the page", role = Role.Button) {
                        if (file.isFile) sharing = true else shareMissing = true
                    }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("Share", style = Type.label, color = p.accent) }
            }
            val result = loaded
            when {
                shareMissing -> PageNotice("The cached file was cleared. Close the page and open it again to fetch it.")
                result == null -> PageNotice("Opening the page…")
                result.isFailure -> PageNotice(
                    when (val error = result.exceptionOrNull()) {
                        is CharacterCodingException -> "This page is not UTF-8. Ask the agent to send it as UTF-8."
                        is IllegalArgumentException -> error.message ?: "This page cannot be opened."
                        else -> "This page cannot be read. Close it and try fetching it again."
                    },
                )
                else -> StaticPage(result.getOrThrow()) { leaving = it }
            }
        }
        leaving?.let { destination ->
            WebsiteConfirmation(destination, "this page", dismiss = { leaving = null }) {
                leaving = null
                openWebLink(context, destination.address)
            }
        }
        if (sharing) AlertDialog(
            onDismissRequest = { sharing = false },
            title = { Text("Share the page's file?") },
            text = {
                Text(
                    "The app you choose shows it its own way. It may run scripts the page holds and load what it links to, which this viewer does not.",
                    style = Type.small, color = p.textMuted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    sharing = false
                    if (file.isFile) share(context, a, file) else shareMissing = true
                }) { Text("Share") }
            },
            dismissButton = { TextButton(onClick = { sharing = false }) { Text("Cancel") } },
        )
    }
}

/**
 * Asks before a link leaves [from] (a page, a conversation) for the browser: its
 * host as the network spells it, then the whole address, in a box that scrolls.
 */
@Composable
fun WebsiteConfirmation(destination: LinkDestination, from: String, dismiss: () -> Unit, open: () -> Unit) {
    val p = palette
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Open this website?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(destination.host, style = Type.name, color = p.text)
                Box(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                    SelectionContainer { Text(destination.address, style = Type.code, color = p.textMuted) }
                }
                Text(
                    "The link leaves $from for the browser. All of the address above goes to that website.",
                    style = Type.small, color = p.textMuted,
                )
            }
        },
        confirmButton = { TextButton(onClick = open) { Text("Open") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}

/** Says that a link was not opened: one the confirmation could not show whole. */
fun linkRefused(context: Context) {
    Toast.makeText(context, "This link is too long or malformed to check, so it was not opened.", Toast.LENGTH_SHORT).show()
}

@Composable
private fun PageNotice(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = Type.body, color = palette.textMuted)
    }
}

/**
 * The only served response is the page in memory, with a CSP the document cannot
 * relax. No route in this WebView reads a file, a content provider or the network.
 */
@Composable
private fun StaticPage(document: ArtefactDocument, confirm: (LinkDestination) -> Unit) {
    val ask by rememberUpdatedState(confirm)
    var opening by remember(document) { mutableStateOf(true) }
    var failed by remember(document) { mutableStateOf(false) }
    var unavailable by remember(document) { mutableStateOf(false) }
    // Made in the factory, not in `remember`: a composition abandoned before it
    // applies never made a WebView, and one that applied destroys it on release.
    fun page(context: Context): WebView? = runCatching {
        val webView = WebView(context)
        try { webView.apply {
            settings.apply {
                javaScriptEnabled = false
                domStorageEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                blockNetworkLoads = true
                safeBrowsingEnabled = false // All content is local; no URL reputation requests.
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE
                builtInZoomControls = true
                displayZoomControls = false
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            setDownloadListener { _, _, _, _, _ -> }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url.toString()
                    if (document.isDocument(url, request.isForMainFrame, request.method)) {
                        return WebResourceResponse(
                            "text/html", "UTF-8", 200, "OK",
                            mapOf("Content-Security-Policy" to ArtefactDocument.CSP, "Cache-Control" to "no-store", "Referrer-Policy" to "no-referrer", "X-Content-Type-Options" to "nosniff"),
                            ByteArrayInputStream(document.bytes),
                        )
                    }
                    // Embedded images/fonts are the page's own bytes; the CSP limits their uses.
                    if (!request.isForMainFrame && request.url.scheme == "data") return null
                    return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(byteArrayOf()))
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val target = request.url.toString()
                    if (document.isAnchor(target)) return false
                    // Nothing leaves from here: the person sees where it goes and confirms first.
                    if (WebLinks.opensFromPage(target, document.url, request.isForMainFrame, request.hasGesture(), request.isRedirect)) {
                        WebLinks.destination(target)?.let(ask) ?: linkRefused(view.context)
                    }
                    return true
                }

                @Deprecated("Called by older WebView versions")
                override fun shouldOverrideUrlLoading(view: WebView, url: String) = !document.isAnchor(url)

                override fun onPageFinished(view: WebView, url: String) { opening = false }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) { failed = true; opening = false }
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    // Removing AndroidView below releases this renderer's view; reopening creates another.
                    failed = true
                    opening = false
                    return true
                }
            }
            loadUrl(document.url)
        } } catch (error: Exception) { webView.destroy(); throw error }
    }.getOrNull()
    Box(Modifier.fillMaxSize()) {
        if (!unavailable && !failed) key(document) {
            AndroidView(
                factory = { context -> page(context) ?: View(context).also { unavailable = true } },
                modifier = Modifier.fillMaxSize(),
                onRelease = { (it as? WebView)?.run { stopLoading(); destroy() } },
            )
        }
        if (failed || opening || unavailable) {
            Box(Modifier.fillMaxSize().background(palette.bg)) {
                PageNotice(when {
                    unavailable -> "The page viewer is unavailable on this phone. You can still share the file."
                    failed -> "This page could not be displayed. Close it and try again."
                    else -> "Opening the page…"
                })
            }
        }
    }
}
