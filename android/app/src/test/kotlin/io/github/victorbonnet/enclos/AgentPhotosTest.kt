package io.github.victorbonnet.enclos

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPhotosTest {
    private fun v(revision: Long, hash: String?) = PhotoVersion(revision, hash)

    @Test
    fun a_host_named_with_a_slash_never_reaches_another_hosts_agent() {
        // Joined as `host/agent`, both would be "home/lab/coder".
        val mine = PhotoKey("home", "lab/coder")
        val theirs = PhotoKey("home/lab", "coder")
        assertNotEquals(mine, theirs)
        assertNotEquals(mine.stored, theirs.stored)
        for (key in listOf(mine, theirs, PhotoKey("a|1|b", "c|d"), PhotoKey("", "x"))) {
            assertEquals(key, PhotoKey.parse(key.stored))
        }
        // An older cache's keys, which joined the two, read as nothing.
        listOf("home/lab/coder", "photo1|x|home", "photo1|99|short", "photo1|-1|x").forEach { assertNull(it, PhotoKey.parse(it)) }

        val versions = PhotoVersions()
        versions.listed("home", listOf("lab/coder" to v(1, "a")))
        versions.listed("home/lab", listOf("x/y" to v(0, null)))
        // The other host offers a photo for "coder", which it does not list.
        assertFalse(versions.accept(theirs, v(9, "evil"), listed = false))
        assertEquals(v(1, "a"), versions[mine])
        assertNull(versions[theirs])
    }

    @Test
    fun the_agent_list_is_the_hosts_word_even_when_its_revision_went_back() {
        val versions = PhotoVersions()
        val key = PhotoKey("mac", "lab/coder")
        assertTrue(versions.accept(key, v(5, "old"), listed = true))
        // The host's database was made again: its list says revision 1, another photo.
        val changes = versions.listed("mac", listOf("lab/coder" to v(1, "new")))
        assertEquals(listOf(key), changes.fetch)
        assertEquals(v(1, "new"), versions[key])
        // Its next change is taken; an older one still is not.
        assertTrue(versions.accept(key, v(2, "newer"), listed = true))
        assertFalse(versions.accept(key, v(1, "new"), listed = true))
        // A photo kept on the phone does not override what the host said.
        assertFalse(versions.cached(key, v(7, "cached")))
    }

    @Test
    fun agents_a_host_no_longer_lists_lose_their_photo() {
        val versions = PhotoVersions()
        versions.listed("mac", listOf("lab/coder" to v(1, "a"), "lab/writer" to v(1, "b")))
        val changes = versions.listed("mac", listOf("lab/coder" to v(1, "a")))
        assertEquals(listOf(PhotoKey("mac", "lab/writer")), changes.removed)
        assertNull(versions[PhotoKey("mac", "lab/writer")])
        // A removal on the host: the agent has none any more.
        val removed = versions.listed("mac", listOf("lab/coder" to v(2, null)))
        assertEquals(listOf(PhotoKey("mac", "lab/coder")), removed.removed)
        assertTrue(removed.fetch.isEmpty())
    }

    @Test
    fun one_host_keeps_a_bounded_number_of_photos() {
        val versions = PhotoVersions(maxPerHost = 2)
        val changes = versions.listed("mac", (1..4).map { "lab/a$it" to v(1, "h$it") })
        assertEquals(listOf("lab/a1", "lab/a2"), changes.fetch.map { it.agent })
        assertNull(versions[PhotoKey("mac", "lab/a3")]?.hash)
        // A broadcast cannot add a third, but can replace one it has.
        assertFalse(versions.accept(PhotoKey("mac", "lab/a3"), v(2, "x"), listed = true))
        assertTrue(versions.accept(PhotoKey("mac", "lab/a1"), v(2, "x"), listed = true))
        // Another host has its own limit.
        assertTrue(versions.accept(PhotoKey("pi", "lab/a1"), v(1, "y"), listed = true))
        assertFalse(versions.cached(PhotoKey("mac", "lab/a4"), v(1, "z")))
    }

    @Test
    fun a_refused_photo_says_why() {
        val versions = PhotoVersions(maxPerHost = 1)
        assertEquals(PhotoRefusal.Unlisted, versions.refusal(PhotoKey("mac", "lab/gone"), v(1, "x"), listed = false))
        assertTrue(versions.accept(PhotoKey("mac", "lab/a1"), v(2, "x"), listed = true))
        assertEquals(PhotoRefusal.Older, versions.refusal(PhotoKey("mac", "lab/a1"), v(1, "y"), listed = true))
        // The host already has as many photos as this phone keeps: its confirmation of this phone's edit is not taken.
        assertEquals(PhotoRefusal.Full, versions.refusal(PhotoKey("mac", "lab/a2"), v(1, "z"), listed = true))
        assertFalse(versions.accept(PhotoKey("mac", "lab/a2"), v(1, "z"), listed = true))
        assertTrue(photoRefusalText(PhotoRefusal.Full).contains("$MAX_PHOTOS_PER_HOST"))
        assertNull(versions.refusal(PhotoKey("mac", "lab/a1"), v(3, "w"), listed = true))
    }

    @Test
    fun an_avatar_message_without_its_agent_or_revision_is_ignored() {
        assertEquals("lab/main" to 4L, avatarHeader(JSONObject("""{"type":"agent.avatar","agent":"lab/main","revision":4,"version":null}""")))
        listOf(
            """{"type":"agent.avatar","revision":4}""",
            """{"type":"agent.avatar","agent":"lab/main"}""",
            """{"type":"agent.avatar","agent":"","revision":4}""",
            """{"type":"agent.avatar","agent":null,"revision":4}""",
            """{"type":"agent.avatar","agent":"lab/main","revision":"soon"}""",
            """{"type":"agent.avatar","agent":7,"revision":4}""",
        ).forEach { assertNull(it, avatarHeader(JSONObject(it))) }
    }
}
