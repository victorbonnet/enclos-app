package io.github.victorbonnet.enclos

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.edit
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.github.victorbonnet.enclos.core.decodeBytes
import io.github.victorbonnet.enclos.core.openPreview

/** The app's process: it starts Firebase, when the build has its settings (docs/adr/0010). */
class EnclosApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PushSetup.start(this)
    }
}

/** Firebase, started with the settings the build was given; nothing without them. */
object PushSetup {
    /** Whether this build has Firebase's settings: without them push is off. */
    val configured: Boolean
        get() = listOf(
            BuildConfig.FIREBASE_APPLICATION_ID,
            BuildConfig.FIREBASE_API_KEY,
            BuildConfig.FIREBASE_PROJECT_ID,
            BuildConfig.FIREBASE_SENDER_ID,
        ).all { it.isNotEmpty() }

    fun start(context: Context) {
        if (!configured) return
        if (FirebaseApp.getApps(context).isEmpty()) {
            val options = FirebaseOptions.Builder()
                .setApplicationId(BuildConfig.FIREBASE_APPLICATION_ID)
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                .build()
            FirebaseApp.initializeApp(context, options)
        }
        // Asked for here, not by Firebase on its own (its auto-init is off).
        FirebaseMessaging.getInstance().token.addOnSuccessListener { PushTokens.save(context, it) }
    }
}

/**
 * This phone's FCM token, kept in the app's private preferences (no backup,
 * as the rest), and given to each host: at pairing, at each connection, and
 * at once to the hosts connected when it changes.
 */
object PushTokens {
    /** Told of a new token while the app's model lives. */
    @Volatile
    var changed: ((String) -> Unit)? = null

    private fun prefs(context: Context) = context.getSharedPreferences("push", Context.MODE_PRIVATE)

    fun current(context: Context): String? = prefs(context).getString("token", null)

    fun save(context: Context, token: String) {
        if (current(context) == token) return
        prefs(context).edit { putString("token", token) }
        changed?.invoke(token)
    }
}

/** Receives the hosts' pushes and this phone's new tokens. */
class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) = PushTokens.save(this, token)

    override fun onMessageReceived(message: RemoteMessage) = Notifier.received(this, message.data)
}

/** Shows what a push says, on its agent's channel. */
object Notifier {
    /** What a notification opens: its conversation. */
    const val OPEN_CONVERSATION = "io.github.victorbonnet.enclos.OPEN_CONVERSATION"
    const val EXTRA_HOST = "host"
    const val EXTRA_CONVERSATION = "conversation"

    private fun shownPrefs(context: Context) = context.getSharedPreferences("push", Context.MODE_PRIVATE)

    fun received(context: Context, data: Map<String, String>) {
        val push = Push.parse(data) { decodeBytes(it) } ?: return
        val preview = Push.open(push, Hosts(context).all(), Keys::unwrap) { key, host, seq, sealed ->
            openPreview(key, host, seq, sealed)
        } ?: return
        val prefs = shownPrefs(context)
        val shownKey = "shown.${preview.host}"
        val last = if (prefs.contains(shownKey)) prefs.getLong(shownKey, 0) else null
        if (Push.seen(preview, last)) return
        prefs.edit { putLong(shownKey, preview.seq) }
        show(context, preview)
    }

    private fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun show(context: Context, preview: PushPreview) {
        if (!allowed(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = Push.channel(preview.host, preview.agent)
        manager.createNotificationChannel(
            NotificationChannel(channel, "${preview.agent} on ${preview.host}", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Messages from ${preview.agent}, on ${preview.host}"
            },
        )
        // With the app lock on, what a message says stays behind it.
        val locked = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("lock", false)
        val text = Push.shown(preview.text, locked)
        val id = Push.notificationId(preview.host, preview.conversation)
        val open = Intent(context, MainActivity::class.java)
            .setAction(OPEN_CONVERSATION)
            .putExtra(EXTRA_HOST, preview.host)
            .putExtra(EXTRA_CONVERSATION, preview.conversation)
        val tap = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // On the lock screen, only that a message came.
        val public = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Enclos")
            .setContentText(Push.GENERIC)
            .build()
        val notification = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(preview.agent)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        manager.notify(id, notification)
    }

    /** Takes away the notification of a conversation the person opened. */
    fun cancel(context: Context, host: String, conversation: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel(Push.notificationId(host, conversation))
    }
}
