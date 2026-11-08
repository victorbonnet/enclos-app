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
private data class AgentImageVersion(val revision: Long, val hash: String?)
private data class ImageRequest(val address: String, val write: Boolean)

/** The host owns the photo; this cache only renders confirmed, versioned assets. */
class AgentImages(
    private val app: Application,
    private val send: (String, JSONObject) -> Boolean,
    private val connected: (String) -> Boolean,
    private val agent: (String) -> Agent?,
    private val hostExists: (String) -> Boolean,
) {
    val pictures = mutableStateMapOf<String, Bitmap>()
    val busy = mutableStateMapOf<String, String>()
    val errors = mutableStateMapOf<String, String>()
    private val versions = mutableStateMapOf<String, AgentImageVersion>()
    private val pictureVersions = mutableMapOf<String, String>()
    private val requests = mutableMapOf<String, ImageRequest>()
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val epochs = ConcurrentHashMap<String, Int>()
    private val cache = app.getSharedPreferences("agent-images", Application.MODE_PRIVATE)
    private val folder = File(app.cacheDir, "agent-images")
    @Volatile private var closed = false
    private fun hostOf(address: String) = address.substringBefore('/')
    private fun epoch(address: String) = epochs[hostOf(address)] ?: 0
    private fun alive(address: String, generation: Int) = !closed && epoch(address) == generation && hostExists(hostOf(address))
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun file(address: String) = File(folder, hash(address.toByteArray()) + ".jpg")
    fun hasPhoto(address: String) = versions[address]?.hash != null

    init {
        io.execute {
            cache.all.forEach { (address, raw) ->
                val generation = epoch(address)
                runCatching {
                    val m = JSONObject(raw as String)
                    val bytes = file(address).readBytes()
                    val version = AgentImageVersion(m.getLong("revision"), m.getString("version"))
                    if (hash(bytes) != version.hash) return@runCatching
                    val bitmap = decode(bytes)
                    main.post {
                        if (alive(address, generation) && (versions[address]?.revision ?: -1) <= version.revision) {
                            versions[address] = version; pictures[address] = bitmap; pictureVersions[address] = requireNotNull(version.hash)
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
    fun listed(listed: List<Agent>) {
        listed.forEach { a ->
            val current = versions[a.address]
            if (current != null && current.revision > a.avatarRevision) return@forEach
            if (a.avatarVersion == null) removeCached(a.address, a.avatarRevision)
            else {
                versions[a.address] = AgentImageVersion(a.avatarRevision, a.avatarVersion)
                if (pictureVersions[a.address] != a.avatarVersion || pictures[a.address] == null) refresh(a.address)
            }
        }
    }
    fun refresh(address: String) {
        if (agent(address)?.supportsAvatars != true || !connected(hostOf(address)) || requests.values.any { !it.write && it.address == address }) return
        request(address, message("agent.avatar.get", "agent" to address.substringAfter('/')), false)
    }
    private fun request(address: String, command: JSONObject, write: Boolean) {
        val id = command.getString("id")
        requests[id] = ImageRequest(address, write)
        if (write) busy[address] = "Saving photo…"
        if (!fits(command) || !send(hostOf(address), command)) {
            requests.remove(id); busy.remove(address)
            if (write) errors[address] = "The photo was not sent. Reconnect and try again."
            return
        }
        main.postDelayed({
            val pending = requests.remove(id)
            if (pending?.write == true) {
                busy.remove(address); errors[address] = "The host has not confirmed the photo. Refresh before trying again."
            }
        }, 60_000)
    }
    fun received(host: String, m: JSONObject) {
        val address = "$host/${m.getString("agent")}"
        val of = m.optString("of")
        if (!hostExists(host) || (of.isNotEmpty() && requests[of]?.address != address)) return
        if (of.isNotEmpty()) {
            val pending = requests.remove(of)
            if (pending?.write == true) { busy.remove(address); errors.remove(address) }
        }
        val revision = m.getLong("revision")
        if (revision < (versions[address]?.revision ?: -1)) return
        val version = m.optString("version").takeUnless { m.isNull("version") || it.isBlank() }
        if (version == null) { removeCached(address, revision); return }
        versions[address] = AgentImageVersion(revision, version)
        val generation = epoch(address)
        val encoded = m.optString("image")
        io.execute {
            runCatching {
                val bytes = decodeBytes(encoded)
                require(hash(bytes) == version)
                val bitmap = decode(bytes)
                if (alive(address, generation)) {
                    folder.mkdirs()
                    val target = file(address); val temp = File(target.path + ".new")
                    temp.writeBytes(bytes); check(temp.renameTo(target))
                    cache.edit { putString(address, JSONObject().put("revision", revision).put("version", version).toString()) }
                    main.post { if (alive(address, generation) && versions[address]?.revision == revision) { pictures[address] = bitmap; pictureVersions[address] = version; errors.remove(address) } }
                }
            }.onFailure {
                main.post { if (alive(address, generation) && versions[address]?.revision == revision) errors[address] = "The shared photo could not be loaded. Refresh to try again." }
            }
        }
    }
    private fun removeCached(address: String, revision: Long) {
        versions[address] = AgentImageVersion(revision, null); pictures.remove(address); pictureVersions.remove(address)
        io.execute { file(address).delete(); cache.edit { remove(address) } }
    }
    fun failure(of: String, reason: String): Boolean {
        val pending = requests.remove(of) ?: return false
        if (pending.write) { busy.remove(pending.address); errors[pending.address] = reason }
        return true
    }
    fun setPhoto(address: String, uri: Uri) {
        val a = agent(address) ?: return
        if (!a.canEditAvatar || !connected(a.host) || address in busy) return
        val revision = versions[address]?.revision ?: a.avatarRevision
        val generation = epoch(address)
        busy[address] = "Preparing photo…"; errors.remove(address)
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
                    if (alive(address, generation)) request(address, message("agent.avatar.set", "agent" to a.name, "revision" to revision, "image" to encoded), true)
                }
            }.onFailure {
                main.post { if (alive(address, generation)) { busy.remove(address); errors[address] = "This image could not be opened. Choose another photo." } }
            }
        }
    }
    fun removePhoto(address: String) {
        val a = agent(address) ?: return
        if (!a.canEditAvatar || !connected(a.host) || address in busy) return
        errors.remove(address)
        request(address, message("agent.avatar.set", "agent" to a.name, "revision" to (versions[address]?.revision ?: a.avatarRevision), "image" to JSONObject.NULL), true)
    }
    fun ended(host: String) {
        requests.filterValues { hostOf(it.address) == host }.keys.toList().forEach { id ->
            val p = requests.remove(id)!!
            if (p.write) { busy.remove(p.address); errors[p.address] = "The connection ended. Refresh the agent page to check its photo." }
        }
    }
    fun forget(host: String) {
        epochs[host] = (epochs[host] ?: 0) + 1
        pictures.keys.filter { hostOf(it) == host }.forEach { pictures.remove(it); pictureVersions.remove(it) }
        versions.keys.filter { hostOf(it) == host }.forEach { versions.remove(it) }
        busy.keys.filter { hostOf(it) == host }.forEach { busy.remove(it) }
        errors.keys.filter { hostOf(it) == host }.forEach { errors.remove(it) }
        requests.entries.removeAll { hostOf(it.value.address) == host }
        io.execute { cache.all.keys.filter { hostOf(it) == host }.forEach { address -> file(address).delete(); cache.edit { remove(address) } } }
    }
    fun clear(hosts: List<String>) { hosts.forEach(::forget) }
    fun close() { closed = true; io.shutdown() }
}
