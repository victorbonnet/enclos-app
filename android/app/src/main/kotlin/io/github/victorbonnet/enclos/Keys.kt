package io.github.victorbonnet.enclos

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The device's keys, in the Android Keystore (docs/protocol.md, Pairing):
 * a P-256 signing key per host, which never leaves the hardware, and an AES
 * key that wraps the Noise static keys the app keeps in its preferences.
 */
object Keys : PairingKeys {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val WRAP_ALIAS = "enclos-noise-wrap"

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    /** Makes the signing key for [alias], in StrongBox where the phone has it. */
    override fun newDeviceKey(alias: String) {
        fun generate(strongBox: Boolean) {
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setIsStrongBoxBacked(strongBox)
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).apply {
                initialize(spec)
                generateKeyPair()
            }
        }
        try {
            generate(strongBox = true)
        } catch (_: StrongBoxUnavailableException) {
            generate(strongBox = false)
        }
    }

    override fun deleteDeviceKey(alias: String) {
        keyStore().deleteEntry(alias)
    }

    /** The signing key's public half as SEC1: 0x04, then X and Y, 32 bytes each. */
    fun devicePublicKey(alias: String): ByteArray {
        val key = keyStore().getCertificate(alias).publicKey as ECPublicKey
        fun fixed(n: java.math.BigInteger): ByteArray {
            val bytes = n.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
            return ByteArray(32 - bytes.size) + bytes
        }
        return byteArrayOf(4) + fixed(key.w.affineX) + fixed(key.w.affineY)
    }

    /** A DER ECDSA signature of [message], made inside the keystore. */
    fun sign(alias: String, message: ByteArray): ByteArray {
        val key = keyStore().getKey(alias, null) as PrivateKey
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(message)
            sign()
        }
    }

    private fun wrapKey(): SecretKey {
        val store = keyStore()
        (store.getKey(WRAP_ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(
            WRAP_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    /** [plain] encrypted with the keystore's AES key: IV (12 bytes), then the ciphertext. */
    override fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrapKey())
        return cipher.iv + cipher.doFinal(plain)
    }

    fun unwrap(wrapped: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, wrapped.copyOfRange(0, 12)))
        return cipher.doFinal(wrapped.copyOfRange(12, wrapped.size))
    }
}
