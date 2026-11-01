package com.one4zero.sshterm.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts secrets (passwords, private keys) with an AES key that lives in the Android Keystore,
 * so they are never stored in plain text in the app's files.
 */
object SecretBox {
    private const val TAG = "SecretBox"
    private const val ALIAS = "sshterm_secrets_v1"
    private const val PREFIX_KEYSTORE = "k1:"
    private const val PREFIX_PLAIN = "p1:"

    @Volatile
    private var cachedKey: SecretKey? = null

    @Synchronized
    private fun key(): SecretKey {
        cachedKey?.let { return it }
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = ks.getKey(ALIAS, null) as? SecretKey
        val key = existing ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
        cachedKey = key
        return key
    }

    fun encrypt(plain: String): String {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX_KEYSTORE + b64(cipher.iv) + ":" + b64(ct)
        } catch (e: Exception) {
            // Some devices have a broken keystore; the app's private storage is still protected.
            Log.w(TAG, "Keystore encryption failed, storing without keystore", e)
            PREFIX_PLAIN + b64(plain.toByteArray(Charsets.UTF_8))
        }
    }

    fun decrypt(value: String?): String? {
        if (value.isNullOrEmpty()) return null
        return try {
            when {
                value.startsWith(PREFIX_KEYSTORE) -> {
                    val parts = value.substring(PREFIX_KEYSTORE.length).split(':')
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, unb64(parts[0])))
                    String(cipher.doFinal(unb64(parts[1])), Charsets.UTF_8)
                }
                value.startsWith(PREFIX_PLAIN) -> String(unb64(value.substring(PREFIX_PLAIN.length)), Charsets.UTF_8)
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to decrypt secret", e)
            null
        }
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)
}
