package com.one4zero.sshterm.data

import org.json.JSONObject
import java.util.UUID

enum class AuthType { PASSWORD, KEY }

data class HostConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val authType: AuthType = AuthType.PASSWORD,
    /** Encrypted password (see [SecretBox]) or null when it is asked on every connect. */
    val passwordEnc: String? = null,
    val keyId: String? = null,
    val startupCommand: String = "",
    val lastUsed: Long = 0,
    /** Not saved in the list (quick connect). */
    val transient: Boolean = false,
) {
    val displayName: String get() = name.ifBlank { address }

    /** Key for the command history: quick connections share history per address. */
    val historyKey: String get() = if (transient) "quick:$address" else id
    val address: String get() = buildString {
        if (username.isNotBlank()) append(username).append('@')
        append(host)
        if (port != 22) append(':').append(port)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("host", host)
        put("port", port)
        put("username", username)
        put("authType", authType.name)
        put("passwordEnc", passwordEnc ?: JSONObject.NULL)
        put("keyId", keyId ?: JSONObject.NULL)
        put("startupCommand", startupCommand)
        put("lastUsed", lastUsed)
    }

    companion object {
        fun fromJson(o: JSONObject) = HostConfig(
            id = o.getString("id"),
            name = o.optString("name"),
            host = o.optString("host"),
            port = o.optInt("port", 22),
            username = o.optString("username"),
            authType = runCatching { AuthType.valueOf(o.optString("authType")) }.getOrDefault(AuthType.PASSWORD),
            passwordEnc = o.optStringOrNull("passwordEnc"),
            keyId = o.optStringOrNull("keyId"),
            startupCommand = o.optString("startupCommand"),
            lastUsed = o.optLong("lastUsed"),
        )

        /** Parses "user@host:port" (port and user optional). */
        fun parseQuick(input: String): HostConfig? {
            val s = input.trim().removePrefix("ssh ").trim()
            if (s.isEmpty() || s.contains(' ')) return null
            val at = s.lastIndexOf('@')
            val user = if (at > 0) s.substring(0, at) else ""
            var hostPart = if (at >= 0) s.substring(at + 1) else s
            var port = 22
            if (hostPart.startsWith("[")) {
                val end = hostPart.indexOf(']')
                if (end < 0) return null
                val rest = hostPart.substring(end + 1)
                if (rest.startsWith(":")) port = rest.substring(1).toIntOrNull() ?: return null
                hostPart = hostPart.substring(1, end)
            } else if (hostPart.count { it == ':' } == 1) {
                val idx = hostPart.indexOf(':')
                port = hostPart.substring(idx + 1).toIntOrNull() ?: return null
                hostPart = hostPart.substring(0, idx)
            }
            if (hostPart.isEmpty() || port !in 1..65535) return null
            return HostConfig(id = "quick-" + UUID.randomUUID(), host = hostPart, port = port, username = user, transient = true)
        }
    }
}

data class SshKeyEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: String,
    val publicKey: String,
    val fingerprint: String,
    /** Encrypted private key text. */
    val privateKeyEnc: String,
    /** Encrypted passphrase (optional). */
    val passphraseEnc: String? = null,
    val encrypted: Boolean = false,
    val created: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("type", type)
        put("publicKey", publicKey)
        put("fingerprint", fingerprint)
        put("privateKeyEnc", privateKeyEnc)
        put("passphraseEnc", passphraseEnc ?: JSONObject.NULL)
        put("encrypted", encrypted)
        put("created", created)
    }

    companion object {
        fun fromJson(o: JSONObject) = SshKeyEntry(
            id = o.getString("id"),
            name = o.optString("name"),
            type = o.optString("type"),
            publicKey = o.optString("publicKey"),
            fingerprint = o.optString("fingerprint"),
            privateKeyEnc = o.getString("privateKeyEnc"),
            passphraseEnc = o.optStringOrNull("passphraseEnc"),
            encrypted = o.optBoolean("encrypted"),
            created = o.optLong("created"),
        )
    }
}

data class Snippet(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val command: String,
    /** Send immediately with Enter instead of inserting into the input line. */
    val autoRun: Boolean = false,
) {
    val label: String get() = name.ifBlank { command }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("command", command)
        put("autoRun", autoRun)
    }

    companion object {
        fun fromJson(o: JSONObject) = Snippet(
            id = o.getString("id"),
            name = o.optString("name"),
            command = o.optString("command"),
            autoRun = o.optBoolean("autoRun"),
        )
    }
}

data class KnownHostKey(val host: String, val type: String, val key: String) {
    fun toJson(): JSONObject = JSONObject().put("host", host).put("type", type).put("key", key)

    companion object {
        fun fromJson(o: JSONObject) = KnownHostKey(o.getString("host"), o.getString("type"), o.getString("key"))
    }
}

fun JSONObject.optStringOrNull(name: String): String? =
    if (isNull(name) || !has(name)) null else optString(name).takeIf { it.isNotEmpty() }
