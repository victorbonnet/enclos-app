package io.github.victorbonnet.enclos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForgetTest {
    @Test
    fun device_keys_no_host_uses_are_deleted_and_nothing_else() {
        val aliases = listOf(
            "enclos-noise-wrap",
            "enclos-device-mac-1",
            "enclos-device-mac-2",
            "enclos-device-pi-3",
            "another-apps-key",
        )
        val used = setOf("enclos-device-mac-2", "enclos-device-pi-3")
        assertEquals(listOf("enclos-device-mac-1"), unusedDeviceKeys(aliases, used))
    }

    @Test
    fun with_no_host_paired_every_device_key_goes() {
        val aliases = listOf("enclos-noise-wrap", "enclos-device-mac-1")
        assertEquals(listOf("enclos-device-mac-1"), unusedDeviceKeys(aliases, emptySet()))
    }

    @Test
    fun only_an_id_that_names_one_folder_is_deleted() {
        assertTrue(attachmentFolderName("att-1f2e"))
        assertFalse(attachmentFolderName(""))
        assertFalse(attachmentFolderName("."))
        assertFalse(attachmentFolderName(".."))
        assertFalse(attachmentFolderName("../files"))
        assertFalse(attachmentFolderName("a\\b"))
    }
}
