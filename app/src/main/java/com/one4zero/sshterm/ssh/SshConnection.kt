package com.one4zero.sshterm.ssh

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchChangedHostKeyException
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.JSchUnknownHostKeyException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import com.one4zero.sshterm.data.KnownHostKey
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Base64

/** Storage of trusted host keys used by [SshConnection]. */
interface HostKeyStore {
    fun find(host: String): List<KnownHostKey>
    fun put(entry: KnownHostKey)
    fun remove(host: String, type: String?)
}

/** User interaction needed while connecting. All methods are called on the connection thread and may block. */
interface ConnectionCallbacks {
    /** Unknown server key. Return true to trust and remember it. */
    fun confirmNewHostKey(host: String, keyType: String, fingerprint: String): Boolean

    /** Server key differs from the remembered one. Return true to replace it. */
    fun confirmChangedHostKey(host: String, keyType: String, oldFingerprint: String, newFingerprint: String): Boolean

    /** Returns the password, or null if the user cancelled. */
    fun askPassword(prompt: String): String?

    /** Returns the key passphrase, or null if the user cancelled. */
    fun askPassphrase(prompt: String): String?

    /** Returns answers, or null if the user cancelled. */
    fun askKeyboardInteractive(name: String, instruction: String, prompts: Array<String>, echo: BooleanArray): Array<String>?

    fun showMessage(message: String)
}

class ConnectParams(
    val host: String,
    val port: Int,
    val username: String,
    val password: String?,
    val privateKey: String?,
    val passphrase: String?,
)

/** A JSch based interactive shell connection. */
class SshConnection(
    private val params: ConnectParams,
    private val hostKeys: HostKeyStore,
    private val callbacks: ConnectionCallbacks,
) {
    private var session: Session? = null
    private var channel: ChannelShell? = null

    lateinit var input: InputStream
        private set
    private lateinit var output: OutputStream

    @Volatile
    var closed = false
        private set

    /** Connects, authenticates and opens a shell. Throws [SshError] with a readable message. */
    fun connect(cols: Int, rows: Int, widthPx: Int, heightPx: Int) {
        try {
            val jsch = JSch()
            if (!params.privateKey.isNullOrBlank()) {
                jsch.addIdentity(
                    "key",
                    params.privateKey.trim().toByteArray(Charsets.UTF_8),
                    null,
                    params.passphrase?.toByteArray(Charsets.UTF_8),
                )
            }
            val s = jsch.getSession(params.username, params.host, params.port)
            session = s
            s.setHostKeyRepository(Verifier())
            s.setConfig("StrictHostKeyChecking", "yes")
            s.setConfig(
                "PreferredAuthentications",
                if (params.privateKey.isNullOrBlank()) "keyboard-interactive,password"
                else "publickey,keyboard-interactive,password",
            )
            s.userInfo = Ui()
            s.setServerAliveInterval(20_000)
            s.setServerAliveCountMax(4)
            s.connect(20_000)

            val ch = s.openChannel("shell") as ChannelShell
            channel = ch
            ch.setPtyType("xterm-256color", cols, rows, widthPx, heightPx)
            ch.setAgentForwarding(false)
            input = ch.inputStream
            output = ch.outputStream
            ch.connect(15_000)
        } catch (e: Exception) {
            disconnect()
            throw SshError(describe(e), e)
        }
    }

    fun write(data: ByteArray) {
        if (closed) return
        output.write(data)
        output.flush()
    }

    fun resize(cols: Int, rows: Int, widthPx: Int, heightPx: Int) {
        channel?.setPtySize(cols, rows, widthPx, heightPx)
    }

    val exitStatus: Int get() = channel?.exitStatus ?: -1

    val isConnected: Boolean get() = session?.isConnected == true && channel?.isClosed == false

    fun disconnect() {
        closed = true
        runCatching { channel?.disconnect() }
        runCatching { session?.disconnect() }
    }

    private inner class Ui : UserInfo, UIKeyboardInteractive {
        private var password: String? = null
        private var passphrase: String? = null
        private var savedPasswordUsed = false
        private var savedPasswordUsedKi = false
        private var savedPassphraseUsed = false

        override fun getPassword(): String? = password
        override fun getPassphrase(): String? = passphrase

        override fun promptPassword(message: String): Boolean {
            val saved = params.password
            password = if (saved != null && !savedPasswordUsed) {
                savedPasswordUsed = true
                saved
            } else {
                callbacks.askPassword(message)
            }
            return password != null
        }

        override fun promptPassphrase(message: String): Boolean {
            val saved = params.passphrase
            passphrase = if (saved != null && !savedPassphraseUsed) {
                savedPassphraseUsed = true
                saved
            } else {
                callbacks.askPassphrase(message)
            }
            return passphrase != null
        }

        override fun promptYesNo(message: String): Boolean = false

        override fun showMessage(message: String) {
            callbacks.showMessage(message)
        }

        override fun promptKeyboardInteractive(
            destination: String,
            name: String,
            instruction: String,
            prompt: Array<String>,
            echo: BooleanArray,
        ): Array<String>? {
            if (prompt.isEmpty()) {
                if (name.isNotBlank() || instruction.isNotBlank()) {
                    callbacks.showMessage(listOf(name, instruction).filter { it.isNotBlank() }.joinToString("\n"))
                }
                return emptyArray()
            }
            val saved = params.password
            if (saved != null && !savedPasswordUsedKi && prompt.size == 1 && !echo[0] &&
                prompt[0].contains("password", ignoreCase = true)
            ) {
                savedPasswordUsedKi = true
                return arrayOf(saved)
            }
            return callbacks.askKeyboardInteractive(name, instruction, prompt, echo)
        }
    }

    /** Host key check: trust on first use, warn loudly when the key changes. */
    private inner class Verifier : HostKeyRepository {
        override fun check(host: String, key: ByteArray): Int {
            val type = SshKeys.readString(key)
            val encoded = Base64.getEncoder().encodeToString(key)
            val known = hostKeys.find(host)
            val sameType = known.firstOrNull { it.type == type }
            if (sameType != null && sameType.key == encoded) return HostKeyRepository.OK
            val fp = SshKeys.fingerprint(key)
            if (sameType != null) {
                val oldFp = runCatching { SshKeys.fingerprint(Base64.getDecoder().decode(sameType.key)) }.getOrDefault("?")
                return if (callbacks.confirmChangedHostKey(host, type, oldFp, fp)) {
                    hostKeys.put(KnownHostKey(host, type, encoded))
                    HostKeyRepository.OK
                } else {
                    HostKeyRepository.CHANGED
                }
            }
            return if (callbacks.confirmNewHostKey(host, type, fp)) {
                hostKeys.put(KnownHostKey(host, type, encoded))
                HostKeyRepository.OK
            } else {
                HostKeyRepository.NOT_INCLUDED
            }
        }

        override fun add(hostkey: HostKey, ui: UserInfo?) {
            hostKeys.put(KnownHostKey(hostkey.host, hostkey.type, hostkey.key))
        }

        override fun remove(host: String, type: String?) {
            hostKeys.remove(host, type)
        }

        override fun remove(host: String, type: String?, key: ByteArray?) {
            hostKeys.remove(host, type)
        }

        override fun getKnownHostsRepositoryID(): String = "ssh-term"

        override fun getHostKey(): Array<HostKey> = emptyArray()

        override fun getHostKey(host: String?, type: String?): Array<HostKey> {
            if (host == null) return emptyArray()
            return hostKeys.find(host)
                .filter { type == null || it.type == type }
                .mapNotNull { runCatching { HostKey(it.host, Base64.getDecoder().decode(it.key)) }.getOrNull() }
                .toTypedArray()
        }
    }

    class SshError(message: String, cause: Throwable?) : Exception(message, cause)

    companion object {
        fun describe(e: Throwable): String {
            var cause: Throwable? = e
            while (cause != null) {
                when (cause) {
                    is UnknownHostException -> return "Не удалось найти хост: ${cause.message ?: ""}".trim()
                    is ConnectException -> return "Соединение отклонено (${cause.message ?: "connect"})"
                    is NoRouteToHostException -> return "Нет маршрута до хоста"
                    is SocketTimeoutException -> return "Превышено время ожидания"
                    is JSchChangedHostKeyException -> return "Ключ сервера изменился — подключение отклонено"
                    is JSchUnknownHostKeyException -> return "Ключ сервера не принят"
                }
                cause = cause.cause
            }
            val msg = e.message ?: e.javaClass.simpleName
            return when {
                e is JSchException && msg.contains("Auth fail", ignoreCase = true) ->
                    "Ошибка аутентификации: неверный логин, пароль или ключ"
                e is JSchException && msg.contains("Auth cancel", ignoreCase = true) -> "Вход отменён"
                msg.contains("timeout", ignoreCase = true) -> "Превышено время ожидания"
                msg.contains("Algorithm negotiation fail", ignoreCase = true) ->
                    "Сервер не поддерживает совместимые алгоритмы шифрования"
                else -> msg
            }
        }
    }
}
