// OkHttp's platform hook, swapped for LoopbackCleartext and back, is internal to OkHttp: used by tests only.
@file:OptIn(okhttp3.internal.OkHttpInternalApi::class)

package io.github.victorbonnet.enclos

import android.content.Context
import android.util.Log
import io.github.victorbonnet.enclos.core.generateNoiseKeypair
import okhttp3.internal.platform.Platform
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom

/** The Keystore alias Keys wraps the Noise keys under (Keys.kt, WRAP_ALIAS). */
internal const val NOISE_WRAP_ALIAS = "enclos-noise-wrap"

/**
 * Every store the app persists that a connected run may write, by raw path,
 * read without opening any of them (no SharedPreferences or SQLite instance
 * is made, so nothing is migrated or written by a check). [prefix] names an
 * isolated copy beside the app's own (occupied-target controls); "" is the
 * app's own. Current stores (AppModel, Hosts, AgentImages, PushTokens,
 * MainActivity): the preferences [PREFERENCES], the conversation database
 * and SQLite's side files, and the cache folders [CACHES].
 */
internal class AppStores(val context: Context, val prefix: String = "") {
    val preferences = PREFERENCES.map { prefix + it }
    val database = prefix + "conversations.db"
    val caches = CACHES.map { prefix + it }

    fun preferenceFiles(name: String): List<File> =
        File(context.dataDir, "shared_prefs/$name.xml").let { listOf(it, File(it.path + ".bak")) }

    fun databaseFiles(): List<File> = context.getDatabasePath(database).let { db ->
        listOf(db) + listOf("-journal", "-wal", "-shm", "-wipecheck").map { File(db.path + it) }
    }

    fun cache(name: String) = File(context.cacheDir, name)

    /** Every path of the stores, files and folders. */
    fun paths(): List<File> = preferences.flatMap(::preferenceFiles) + databaseFiles() + caches.map(::cache)

    /** Each store path present, recursively, by path, as SHA-256 of its bytes (a folder by its own name): what a refusal must leave as it was. */
    fun bytes(): Map<String, String> = paths().filter(File::exists).flatMap { root -> root.walkTopDown().toList() }
        .associate { f -> f.path to if (f.isDirectory) "dir" else digest(f.readBytes()) }

    /**
     * Why the app's data is not an empty target, read only: any store present,
     * a device signing key (AppModel's sweep deletes those no host names), a
     * process hook a run sets still set (OkHttp's platform hook swapped, an
     * AppModel's push-token listener), or push configured in this build
     * (Firebase would be asked for a token, written to `push`, with a
     * permission dialog over the screens). Empty when the target is empty.
     */
    fun occupancy(): List<String> {
        val reasons = mutableListOf<String>()
        paths().filter(File::exists).forEach { reasons += "store present: ${it.path.removePrefix(context.dataDir.path)}" }
        deviceAliases().forEach { reasons += "device key $it" }
        if (Platform.get() is LoopbackCleartext) reasons += "OkHttp's platform hook is a test's: an earlier run did not give it back"
        if (PushTokens.changed != null) reasons += "an AppModel's push-token listener is set: a model is live in this process"
        if (PushSetup.configured) reasons += "push is configured in this build: a run would ask Firebase for a token and write the push preferences"
        return reasons
    }

    companion object {
        val PREFERENCES = listOf(
            "hosts", "reads", "tasks", "sandbox-policies", "policy-requests", "task-board-links", "agent-images", "settings", "push",
        )
        val CACHES = listOf("agent-images", "attachments")

        fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        fun deviceAliases(): List<String> = keyStore().aliases().toList().filter { it.startsWith(DEVICE_KEY_PREFIX) }.sorted()

        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/**
 * What a connected run takes over in the app under test, and gives back:
 * the app's stores ([AppStores]), device keys in the Keystore (and the Noise
 * wrapping key, when the run made it), OkHttp's process-wide platform hook,
 * the fake bridges and the model of the real Activity.
 *
 * Nothing is taken before [acquire] found the target empty, read only: a
 * refusal owns nothing, writes nothing, and [release] then removes nothing.
 * Every store is owned from [acquire] on, as it was absent: whatever is there
 * at the end the run made. Each resource is recorded as the fixture's before
 * it is made, so a setup that fails half way still gives back what it made,
 * and only that. [release] attempts every cleanup even when one fails,
 * restores the platform hook last, in a `finally`, and throws what failed,
 * the others suppressed in it. What it could not give back stays held, for a
 * later [release]; a resource given back is not touched again.
 *
 * Dependency fences: while the model step is held (its Activity or AppModel
 * not ended, writers not drained), hosts, keys and stores stay held,
 * untouched. A bridge not closed keeps its host and key held, and any
 * retained bridge keeps the stores held. A host whose removal was not
 * written keeps its device key held. The stores go last, each on a checked
 * result: preferences once a commit barrier drained their queued writes and
 * `deleteSharedPreferences` answered with the files gone, the database once
 * it and its side files are gone, a cache folder once it is gone. There is
 * no sweep: only what the fixture owns is touched.
 */
internal class ReplacementFixture(
    private val stores: AppStores,
    /** Whether an AppModel may be made: it uses the application's own data, never an isolated copy's. */
    private val modelAllowed: Boolean,
) {
    /** Called before each step of the setup and each cleanup: only the tests' own controls set them, to fail one step on purpose. */
    var beforeStep: (String) -> Unit = {}
    var beforeCleanup: (String) -> Unit = {}

    /** Whether the fixture holds anything of the app's: from past the occupancy check to a complete [release]. */
    var owned = false
        private set

    /** The bridges made and not closed yet. */
    val bridges = mutableListOf<FakeBridge>()
    /** The cleanups done, in order: for logs and checks. */
    val released = mutableListOf<String>()

    private var originalPlatform: Platform? = null
    private var model: (() -> Unit)? = null
    private val hostNames = mutableListOf<String>()
    private val keyAliases = mutableListOf<String>()
    private var wrapKey = false
    private val data = mutableListOf<String>()

    /** What the fixture still holds, by the labels [release] gives them. */
    val held: List<String>
        get() = listOfNotNull(model?.let { "model" }) + bridges.map { "bridge ${it.name}" } + hostNames.map { "host $it" } +
            keyAliases.map { "key $it" } + listOfNotNull(if (wrapKey) "key $NOISE_WRAP_ALIAS" else null) + data +
            listOfNotNull(originalPlatform?.let { "platform" })

    /**
     * Refuses a target that is not empty ([AppStores.occupancy]), before
     * anything is taken or written; otherwise owns every store (each absent),
     * the Noise wrapping key when absent, and swaps OkHttp's platform hook
     * for [LoopbackCleartext].
     */
    fun acquire() {
        check(!owned && held.isEmpty()) { "the fixture is already acquired: $held" }
        val reasons = stores.occupancy()
        if (reasons.isNotEmpty()) {
            throw AssertionError("REFUSED (nothing taken, nothing changed): this runs on an empty disposable emulator only: $reasons")
        }
        beforeStep("own")
        owned = true
        // Each one recorded before anything touches it: absent now, whatever is there later the run made.
        data += stores.preferences.map { "preferences $it" } + "database ${stores.database}" + stores.caches.map { "cache $it" }
        beforeStep("wrap key")
        // The Noise wrapping key is the app's when it exists already: never deleted then.
        wrapKey = !AppStores.keyStore().containsAlias(NOISE_WRAP_ALIAS)
        beforeStep("platform")
        val original = Platform.get()
        originalPlatform = original
        Platform.resetForTests(LoopbackCleartext(original))
        log("acquired: $held")
    }

    /** Pairs [name] with a new [FakeBridge] scripted by [script], as a pairing would have kept it: synthetic keys, the device's in the Keystore. */
    fun pair(name: String, script: BridgeScript): FakeBridge {
        check(owned) { "pair before acquire" }
        val alias = aliasOf(name)
        setup(!AppStores.keyStore().containsAlias(alias)) { "a key under the fixture's alias $alias exists already: not the fixture's, kept" }
        beforeStep("key $name")
        keyAliases += alias
        Keys.newDeviceKey(alias)
        val device = generateNoiseKeypair()
        val bridgeKeys = generateNoiseKeypair()
        beforeStep("bridge $name")
        val bridge = FakeBridge(name, bridgeKeys.privateKey, device.publicKey, "d-$name", alias, script)
        bridges += bridge
        beforeStep("host $name")
        val host = Host(
            name = name,
            url = bridge.url,
            bridgeKey = bridgeKeys.publicKey,
            deviceId = "d-$name",
            noiseKeyWrapped = Keys.wrap(device.privateKey),
            keyAlias = alias,
            pushKeyWrapped = Keys.wrap(ByteArray(32).also(SecureRandom()::nextBytes)),
        )
        val stored = storedHosts()
        setup(stored.none { it.name == name }) { "a host named $name is paired already: not the fixture's, kept" }
        // Recorded before it is written: a write that fails may still have changed the preferences in memory.
        hostNames += name
        storeHosts(stored + host, "pairing $name")
        return bridge
    }

    /** Before the AppModel is made: only once the hosts and keys are the fixture's, and only on the app's own data. */
    fun beforeModel() {
        check(owned) { "a model before acquire" }
        beforeStep("model")
        setup(modelAllowed && stores.prefix.isEmpty()) { "this fixture's data is isolated: an AppModel would use the app's own data" }
    }

    /** Records how to end the AppModel made (Activity destroyed, model cleared, writers drained): registered before the Activity exists. */
    fun holdModel(end: () -> Unit) {
        check(owned && model == null) { "a model not the fixture's" }
        model = end
    }

    /**
     * Gives back all the fixture holds, each cleanup attempted on its own,
     * the platform hook restored last whatever failed; then throws the first
     * failure, the others suppressed in it. Called again after a complete
     * release, it does nothing.
     */
    fun release() {
        val failures = mutableListOf<Throwable>()
        fun attempt(label: String, action: () -> Unit): Boolean = try {
            beforeCleanup(label)
            action()
            released += label
            log("released $label")
            true
        } catch (t: Throwable) {
            log("not released $label: $t")
            failures += t
            false
        }
        try {
            if (owned) {
                // Out of the foreground first: no retry of an ended connection outlives the test.
                model?.let { end -> if (attempt("model", end)) model = null }
                bridges.toList().forEach { b -> if (attempt("bridge ${b.name}") { b.close() }) bridges -= b }
                if (model != null) {
                    // The model not ended may still read or write them: kept, held, for a later release.
                    log("kept hosts, keys and stores until the model ends: ${hostNames + keyAliases + data}")
                } else {
                    releaseStorage(::attempt)
                }
            }
        } finally {
            originalPlatform?.let { original ->
                if (attempt("platform") { Platform.resetForTests(original) }) originalPlatform = null
            }
            if (held.isEmpty()) owned = false
        }
        if (failures.isNotEmpty()) {
            val first = failures.first()
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }

    /** Hosts and keys independent of retained bridges, then the stores once nothing that may use them is held. */
    private fun releaseStorage(attempt: (String, () -> Unit) -> Boolean) {
        // A retained bridge keeps its host (and so its key): it may still authenticate a connection.
        val bridgeHosts = bridges.map { it.name }.toSet()
        hostNames.toList().forEach { name ->
            if (name in bridgeHosts) return@forEach
            if (attempt("host $name") { storeHosts(storedHosts().filter { it.name != name }, "removing $name") }) hostNames -= name
        }
        keyAliases.toList().forEach { alias ->
            // A host whose removal was not written may still be on disk, naming this key: the key stays, held, until it is.
            val namedBy = (hostNames + bridgeHosts).filter { aliasOf(it) == alias }
            if (namedBy.isNotEmpty()) {
                log("kept key $alias: ${namedBy.joinToString()} still paired or its bridge held")
                return@forEach
            }
            // Absent when its making failed before the Keystore had it.
            if (attempt("key $alias") { AppStores.keyStore().run { if (containsAlias(alias)) deleteEntry(alias) } }) keyAliases -= alias
        }
        if (bridges.isNotEmpty() || hostNames.isNotEmpty() || keyAliases.isNotEmpty()) {
            log("kept the wrapping key and stores: ${bridges.map { it.name } + hostNames + keyAliases} still held")
            return
        }
        // Only once no pairing written by the fixture can still name a key it wraps.
        if (wrapKey && attempt("key $NOISE_WRAP_ALIAS") {
                check(storedHosts().isEmpty()) { "hosts still paired: the wrapping key is kept" }
                AppStores.keyStore().run { if (containsAlias(NOISE_WRAP_ALIAS)) deleteEntry(NOISE_WRAP_ALIAS) }
            }
        ) wrapKey = false
        data.toList().forEach { what -> if (attempt(what) { remove(what) }) data -= what }
    }

    private fun remove(what: String) {
        when {
            what.startsWith("preferences ") -> removePreferences(what.removePrefix("preferences "))
            what == "database ${stores.database}" -> deleteDatabase()
            what.startsWith("cache ") -> {
                val folder = stores.cache(what.removePrefix("cache "))
                if (folder.exists()) folder.deleteRecursively()
                if (folder.exists()) throw NotDeleted("$what not deleted")
            }
            else -> error("not the fixture's: $what")
        }
    }

    /**
     * Drains, then deletes, preferences [name]: a commit on the process's
     * own instance waits for every write queued before it (an `apply` from a
     * model's executor), then `deleteSharedPreferences` drops the instance
     * and its files. Run even when no file is there yet: a first write may
     * still be queued (the barrier then writes an empty file, deleted next).
     * Checked: a commit answering false, or a file still there, throws, and
     * the preferences stay the fixture's.
     */
    private fun removePreferences(name: String) {
        val prefs = stores.context.getSharedPreferences(name, Context.MODE_PRIVATE)
        if (!prefs.edit().commit()) throw NotWritten("$name: the commit barrier answered false")
        val answered = stores.context.deleteSharedPreferences(name)
        val left = stores.preferenceFiles(name).filter(File::exists)
        if (!answered || left.isNotEmpty()) throw NotDeleted("preferences $name not deleted: answered $answered; still there: ${left.map { it.name }}")
    }

    /**
     * Deletes the database and checks it is gone: its file and the files
     * SQLite keeps beside it. Absent already, it is done. Still there after
     * `deleteDatabase` (which answers false and throws nothing), it throws
     * [NotDeleted]: the database stays the fixture's, for a later [release].
     */
    private fun deleteDatabase() {
        val files = stores.databaseFiles()
        if (files.none(File::exists)) return
        val answered = stores.context.deleteDatabase(stores.database)
        val left = files.filter(File::exists)
        if (left.isNotEmpty()) throw NotDeleted("database ${stores.database} not deleted: deleteDatabase answered $answered; still there: ${left.map { it.name }}")
    }

    private fun hostPreferences() = stores.context.getSharedPreferences(stores.prefix + "hosts", Context.MODE_PRIVATE)

    /** The hosts the `hosts` preferences hold, decoded as production's [Hosts] does, with no migration written back. */
    private fun storedHosts(): List<Host> {
        val (hosts, migrated) = decodeHosts(hostPreferences().getString("hosts", null) ?: "[]") { it }
        // Only the fixture writes them, in the current encoding: an older one is not its own.
        check(!migrated) { "hosts in an earlier encoding: not the fixture's, not written" }
        return hosts
    }

    /** Writes [hosts] as production's [Hosts] keeps them, and checks `commit` reached the disk; on false it throws [NotWritten]. */
    private fun storeHosts(hosts: List<Host>, what: String) {
        if (!hostPreferences().edit().putString("hosts", encodeHosts(hosts)).commit()) {
            throw NotWritten("$what: the hosts were not written to disk (commit answered false)")
        }
    }

    /** A store still there after the fixture deleted it. */
    class NotDeleted(message: String) : IllegalStateException(message)

    /** Preferences whose write did not reach the disk. */
    class NotWritten(message: String) : IllegalStateException(message)

    private fun setup(condition: Boolean, why: () -> String) {
        if (!condition) throw AssertionError("SETUP (no product result): ${why()}")
    }

    private fun log(text: String) {
        Log.i("C82-fixture", text)
    }

    companion object {
        /** A device key's alias (Keys.kt, DEVICE_KEY_PREFIX): AppModel's sweep keeps it while the host is paired. */
        fun aliasOf(host: String) = "$DEVICE_KEY_PREFIX$host-test"
    }
}
