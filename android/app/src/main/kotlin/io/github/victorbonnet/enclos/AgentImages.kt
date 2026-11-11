package io.github.victorbonnet.enclos

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateMapOf
import androidx.core.content.edit
import io.github.victorbonnet.enclos.core.decodeBytes
import io.github.victorbonnet.enclos.core.encodeBytes
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private const val MAX_AGENT_IMAGE = 32 * 1024

/** At most this many photos are kept for one host, whatever it lists. */
const val MAX_PHOTOS_PER_HOST = 64

/**
 * An agent's photo, by its host and its `sandbox/agent` kept apart. No string
 * joins the two, so a host named `a/b` cannot reach host `a`'s agent `b/c`:
 * pairing links may put `/` in a host's name.
 */
data class PhotoKey(val host: String, val agent: String) {
    /** Its key on the phone: length-prefixed, so it reads back as this pair only. */
    val stored: String get() = "photo1|${host.length}|$host$agent"

    companion object {
        /** The pair [stored] was made from, or null for anything else (an older cache's key). */
        fun parse(stored: String): PhotoKey? {
            if (!stored.startsWith("photo1|")) return null
            val rest = stored.removePrefix("photo1|")
            val bar = rest.indexOf('|')
            val length = rest.substring(0, bar.coerceAtLeast(0)).toIntOrNull() ?: return null
            val body = rest.substring(bar + 1)
            if (length < 0 || length > body.length) return null
            return PhotoKey(body.substring(0, length), body.substring(length))
        }
    }
}

/** Which photo the host says an agent has: [hash] null for none. */
data class PhotoVersion(val revision: Long, val hash: String?)

/**
 * Which photo each agent shows, as its host last said. A host's agent list is
 * its word on every agent it lists, a lower revision too (its database may have
 * been made again); a photo for an agent it does not list is never taken, and
 * one host keeps at most [maxPerHost] photos.
 */
class PhotoVersions(
    private val versions: MutableMap<PhotoKey, PhotoVersion> = HashMap(),
    private val maxPerHost: Int = MAX_PHOTOS_PER_HOST,
) {
    /** What an agent list says: the agents with a photo to show, and those that no longer have one. */
    data class Changes(val fetch: List<PhotoKey>, val removed: List<PhotoKey>)

    operator fun get(key: PhotoKey): PhotoVersion? = versions[key]

    private fun photos(host: String) = versions.count { (k, v) -> k.host == host && v.hash != null }

    /** `agents.list` from [host]: each listed agent's revision and photo hash, in its order. */
    fun listed(host: String, agents: List<Pair<String, PhotoVersion>>): Changes {
        val fetch = mutableListOf<PhotoKey>()
        val removed = mutableListOf<PhotoKey>()
        val names = agents.map { it.first }.toSet()
        versions.keys.filter { it.host == host && it.agent !in names }.forEach { versions.remove(it); removed += it }
        var kept = 0
        for ((agent, version) in agents) {
            val key = PhotoKey(host, agent)
            val before = versions[key]
            val shown = if (version.hash != null && kept >= maxPerHost) version.copy(hash = null) else version
            if (shown.hash != null) kept++
            versions[key] = shown
            if (shown.hash == null) { if (before?.hash != null) removed += key } else fetch += key
        }
        return Changes(fetch, removed)
    }

    /**
     * `agent.avatar` for [key]: whether to take it. Refused for an agent its host
     * does not list, an older revision, or a new photo past the host's limit.
     */
    fun accept(key: PhotoKey, version: PhotoVersion, listed: Boolean): Boolean {
        if (!listed) return false
        val current = versions[key]
        if (current != null && version.revision < current.revision) return false
        if (version.hash != null && current?.hash == null && photos(key.host) >= maxPerHost) return false
        versions[key] = version
        return true
    }

    /** A photo kept on the phone, shown until its host says otherwise. */
    fun cached(key: PhotoKey, version: PhotoVersion): Boolean {
        if (key in versions || photos(key.host) >= maxPerHost) return false
        versions[key] = version
        return true
    }

    fun forget(host: String) { versions.keys.filter { it.host == host }.forEach { versions.remove(it) } }
}

private data class ImageRequest(val key: PhotoKey, val write: Boolean)

/** The host owns the photo; this cache only renders confirmed, versioned assets. */
class AgentImages(
    private val app: Application,
    private val send: (String, JSONObject) -> Boolean,
    private val connected: (String) -> Boolean,
    private val agent: (PhotoKey) -> Agent?,
    private val hostExists: (String) -> Boolean,
) {
    val pictures = mutableStateMapOf<PhotoKey, Bitmap>()
    val busy = mutableStateMapOf<PhotoKey, String>()
    val errors = mutableStateMapOf<PhotoKey, String>()
    private val versions = PhotoVersions(mutableStateMapOf())
    private val pictureVersions = mutableMapOf<PhotoKey, String>()
    private val requests = mutableMapOf<String, ImageRequest>()
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val epochs = ConcurrentHashMap<String, Int>()
    private val cache = app.getSharedPreferences("agent-images", Application.MODE_PRIVATE)
    private val folder = File(app.cacheDir, "agent-images")
    @Volatile private var closed = false
    private fun epoch(key: PhotoKey) = epochs[key.host] ?: 0
    private fun alive(key: PhotoKey, generation: Int) = !closed && epoch(key) == generation && hostExists(key.host)
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun file(key: PhotoKey) = File(folder, hash(key.stored.toByteArray()) + ".jpg")
    fun hasPhoto(key: PhotoKey) = versions[key]?.hash != null

    init {
        io.execute {
            cache.all.forEach { (stored, raw) ->
                val key = PhotoKey.parse(stored)
                if (key == null) {
                    // An older cache's key, which could not tell hosts apart: dropped.
                    File(folder, hash(stored.toByteArray()) + ".jpg").delete()
                    cache.edit { remove(stored) }
                    return@forEach
                }
                val generation = epoch(key)
                runCatching {
                    val m = JSONObject(raw as String)
                    val bytes = file(key).readBytes()
                    val version = PhotoVersion(m.getLong("revision"), m.getString("version"))
                    if (hash(bytes) != version.hash) return@runCatching
                    val bitmap = decode(bytes)
                    main.post {
                        // Only until the host has said which photo it has.
                        if (alive(key, generation) && versions.cached(key, version)) {
                            pictures[key] = bitmap; pictureVersions[key] = requireNotNull(version.hash)
                        }
                    }
                }
            }
        }
    }
    private fun decode(bytes: ByteArray): Bitmap {
        require(bytes.size <= MAX_AGENT_IMAGE)
        val info = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, info)
        require(info.outWidth in 1..256 && info.outHeight in 1..256)
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }
    /** [host]'s agent list: its word on each listed agent's photo; the others are dropped. */
    fun listed(host: String, listed: List<Agent>) {
        val changes = versions.listed(host, listed.map { it.name to PhotoVersion(it.avatarRevision, it.avatarVersion) })
        changes.removed.forEach(::dropPicture)
        changes.fetch.forEach { key ->
            if (pictureVersions[key] != versions[key]?.hash || pictures[key] == null) {
                // Not the host's photo any more: the initial until the right one comes.
                if (pictureVersions[key] != versions[key]?.hash) { pictures.remove(key); pictureVersions.remove(key) }
                refresh(key)
            }
        }
    }
    fun refresh(key: PhotoKey) {
        if (agent(key)?.supportsAvatars != true || !connected(key.host) || requests.values.any { !it.write && it.key == key }) return
        request(key, message("agent.avatar.get", "agent" to key.agent), false)
    }
    private fun request(key: PhotoKey, command: JSONObject, write: Boolean) {
        val id = command.getString("id")
        requests[id] = ImageRequest(key, write)
        if (write) busy[key] = "Saving photo…"
        if (!fits(command) || !send(key.host, command)) {
            requests.remove(id); busy.remove(key)
            if (write) errors[key] = "The photo was not sent. Reconnect and try again."
            return
        }
        main.postDelayed({
            val pending = requests.remove(id)
            if (pending?.write == true) {
                busy.remove(key); errors[key] = "The host has not confirmed the photo. Refresh before trying again."
            }
        }, 60_000)
    }
    fun received(host: String, m: JSONObject) {
        val key = PhotoKey(host, m.getString("agent"))
        val of = m.optString("of")
        if (!hostExists(host) || (of.isNotEmpty() && requests[of]?.key != key)) return
        if (of.isNotEmpty()) {
            val pending = requests.remove(of)
            if (pending?.write == true) { busy.remove(key); errors.remove(key) }
        }
        val revision = m.getLong("revision")
        val version = m.optString("version").takeUnless { m.isNull("version") || it.isBlank() }
        // Only for an agent this host lists; its revision and the host's limit decide the rest.
        if (!versions.accept(key, PhotoVersion(revision, version), listed = agent(key) != null)) return
        if (version == null) { dropPicture(key); return }
        val generation = epoch(key)
        val encoded = m.optString("image")
        io.execute {
            runCatching {
                val bytes = decodeBytes(encoded)
                require(hash(bytes) == version)
                val bitmap = decode(bytes)
                if (alive(key, generation)) {
                    folder.mkdirs()
                    val target = file(key); val temp = File(target.path + ".new")
                    temp.writeBytes(bytes); check(temp.renameTo(target))
                    cache.edit { putString(key.stored, JSONObject().put("revision", revision).put("version", version).toString()) }
                    main.post { if (alive(key, generation) && versions[key]?.revision == revision) { pictures[key] = bitmap; pictureVersions[key] = version; errors.remove(key) } }
                }
            }.onFailure {
                main.post { if (alive(key, generation) && versions[key]?.revision == revision) errors[key] = "The shared photo could not be loaded. Refresh to try again." }
            }
        }
    }
    private fun dropPicture(key: PhotoKey) {
        pictures.remove(key); pictureVersions.remove(key)
        io.execute { file(key).delete(); cache.edit { remove(key.stored) } }
    }
    fun failure(of: String, reason: String): Boolean {
        val pending = requests.remove(of) ?: return false
        if (pending.write) { busy.remove(pending.key); errors[pending.key] = reason }
        return true
    }
    fun setPhoto(key: PhotoKey, uri: Uri) {
        val a = agent(key) ?: return
        if (!a.canEditAvatar || !connected(a.host) || key in busy) return
        val revision = versions[key]?.revision ?: a.avatarRevision
        val generation = epoch(key)
        busy[key] = "Preparing photo…"; errors.remove(key)
        io.execute {
            runCatching {
                val source = ImageDecoder.createSource(app.contentResolver, uri)
                val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val scale = 512f / maxOf(info.size.width, info.size.height).coerceAtLeast(1)
                    decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                val side = minOf(bitmap.width, bitmap.height)
                val square = Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
                val photo = Bitmap.createScaledBitmap(square, 256, 256, true)
                var bytes: ByteArray? = null
                for (quality in listOf(85, 75, 60, 40)) {
                    val stream = ByteArrayOutputStream(); check(photo.compress(Bitmap.CompressFormat.JPEG, quality, stream))
                    if (stream.size() <= MAX_AGENT_IMAGE) { bytes = stream.toByteArray(); break }
                }
                val encoded = encodeBytes(requireNotNull(bytes))
                main.post {
                    if (alive(key, generation)) request(key, message("agent.avatar.set", "agent" to a.name, "revision" to revision, "image" to encoded), true)
                }
            }.onFailure {
                main.post { if (alive(key, generation)) { busy.remove(key); errors[key] = "This image could not be opened. Choose another photo." } }
            }
        }
    }
    fun removePhoto(key: PhotoKey) {
        val a = agent(key) ?: return
        if (!a.canEditAvatar || !connected(a.host) || key in busy) return
        errors.remove(key)
        request(key, message("agent.avatar.set", "agent" to a.name, "revision" to (versions[key]?.revision ?: a.avatarRevision), "image" to JSONObject.NULL), true)
    }
    fun ended(host: String) {
        requests.filterValues { it.key.host == host }.keys.toList().forEach { id ->
            val p = requests.remove(id)!!
            if (p.write) { busy.remove(p.key); errors[p.key] = "The connection ended. Refresh the agent page to check its photo." }
        }
    }
    fun forget(host: String) {
        epochs[host] = (epochs[host] ?: 0) + 1
        pictures.keys.filter { it.host == host }.forEach { pictures.remove(it); pictureVersions.remove(it) }
        versions.forget(host)
        busy.keys.filter { it.host == host }.forEach { busy.remove(it) }
        errors.keys.filter { it.host == host }.forEach { errors.remove(it) }
        requests.entries.removeAll { it.value.key.host == host }
        io.execute {
            cache.all.keys.mapNotNull { stored -> PhotoKey.parse(stored)?.takeIf { it.host == host } }
                .forEach { key -> file(key).delete(); cache.edit { remove(key.stored) } }
        }
    }
    fun clear(hosts: List<String>) { hosts.forEach(::forget) }
    fun close() { closed = true; io.shutdown() }
}
