package io.github.victorbonnet.enclos

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsService
import androidx.core.net.toUri

/** The browser owns web pages; private HTML attachments stay in the offline viewer. */
fun openWebLink(context: Context, url: String) {
    if (!WebLinks.accepts(url)) return
    val uri = url.toUri()
    try {
        val candidates = context.packageManager.queryIntentServices(
            Intent(CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION), 0,
        ).map { it.serviceInfo.packageName }.distinct()
        val provider = CustomTabsClient.getPackageName(context, candidates)
        if (provider == null) {
            openWithHandler(context, uri, "No browser is available to open this link.", browser = true)
            return
        }
        val tab = CustomTabsIntent.Builder().setShowTitle(true)
            .setSendToExternalDefaultHandlerEnabled(false)
            .build()
        tab.intent.setPackage(provider)
        tab.launchUrl(context, uri)
    } catch (_: ActivityNotFoundException) {
        openWithHandler(context, uri, "No browser is available to open this link.", browser = true)
    } catch (_: SecurityException) {
        Toast.makeText(context, "This link could not be opened.", Toast.LENGTH_SHORT).show()
    }
}

/** Mail keeps the usual native handler, independent of the browser. */
fun openMailLink(context: Context, url: String) {
    if (!url.startsWith("mailto:", ignoreCase = true)) return
    openWithHandler(context, url.toUri(), "No mail app is available to open this link.", browser = false)
}

/** A web link ([browser]) goes only to an activity that may be opened from the web, as a browser does. */
private fun openWithHandler(context: Context, uri: Uri, unavailable: String, browser: Boolean) {
    try {
        val view = Intent(Intent.ACTION_VIEW, uri)
        if (browser) view.addCategory(Intent.CATEGORY_BROWSABLE)
        context.startActivity(view)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, unavailable, Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        Toast.makeText(context, "This link could not be opened.", Toast.LENGTH_SHORT).show()
    }
}
