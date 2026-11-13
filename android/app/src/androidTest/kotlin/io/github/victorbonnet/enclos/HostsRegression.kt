package io.github.victorbonnet.enclos

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Exercises the real Android JSON and preferences path without touching the person's pairings. */
class HostsRegression : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val tests = listOf(
            "wrapped_pairings_round_trip_without_plaintext" to ::wrappedPairingsRoundTrip,
            "legacy_migration_preserves_other_wrapped_pairings" to ::legacyMigration,
            "adding_and_removing_preserve_other_wrapped_pairings" to ::addingAndRemoving,
        )
        var failures = 0
        tests.forEachIndexed { index, (name, test) ->
            val status = Bundle().apply {
                putString("class", this@HostsRegression.javaClass.name)
                putString("test", name)
                putInt("numtests", tests.size)
                putInt("current", index + 1)
            }
            sendStatus(1, status)
            try {
                isolated(test)
                status.putString("stream", "PASS $name\n")
                sendStatus(0, status)
            } catch (error: Throwable) {
                failures++
                // Do not print exception messages, fixture values or preference contents.
                status.putString("stream", "FAIL $name (${error.javaClass.simpleName})\n")
                status.putString("stack", error.javaClass.name)
                sendStatus(-2, status)
            }
        }
        finish(
            if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply {
                putString("stream", "Hosts regression: ${tests.size - failures}/${tests.size} passed\n")
                putInt("tests", tests.size)
                putInt("failures", failures)
            },
        )
    }

    private fun isolated(test: (Hosts, SharedPreferences) -> Unit) {
        val name = "hosts-regression-${UUID.randomUUID()}"
        val preferences = targetContext.getSharedPreferences(name, Context.MODE_PRIVATE)
        val context = object : ContextWrapper(targetContext) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "hosts") preferences else super.getSharedPreferences(name, mode)
        }
        try {
            test(Hosts(context), preferences)
        } finally {
            preferences.edit().clear().commit()
            targetContext.deleteSharedPreferences(name)
        }
    }

    private fun wrappedPairingsRoundTrip(hosts: Hosts, preferences: SharedPreferences) {
        val first = ByteArray(60) { (it + 1).toByte() }
        val second = ByteArray(60) { (it + 2).toByte() }
        write(preferences, record("first", "pushKeyWrapped", first), record("second", "pushKeyWrapped", second))
        val loaded = hosts.all()
        check(loaded.map { it.name } == listOf("first", "second"))
        hosts.add(loaded.first())
        val saved = saved(preferences)
        check(saved.keys == setOf("first", "second"))
        check(wrapped(saved.getValue("first")).contentEquals(first))
        check(wrapped(saved.getValue("second")).contentEquals(second))
        check(saved.values.none { it.has("pushKey") })
    }

    private fun legacyMigration(hosts: Hosts, preferences: SharedPreferences) {
        val kept = ByteArray(60) { (it + 3).toByte() }
        val plain = ByteArray(32) { (it + 4).toByte() }
        write(preferences, record("wrapped", "pushKeyWrapped", kept), record("legacy", "pushKey", plain))
        check(hosts.all().map { it.name } == listOf("wrapped", "legacy"))
        val saved = saved(preferences)
        check(saved.keys == setOf("wrapped", "legacy"))
        check(wrapped(saved.getValue("wrapped")).contentEquals(kept))
        check(Keys.unwrap(wrapped(saved.getValue("legacy"))).contentEquals(plain))
        check(saved.values.none { it.has("pushKey") })
        check(hosts.all().map { it.name } == listOf("wrapped", "legacy"))
    }

    private fun addingAndRemoving(hosts: Hosts, preferences: SharedPreferences) {
        val first = ByteArray(60) { (it + 5).toByte() }
        val second = ByteArray(60) { (it + 6).toByte() }
        write(preferences, record("first", "pushKeyWrapped", first), record("second", "pushKeyWrapped", second))
        hosts.add(hosts.all().first().copy(name = "added"))
        hosts.remove("first")
        val saved = saved(preferences)
        check(saved.keys == setOf("second", "added"))
        check(wrapped(saved.getValue("second")).contentEquals(second))
        check(wrapped(saved.getValue("added")).contentEquals(first))
        check(saved.values.none { it.has("pushKey") })
    }

    private fun record(name: String, pushField: String, push: ByteArray): JSONObject = JSONObject()
        .put("name", name)
        .put("url", "https://$name.example.invalid")
        .put("bridgeKey", encode(ByteArray(32) { 7 }))
        .put("deviceId", "fixture-$name")
        .put("noiseKey", encode(ByteArray(60) { 8 }))
        .put("keyAlias", "fixture-$name")
        .put(pushField, encode(push))

    private fun write(preferences: SharedPreferences, vararg records: JSONObject) {
        val array = JSONArray()
        records.forEach { array.put(it) }
        check(preferences.edit().putString("hosts", array.toString()).commit())
    }

    private fun saved(preferences: SharedPreferences): Map<String, JSONObject> {
        val array = JSONArray(preferences.getString("hosts", "[]"))
        return (0 until array.length()).map { array.getJSONObject(it) }.associateBy { it.getString("name") }
    }

    private fun wrapped(record: JSONObject): ByteArray = Base64.decode(record.getString("pushKeyWrapped"), Base64.NO_WRAP)

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
}
