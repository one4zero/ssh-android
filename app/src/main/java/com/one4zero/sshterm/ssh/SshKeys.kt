package com.one4zero.sshterm.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Generation and parsing of SSH private keys. Pure JVM code (no Android dependencies). */
object SshKeys {
    class KeyInfo(
        val type: String,
        /** Public key in OpenSSH authorized_keys format. */
        val publicKey: String,
        val fingerprint: String,
        val encrypted: Boolean,
    )

    class GeneratedKey(val privateKey: String, val info: KeyInfo)

    class KeyException(message: String) : Exception(message)

    /**
     * Parses a private key (OpenSSH, PEM or PuTTY format). Throws [KeyException] with a
     * user readable message when the key is invalid or the passphrase is wrong.
     */
    fun parse(privateKey: String, passphrase: String?, comment: String = ""): KeyInfo {
        val text = privateKey.trim()
        if (text.isEmpty()) throw KeyException("Пустой ключ")
        val kp = try {
            KeyPair.load(JSch(), text.toByteArray(Charsets.UTF_8), null)
        } catch (e: Exception) {
            throw KeyException("Не удалось прочитать ключ: ${e.message}")
        }
        try {
            val encrypted = kp.isEncrypted
            if (encrypted) {
                if (passphrase.isNullOrEmpty()) throw KeyException("Ключ зашифрован — укажите парольную фразу")
                if (!kp.decrypt(passphrase)) throw KeyException("Неверная парольная фраза")
            }
            val blob = kp.publicKeyBlob ?: throw KeyException("Не удалось получить публичный ключ")
            val type = readString(blob)
            return KeyInfo(
                type = type,
                publicKey = formatPublicKey(blob, comment),
                fingerprint = fingerprint(blob),
                encrypted = encrypted,
            )
        } finally {
            kp.dispose()
        }
    }

    fun isEncrypted(privateKey: String): Boolean = try {
        val kp = KeyPair.load(JSch(), privateKey.trim().toByteArray(Charsets.UTF_8), null)
        kp.isEncrypted.also { kp.dispose() }
    } catch (e: Exception) {
        false
    }

    /** Generates an Ed25519 key in OpenSSH format. */
    fun generateEd25519(comment: String): GeneratedKey {
        val random = SecureRandom()
        val priv = Ed25519PrivateKeyParameters(random)
        val seed = priv.encoded
        val pub = priv.generatePublicKey().encoded
        val keyType = "ssh-ed25519"

        val pubBlob = SshBuffer().putString(keyType).putBytes(pub).toByteArray()

        val check = random.nextInt()
        val privSection = SshBuffer()
            .putInt(check).putInt(check)
            .putString(keyType)
            .putBytes(pub)
            .putBytes(seed + pub)
            .putString(comment)
        var pad = 1
        while (privSection.size() % 8 != 0) privSection.putByte(pad++)

        val body = SshBuffer()
        body.putRaw("openssh-key-v1".toByteArray(Charsets.US_ASCII) + byteArrayOf(0))
            .putString("none")
            .putString("none")
            .putBytes(ByteArray(0))
            .putInt(1)
            .putBytes(pubBlob)
            .putBytes(privSection.toByteArray())

        val b64 = Base64.getEncoder().encodeToString(body.toByteArray())
        val pem = buildString {
            append("-----BEGIN OPENSSH PRIVATE KEY-----\n")
            b64.chunked(70).forEach { append(it).append('\n') }
            append("-----END OPENSSH PRIVATE KEY-----\n")
        }
        return GeneratedKey(pem, KeyInfo(keyType, formatPublicKey(pubBlob, comment), fingerprint(pubBlob), false))
    }

    /** Generates an RSA key in PEM (PKCS#1) format. */
    fun generateRsa(bits: Int, comment: String): GeneratedKey {
        val kp = KeyPair.genKeyPair(JSch(), KeyPair.RSA, bits)
        try {
            val out = ByteArrayOutputStream()
            kp.writePrivateKey(out)
            val blob = kp.publicKeyBlob
            return GeneratedKey(
                String(out.toByteArray(), Charsets.US_ASCII),
                KeyInfo("ssh-rsa", formatPublicKey(blob, comment), fingerprint(blob), false),
            )
        } finally {
            kp.dispose()
        }
    }

    fun formatPublicKey(blob: ByteArray, comment: String): String {
        val type = readString(blob)
        val b64 = Base64.getEncoder().encodeToString(blob)
        return if (comment.isBlank()) "$type $b64" else "$type $b64 ${comment.trim()}"
    }

    /** OpenSSH style SHA256 fingerprint. */
    fun fingerprint(blob: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    /** Reads the first SSH string of a key blob (the key type). */
    fun readString(blob: ByteArray): String {
        if (blob.size < 4) return "unknown"
        val len = ((blob[0].toInt() and 0xff) shl 24) or ((blob[1].toInt() and 0xff) shl 16) or
            ((blob[2].toInt() and 0xff) shl 8) or (blob[3].toInt() and 0xff)
        if (len < 0 || len > blob.size - 4) return "unknown"
        return String(blob, 4, len, Charsets.US_ASCII)
    }

    private class SshBuffer {
        private val out = ByteArrayOutputStream()
        fun putInt(v: Int) = apply {
            out.write(v ushr 24); out.write(v ushr 16); out.write(v ushr 8); out.write(v)
        }
        fun putByte(v: Int) = apply { out.write(v) }
        fun putRaw(b: ByteArray) = apply { out.write(b) }
        fun putBytes(b: ByteArray) = apply { putInt(b.size); out.write(b) }
        fun putString(s: String) = putBytes(s.toByteArray(Charsets.UTF_8))
        fun size() = out.size()
        fun toByteArray(): ByteArray = out.toByteArray()
    }
}
