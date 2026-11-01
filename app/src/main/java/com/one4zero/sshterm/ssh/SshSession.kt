package com.one4zero.sshterm.ssh

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.one4zero.sshterm.App
import com.one4zero.sshterm.data.AuthType
import com.one4zero.sshterm.data.HostConfig
import com.one4zero.sshterm.data.SecretBox
import com.one4zero.sshterm.terminal.TerminalEmulator
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** One interactive SSH shell with its terminal emulator. Lives in [SessionManager]. */
class SshSession(val id: Int, host: HostConfig) {
    enum class State { CONNECTING, CONNECTED, DISCONNECTED }

    interface Listener {
        fun onScreenChanged()
        fun onStateChanged()
        fun onPromptChanged()
        fun onTitleChanged() {}
        fun onBell() {}
    }

    var host: HostConfig = host
        private set

    private val app = App.instance
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ssh-writer-$id").apply { isDaemon = true }
    }
    private val updatePosted = AtomicBoolean(false)

    val emulator = TerminalEmulator(80, 24, app.settings.scrollbackLines) { bytes -> write(bytes) }

    @Volatile
    var state = State.CONNECTING
        private set

    @Volatile
    var statusMessage: String = ""
        private set

    /** Question waiting for the user, shown by the attached activity. */
    @Volatile
    var pendingPrompt: Prompt? = null
        private set

    @Volatile
    private var connection: SshConnection? = null
    private var thread: Thread? = null

    /** Absolute terminal row where the last command from the input line was sent. */
    var lastCommandRow = -1L

    @Volatile
    private var cols = 80

    @Volatile
    private var rows = 24

    @Volatile
    private var widthPx = 0

    @Volatile
    private var heightPx = 0

    /** Password typed by the user which should be saved after a successful login. */
    @Volatile
    private var passwordToSave: String? = null

    @Volatile
    private var closedByUser = false

    val title: String
        get() = host.displayName

    val terminalTitle: String?
        get() = emulator.title?.takeIf { it.isNotBlank() }

    init {
        emulator.onTitleChanged = { main.post { listeners.forEach { it.onTitleChanged() } } }
        emulator.onBell = { main.post { listeners.forEach { it.onBell() } } }
    }

    fun addListener(l: Listener) {
        listeners.addIfAbsent(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    /** Starts connecting. Must be called on the main thread. */
    fun start() {
        closedByUser = false
        val h = app.hosts.get(host.id) ?: host
        host = h
        val key = if (h.authType == AuthType.KEY) app.keys.get(h.keyId) else null
        val params = ConnectParams(
            host = h.host,
            port = h.port,
            username = h.username,
            password = SecretBox.decrypt(h.passwordEnc),
            privateKey = key?.let { SecretBox.decrypt(it.privateKeyEnc) },
            passphrase = key?.let { SecretBox.decrypt(it.passphraseEnc) },
        )
        if (h.authType == AuthType.KEY && key == null) {
            printInfo("Ключ для этого сервера не найден — будет запрошен пароль.", warning = true)
        }
        setState(State.CONNECTING, "Подключение…")
        val t = Thread({ run(params) }, "ssh-session-$id")
        t.isDaemon = true
        thread = t
        t.start()
    }

    private fun run(params: ConnectParams) {
        printInfo("Подключение к ${params.username}@${params.host}:${params.port}…")
        val conn = SshConnection(params, app.knownHosts, Callbacks())
        try {
            conn.connect(cols, rows, widthPx, heightPx)
        } catch (e: SshConnection.SshError) {
            Log.w(TAG, "connect failed", e)
            if (!closedByUser) {
                printInfo("Ошибка: ${e.message}", warning = true)
                setState(State.DISCONNECTED, e.message ?: "Ошибка подключения")
            }
            return
        }
        if (closedByUser) {
            conn.disconnect()
            return
        }
        connection = conn
        // the view may have been laid out while connecting: make sure the PTY has the current size
        onWriter { runCatching { conn.resize(cols, rows, widthPx, heightPx) } }
        setState(State.CONNECTED, "Подключено")
        main.post { onConnected() }

        val startup = host.startupCommand.trim()
        if (startup.isNotEmpty()) write((startup + "\r").toByteArray(Charsets.UTF_8))

        var error: String? = null
        val buf = ByteArray(32 * 1024)
        try {
            val input = conn.input
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) {
                    synchronized(emulator) {
                        try {
                            emulator.append(buf, 0, n)
                        } catch (e: RuntimeException) {
                            // never let an emulator bug kill the connection
                            Log.e(TAG, "terminal emulator error", e)
                        }
                    }
                    postScreenUpdate()
                }
            }
        } catch (e: IOException) {
            if (!conn.closed) error = e.message
        }
        val exit = conn.exitStatus
        conn.disconnect()
        connection = null
        if (!closedByUser) {
            val msg = when {
                error != null -> "Соединение потеряно: $error"
                exit >= 0 -> "Сеанс завершён (код $exit)"
                else -> "Соединение закрыто"
            }
            printInfo(msg, warning = error != null)
            setState(State.DISCONNECTED, msg)
        }
    }

    private fun onConnected() {
        val h = host
        if (!h.transient) {
            val toSave = passwordToSave
            val current = app.hosts.get(h.id)
            if (current != null) {
                var updated = current.copy(lastUsed = System.currentTimeMillis())
                if (toSave != null) updated = updated.copy(passwordEnc = SecretBox.encrypt(toSave))
                app.hosts.upsert(updated)
                host = updated
            }
        }
        passwordToSave = null
    }

    fun reconnect() {
        if (state != State.DISCONNECTED || closedByUser) return
        synchronized(emulator) { emulator.resetForReconnect() }
        start()
    }

    fun write(data: ByteArray) {
        if (data.isEmpty()) return
        onWriter {
            val c = connection ?: return@onWriter
            try {
                c.write(data)
            } catch (e: Exception) {
                Log.w(TAG, "write failed", e)
            }
        }
    }

    fun writeText(text: String) = write(text.toByteArray(Charsets.UTF_8))

    /** Pastes text into the terminal, using bracketed paste mode when the remote side enabled it. */
    fun paste(text: String) {
        val normalized = text.replace("\r\n", "\r").replace('\n', '\r')
        val bracketed = synchronized(emulator) { emulator.bracketedPaste }
        if (bracketed) {
            writeText("\u001b[200~" + normalized.replace("\u001b[201~", "") + "\u001b[201~")
        } else {
            writeText(normalized)
        }
    }

    /** Sends a command from the input line followed by Enter. */
    fun sendCommand(command: String) {
        synchronized(emulator) {
            lastCommandRow = if (emulator.isAltScreen) -1 else emulator.absoluteRow(emulator.cursorY)
        }
        if (command.contains('\n')) {
            paste(command.trimEnd('\n', '\r'))
            writeText("\r")
        } else {
            writeText(command + "\r")
        }
    }

    /** Output of the last command sent from the input line (between the command and the next prompt). */
    fun lastCommandOutput(): String? = synchronized(emulator) {
        val mark = lastCommandRow
        if (mark < 0 || emulator.isAltScreen) return@synchronized null
        var start = mark
        // skip the echoed (possibly wrapped) command line
        while (true) {
            val row = emulator.rowByAbsolute(start) ?: return@synchronized null
            if (!row.wrapped) break
            start++
        }
        start++
        val end = emulator.absoluteRow(emulator.cursorY) - 1
        if (end < start) return@synchronized ""
        emulator.getText(start, 0, end, Int.MAX_VALUE).trimEnd()
    }

    fun resize(cols: Int, rows: Int, widthPx: Int, heightPx: Int) {
        this.cols = cols
        this.rows = rows
        this.widthPx = widthPx
        this.heightPx = heightPx
        synchronized(emulator) { emulator.resize(cols, rows) }
        onWriter {
            try {
                connection?.resize(cols, rows, widthPx, heightPx)
            } catch (e: Exception) {
                Log.w(TAG, "resize failed", e)
            }
        }
    }

    /** Disconnects; the session must then be removed from [SessionManager]. */
    fun close() {
        closedByUser = true
        pendingPrompt?.cancel()
        val c = connection
        connection = null
        onWriter { c?.disconnect() }
        thread?.interrupt()
        writer.shutdown()
        setState(State.DISCONNECTED, "Отключено")
    }

    /** Runs [task] on the writer thread; ignored after the session was closed. */
    private fun onWriter(task: () -> Unit) {
        try {
            writer.execute(task)
        } catch (e: RejectedExecutionException) {
            // session closed
        }
    }

    private fun setState(s: State, message: String) {
        state = s
        statusMessage = message
        main.post {
            listeners.forEach { it.onStateChanged() }
            SessionManager.notifyChanged()
        }
    }

    private fun postScreenUpdate() {
        if (updatePosted.compareAndSet(false, true)) {
            main.post {
                updatePosted.set(false)
                listeners.forEach { it.onScreenChanged() }
            }
        }
    }

    /** Writes a coloured informational line into the terminal. */
    fun printInfo(text: String, warning: Boolean = false) {
        val color = if (warning) "\u001b[1;33m" else "\u001b[36m"
        val body = text.replace("\r\n", "\n").replace("\n", "\r\n")
        synchronized(emulator) {
            val prefix = if (emulator.cursorX > 0) "\r\n" else ""
            emulator.append("$prefix$color$body\u001b[0m\r\n")
        }
        postScreenUpdate()
    }

    private fun ask(prompt: Prompt): Prompt {
        if (closedByUser) {
            prompt.cancel()
            return prompt
        }
        pendingPrompt = prompt
        main.post { listeners.forEach { it.onPromptChanged() } }
        try {
            prompt.await()
        } catch (e: InterruptedException) {
            prompt.cancel()
        }
        if (pendingPrompt === prompt) pendingPrompt = null
        main.post { listeners.forEach { it.onPromptChanged() } }
        return prompt
    }

    private inner class Callbacks : ConnectionCallbacks {
        override fun confirmNewHostKey(host: String, keyType: String, fingerprint: String): Boolean {
            val p = ask(
                Prompt(
                    title = "Новый сервер",
                    message = "Подлинность сервера $host не проверена.\n\n" +
                        "Тип ключа: $keyType\nОтпечаток:\n$fingerprint\n\n" +
                        "Доверять этому серверу и запомнить его ключ?",
                    positive = "Доверять",
                    negative = "Отмена",
                ),
            )
            if (p.accepted) printInfo("Ключ сервера сохранён: $fingerprint")
            return p.accepted
        }

        override fun confirmChangedHostKey(host: String, keyType: String, oldFingerprint: String, newFingerprint: String): Boolean {
            printInfo("ВНИМАНИЕ: ключ сервера $host изменился!", warning = true)
            val p = ask(
                Prompt(
                    title = "Ключ сервера изменился!",
                    message = "Ключ сервера $host отличается от сохранённого. Это может означать " +
                        "атаку «человек посередине» или переустановку сервера.\n\n" +
                        "Тип ключа: $keyType\nБыл:\n$oldFingerprint\nСтал:\n$newFingerprint\n\n" +
                        "Принять новый ключ?",
                    positive = "Принять новый ключ",
                    negative = "Отключиться",
                    warning = true,
                ),
            )
            return p.accepted
        }

        override fun askPassword(prompt: String): String? {
            val p = ask(
                Prompt(
                    title = "Пароль",
                    message = "${host.username}@${host.host}",
                    fields = listOf(Prompt.Field("Пароль", true)),
                    positive = "Войти",
                    negative = "Отмена",
                    offerSave = !host.transient,
                ),
            )
            if (!p.accepted) return null
            val pw = p.values.firstOrNull() ?: ""
            passwordToSave = if (p.save) pw else null
            return pw
        }

        override fun askPassphrase(prompt: String): String? {
            val p = ask(
                Prompt(
                    title = "Парольная фраза ключа",
                    message = prompt,
                    fields = listOf(Prompt.Field("Парольная фраза", true)),
                    positive = "OK",
                    negative = "Отмена",
                ),
            )
            return if (p.accepted) p.values.firstOrNull() ?: "" else null
        }

        override fun askKeyboardInteractive(
            name: String,
            instruction: String,
            prompts: Array<String>,
            echo: BooleanArray,
        ): Array<String>? {
            val isPassword = prompts.size == 1 && !echo[0] && prompts[0].contains("password", ignoreCase = true)
            val p = ask(
                Prompt(
                    title = name.ifBlank { if (isPassword) "Пароль" else "Вход на сервер" },
                    message = listOf("${host.username}@${host.host}", instruction).filter { it.isNotBlank() }.joinToString("\n"),
                    fields = prompts.mapIndexed { i, s -> Prompt.Field(s.trim().trimEnd(':'), !echo[i]) },
                    positive = "Войти",
                    negative = "Отмена",
                    offerSave = isPassword && !host.transient,
                ),
            )
            if (!p.accepted) return null
            if (isPassword) passwordToSave = if (p.save) p.values.firstOrNull() else null
            return Array(prompts.size) { p.values.getOrElse(it) { "" } }
        }

        override fun showMessage(message: String) {
            printInfo(message.trimEnd())
        }
    }

    companion object {
        private const val TAG = "SshSession"
    }
}
