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
import android.net.Uri
import android.os.Build
import android.util.Log
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
        // Only in the app's own process: not in the isolated one that
        // renders PDFs (ADR 0008), which Android also gives this class.
        if (getProcessName() == packageName) PushSetup.start(this)
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
        askToken(context)
    }

    /**
     * Asks Firebase for this phone's token (its auto-init is off). A failure
     * (no Play services, no network yet) is logged, and asked again when a
     * host next connects.
     */
    fun askToken(context: Context) {
        if (!configured || FirebaseApp.getApps(context).isEmpty()) return
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { PushTokens.save(context, it) }
            .addOnFailureListener { Log.w("enclos", "no FCM token: ${it.javaClass.simpleName}") }
    }
}

/**
 * This phone's FCM token, kept in the app's private preferences (no backup,
 * as the rest) with the project it is for, and given to each host: at
 * pairing, at each connection, and at once to the hosts connected when it
 * changes.
 */
object PushTokens {
    /** Told of a new token while the app's model lives. */
    @Volatile
    var changed: ((String) -> Unit)? = null

    private fun prefs(context: Context) = context.getSharedPreferences("push", Context.MODE_PRIVATE)

    /**
     * The token to give hosts: none when this build has push off, or when the
     * token kept is another project's (a build installed over one made with
     * other settings), which every push would fail for.
     */
    fun current(context: Context): String? {
        if (!PushSetup.configured) return null
        val prefs = prefs(context)
        if (prefs.getString("sender", null) != BuildConfig.FIREBASE_SENDER_ID) return null
        return prefs.getString("token", null)
    }

    fun save(context: Context, token: String) {
        if (current(context) == token) return
        prefs(context).edit {
            putString("token", token)
            putString("sender", BuildConfig.FIREBASE_SENDER_ID)
        }
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

    private fun prefs(context: Context) = context.getSharedPreferences("push", Context.MODE_PRIVATE)

    fun received(context: Context, data: Map<String, String>) {
        val prefs = prefs(context)
        val decision = Push.decide(
            data = data,
            hosts = Hosts(context).all(),
            decode = { decodeBytes(it) },
            unwrap = Keys::unwrap,
            open = { key, host, seq, sealed -> openPreview(key, host, seq, sealed) },
            lastShown = { host, conversation ->
                Push.shownKey(host, conversation).takeIf(prefs::contains)?.let { prefs.getLong(it, 0) }
            },
            unwrapFailed = { _, e -> Log.w("enclos", "a host's push key could not be unwrapped: ${e.javaClass.simpleName}") },
        )
        when (decision) {
            is PushDecision.Drop -> decision.why?.let { Log.w("enclos", "push dropped: $it") }
            // Kept as shown only once it is: a push the phone did not show
            // (notifications refused) is no reason to drop the next.
            is PushDecision.Show -> if (show(context, decision.preview)) {
                prefs.edit { putLong(Push.shownKey(decision.preview.host, decision.preview.conversation), decision.preview.seq) }
            }
        }
    }

    /** Whether the app may show notifications: on Android 13 and later, the person said so. */
    fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Shows [preview]; whether it was shown. */
    private fun show(context: Context, preview: PushPreview): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!allowed(context) || !manager.areNotificationsEnabled()) {
            Log.w("enclos", "push not shown: notifications are off for the app")
            return false
        }
        val channel = Push.channel(preview.host, preview.agent)
        manager.createNotificationChannel(
            NotificationChannel(channel, "${preview.agent} on ${preview.host}", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Messages from ${preview.agent}, on ${preview.host}"
            },
        )
        // With the app lock on, what a message says stays behind it.
        val locked = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("lock", false)
        val text = Push.shown(preview.text, locked)
        val id = Push.notificationId(preview.conversation)
        // The data makes each conversation's intent its own, whatever its id's hash.
        val open = Intent(context, MainActivity::class.java)
            .setAction(OPEN_CONVERSATION)
            .setData(Uri.Builder().scheme("enclos-notification").authority(preview.host).appendPath(preview.conversation).build())
            .putExtra(EXTRA_HOST, preview.host)
            .putExtra(EXTRA_CONVERSATION, preview.conversation)
        val tap = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // On the lock screen, when the person's system setting hides
        // sensitive content: only that a message came.
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
        manager.notify(preview.host, id, notification)
        return true
    }

    /** Takes away the notification of a conversation the person opened. */
    fun cancel(context: Context, host: String, conversation: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel(host, Push.notificationId(conversation))
    }

    /** Takes away every notification of the app: the app lock was turned on. */
    fun cancelAll(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancelAll()
    }

    /**
     * Forgets what the phone showed of [host]: forgotten, or paired again
     * (its bridge may count again from the start). Its marks, its
     * notifications and its agents' channels go.
     */
    fun forget(context: Context, host: String) {
        val prefs = prefs(context)
        prefs.edit { prefs.all.keys.filter { it.startsWith(Push.shownPrefix(host)) }.forEach { remove(it) } }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.activeNotifications.filter { it.tag == host }.forEach { manager.cancel(host, it.id) }
        manager.notificationChannels.filter { it.id.startsWith(Push.channelPrefix(host)) }.forEach {
            manager.deleteNotificationChannel(it.id)
        }
    }
}
