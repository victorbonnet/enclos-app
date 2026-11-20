package io.github.victorbonnet.enclos

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.victorbonnet.enclos.core.DeviceSession
import io.github.victorbonnet.enclos.core.NoHandle
import kotlinx.coroutines.Job
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A bridge connection for tests: an open socket whose frames are recorded
 * in clear (an identity cipher), with no network or pairing. [beforeSend]
 * runs on the sending thread before each frame is recorded, and may block.
 */
internal class FakeBridge(private val beforeSend: (JSONObject) -> Unit = {}) {
    val frames: MutableList<JSONObject> = Collections.synchronizedList(mutableListOf())
    val closes = AtomicInteger()
    val connection = BridgeConnection("https://example.invalid", ByteArray(32), { ByteArray(32) }, { error("unused") }, {}, {}, {})

    init {
        val socket = object : WebSocket {
            override fun request() = Request.Builder().url("https://example.invalid").build()
            override fun queueSize() = 0L
            override fun send(text: String) = false
            override fun send(bytes: ByteString): Boolean {
                val frame = JSONObject(bytes.utf8())
                beforeSend(frame)
                frames.add(frame)
                return true
            }
            override fun close(code: Int, reason: String?): Boolean { closes.incrementAndGet(); return true }
            override fun cancel() {}
        }
        val session = object : DeviceSession(NoHandle) {
            override fun encrypt(plaintext: ByteArray) = plaintext
        }
        BridgeConnection::class.java.getDeclaredField("socket").apply { isAccessible = true }.set(connection, socket)
        BridgeConnection::class.java.getDeclaredField("session").apply { isAccessible = true }.set(connection, session)
    }

    fun types(): List<String> = synchronized(frames) { frames.map { it.getString("type") } }
    fun ofType(type: String): List<JSONObject> = synchronized(frames) { frames.filter { it.getString("type") == type } }

    /** Connects [model] to this bridge as [host], connected, its agents taking files when [files]. */
    fun attach(model: AppModel, host: String, files: Boolean = true) {
        model.links[host] = HostLink.Connected("fixture-person")
        if (files) model.field<MutableMap<String, Set<String>>>("hostCapabilities")[host] = setOf("message.begin")
        model.field<MutableMap<String, BridgeConnection>>("connections")[host] = connection
    }
}

@Suppress("UNCHECKED_CAST")
internal fun <T> AppModel.field(name: String): T = AppModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(this) as T

/** A model whose own start (its cache read) is over. */
internal fun fixtureModel(): AppModel {
    val model = AppModel(ApplicationProvider.getApplicationContext<Application>())
    model.field<ExecutorService>("saving").submit {}.get(5, TimeUnit.SECONDS)
    shadowOf(Looper.getMainLooper()).idle()
    return model
}

/** What the ViewModel's own end does, then its executors drained and its store closed. */
internal fun AppModel.endFixture() {
    AppModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(this)
    try {
        for (name in listOf("saving", "work", "disk")) {
            assertTrue("owned executor teardown: $name", field<ExecutorService>(name).awaitTermination(5, TimeUnit.SECONDS))
        }
    } finally { field<Store>("store").close() }
}

internal fun fixtureHost(name: String) = Host(name, "https://example.invalid", ByteArray(32), "device", byteArrayOf(), "fixture", byteArrayOf())

/** [m] as the bridge of [host] sends it. */
internal fun AppModel.receive(host: String, m: JSONObject) {
    AppModel::class.java.getDeclaredMethod("received", Host::class.java, JSONObject::class.java)
        .apply { isAccessible = true }.invoke(this, fixtureHost(host), m)
}

/** Runs the main looper until [done], while uploads go on their own thread. */
internal fun settle(timeoutMs: Long = 5_000, done: () -> Boolean) {
    val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    while (true) {
        shadowOf(Looper.getMainLooper()).idle()
        if (done()) return
        check(System.nanoTime() < until) { "condition not reached in $timeoutMs ms" }
        Thread.sleep(5)
    }
}

/** [files] join [key]'s draft as a preparation hands them over, their bytes reserved in [budget]. */
internal fun SendingFiles.drafted(key: FileKey, vararg files: SendFile, budget: PreparedBudget = PreparedBudget.process) {
    val job = Job()
    assertTrue("a preparation may start", preparing(key, job))
    files.forEach { assertTrue(budget.reserve(it.bytes.size)) }
    assertTrue("the draft takes them", prepared(key, job, files.toList(), emptyList()))
}
