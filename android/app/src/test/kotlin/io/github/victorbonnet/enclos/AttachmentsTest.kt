package io.github.victorbonnet.enclos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentsTest {
    @Test
    fun only_the_bridges_ids_name_a_folder() {
        assertTrue(Attachments.validId("0b9a4f4e-5c1d-4c8e-9f57-2b1f0f6a7c11"))
        assertFalse(Attachments.validId("../../files"))
        assertFalse(Attachments.validId(".."))
        assertFalse(Attachments.validId("a/b"))
        assertFalse(Attachments.validId("a\\b"))
        assertFalse(Attachments.validId(""))
        assertFalse(Attachments.validId("a".repeat(65)))
    }

    @Test
    fun a_name_on_disk_is_one_plain_segment() {
        assertEquals("_x", Attachments.fileName("../x"))
        assertEquals("part", Attachments.fileName(".part"))
        assertEquals("file", Attachments.fileName(""))
        assertEquals("file", Attachments.fileName("..."))
        assertEquals("rapport _t_.pdf", Attachments.fileName("rapport été.pdf"))
        assertEquals("host", Attachments.hostFolder(".."))
        assertEquals("mac.tailnet", Attachments.hostFolder("mac.tailnet"))
    }

    @Test
    fun a_long_name_keeps_its_extension() {
        val kept = Attachments.fileName("a".repeat(116) + ".pdf")
        assertEquals(Attachments.MOST_NAME, kept.length)
        assertTrue(kept.endsWith(".pdf"))
        // A dot far from the end is no extension.
        assertEquals(Attachments.MOST_NAME, Attachments.fileName("v1." + "b".repeat(117)).length)
    }

    @Test
    fun parts_are_written_in_order_until_the_size() {
        assertEquals(Part.Write(45_000, complete = false), part(0, 0, 45_000, 100_000))
        assertEquals(Part.Write(90_000, complete = false), part(45_000, 45_000, 45_000, 100_000))
        assertEquals(Part.Write(100_000, complete = true), part(90_000, 90_000, 10_000, 100_000))
        assertEquals(Part.Write(0, complete = true), part(0, 0, 0, 0))
    }

    @Test
    fun a_repeated_part_is_dropped_and_one_out_of_order_fails() {
        assertEquals(Part.Stale, part(90_000, 45_000, 45_000, 100_000))
        assertEquals(Part.Wrong, part(45_000, 90_000, 10_000, 100_000))
        assertEquals(Part.Wrong, part(45_000, 45_000, 0, 100_000))
    }

    @Test
    fun a_large_file_waits_for_a_tap_on_a_metered_network_only() {
        // Unmetered (Wi-Fi): anything the bridge sends comes by itself.
        assertTrue(Attachments.fetchesItself(0, metered = false))
        assertTrue(Attachments.fetchesItself(Attachments.FETCH_ON_TAP + 1, metered = false))
        assertTrue(Attachments.fetchesItself(25L * 1024 * 1024, metered = false))
        assertFalse(Attachments.fetchesItself(25L * 1024 * 1024 + 1, metered = false))
        // Metered (mobile data): up to 10 MB by itself, above it on a tap.
        assertTrue(Attachments.fetchesItself(0, metered = true))
        assertTrue(Attachments.fetchesItself(10_000_000, metered = true))
        assertFalse(Attachments.fetchesItself(10_000_001, metered = true))
        assertFalse(Attachments.fetchesItself(25L * 1024 * 1024, metered = true))
    }

    @Test
    fun a_large_image_is_decoded_within_bounds() {
        // Was decoded whole (144 MB), above what Android draws.
        assertEquals(2, Images.sampleSize(6_000, 6_000, 4_096))
        assertEquals(1, Images.sampleSize(4_096, 3_000, 4_096))
        assertEquals(1, Images.sampleSize(1, 1, 1_080))
        val sizes = listOf(6_000 to 6_000, 10 to 4_000, 4_097 to 1, 30_000 to 30_000, 65_535 to 65_535, Int.MAX_VALUE to Int.MAX_VALUE, 0 to 0)
        for ((w, h) in sizes) {
            for (longest in listOf(1_080, 4_096)) {
                val s = Images.sampleSize(w, h, longest)
                val sw = (w.toLong() + s - 1) / s
                val sh = (h.toLong() + s - 1) / s
                assertTrue("$w x $h", maxOf(sw, sh) <= longest)
                assertTrue("$w x $h", sw * sh * 4 <= Images.MOST_BYTES)
            }
        }
        assertTrue(Images.MOST_BYTES < 100L * 1024 * 1024)
        assertFalse(Images.drawable(Images.MOST_BYTES + 1))
        assertTrue(Images.drawable(4_096L * 4_096 * 4))
    }

    @Test
    fun a_thin_tall_image_stays_within_three_widths() {
        assertEquals(1 / 3f, Images.ratio(10, 4_000), 0f)
        assertEquals(3f, Images.ratio(4_000, 10), 0f)
        assertEquals(800 / 600f, Images.ratio(800, 600), 0f)
        assertEquals(1f, Images.ratio(0, 0), 0f)
        // Whatever the image, a bubble of any width is at most three widths tall.
        for ((w, h) in listOf(1 to Int.MAX_VALUE, 10 to 4_000, Int.MAX_VALUE to 1)) {
            assertTrue(1080 / Images.ratio(w, h) <= 3 * 1080)
        }
    }
}
