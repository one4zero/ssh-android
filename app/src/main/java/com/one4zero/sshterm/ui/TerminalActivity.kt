package com.one4zero.sshterm.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toolbar
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.one4zero.sshterm.App
import com.one4zero.sshterm.R
import com.one4zero.sshterm.data.Snippet
import com.one4zero.sshterm.ssh.Prompt
import com.one4zero.sshterm.ssh.SessionManager
import com.one4zero.sshterm.ssh.SshSession
import com.one4zero.sshterm.terminal.AiText
import com.one4zero.sshterm.terminal.KeyEncoder
import com.one4zero.sshterm.terminal.TermKey
import com.one4zero.sshterm.terminal.TerminalColors
import com.one4zero.sshterm.terminal.TerminalView

class TerminalActivity : Activity(), SshSession.Listener, TerminalView.Client {
    private val app get() = App.instance
    private val settings get() = app.settings
    private val handler = Handler(Looper.getMainLooper())

    private var session: SshSession? = null

    private lateinit var toolbar: Toolbar
    private lateinit var terminalView: TerminalView
    private lateinit var commandInput: EditText
    private lateinit var directHint: TextView
    private lateinit var btnMode: ImageButton
    private lateinit var selectionBar: View
    private lateinit var scrollBottom: View
    private lateinit var disconnectedPanel: View
    private lateinit var disconnectedText: TextView
    private lateinit var snippetsScroll: HorizontalScrollView
    private lateinit var snippetsContainer: LinearLayout
    private lateinit var extraKeys: LinearLayout

    private var directMode = false
    private var ctrlSticky = false
    private var altSticky = false
    private val modifierViews = mutableMapOf<ExtraKey, TextView>()

    private var promptDialog: AlertDialog? = null
    private var shownPrompt: Prompt? = null
    private var resumed = false

    private val snippetsListener: () -> Unit = { buildSnippets() }
    private val imm get() = getSystemService(InputMethodManager::class.java)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)
        toolbar = findViewById(R.id.toolbar)
        setActionBar(toolbar)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        setupEdgeToEdge(findViewById(R.id.root), toolbar)

        terminalView = findViewById(R.id.terminal)
        commandInput = findViewById(R.id.command_input)
        directHint = findViewById(R.id.direct_hint)
        btnMode = findViewById(R.id.btn_mode)
        selectionBar = findViewById(R.id.selection_bar)
        scrollBottom = findViewById(R.id.scroll_bottom)
        disconnectedPanel = findViewById(R.id.disconnected_panel)
        disconnectedText = findViewById(R.id.disconnected_text)
        snippetsScroll = findViewById(R.id.snippets_scroll)
        snippetsContainer = findViewById(R.id.snippets)
        extraKeys = findViewById(R.id.extra_keys)

        terminalView.client = this
        terminalView.setFontSize(settings.fontSizeSp)
        applyColors()

        setupCommandInput()
        setupButtons()
        buildExtraKeys()
        buildSnippets()
        app.snippets.addListener(snippetsListener)

        if (settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val id = savedInstanceState?.getInt(EXTRA_SESSION_ID, -1)?.takeIf { it >= 0 }
            ?: intent.getIntExtra(EXTRA_SESSION_ID, -1)
        val s = SessionManager.get(id)
        if (s == null) {
            toast("Сессия не найдена")
            finish()
            return
        }
        attach(s)
        setDirectMode(settings.directInputDefault, showKeyboard = false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val s = SessionManager.get(intent.getIntExtra(EXTRA_SESSION_ID, -1)) ?: return
        setIntent(intent)
        attach(s)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        session?.let { outState.putInt(EXTRA_SESSION_ID, it.id) }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (session != null && SessionManager.get(session!!.id) == null) {
            finish()
            return
        }
        applySettings()
        updateState()
        showPendingPrompt()
        terminalView.invalidate()
    }

    /** Re-applies settings which may have been changed in [SettingsActivity]. */
    private fun applySettings() {
        applyColors()
        if (terminalView.fontSizeSp != settings.fontSizeSp) terminalView.setFontSize(settings.fontSizeSp)
        extraKeys.visibility = if (settings.showExtraKeys) View.VISIBLE else View.GONE
        if ((snippetsScroll.visibility == View.VISIBLE) != settings.showSnippets) buildSnippets()
        setupCommandInput()
        if (settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        resumed = false
        showBackgroundHintIfLeaving()
        super.onPause()
    }

    override fun onDestroy() {
        session?.removeListener(this)
        app.snippets.removeListener(snippetsListener)
        promptDialog?.dismiss()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Back closes the text selection first (predictive back on Android 13+). */
    private val selectionBackCallback: Any? =
        if (Build.VERSION.SDK_INT >= 33) OnBackInvokedCallback { terminalView.clearSelection() } else null
    private var selectionBackRegistered = false

    private fun updateBackCallback(selectionActive: Boolean) {
        if (Build.VERSION.SDK_INT < 33 || selectionActive == selectionBackRegistered) return
        val cb = selectionBackCallback as OnBackInvokedCallback
        if (selectionActive) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
        } else {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(cb)
        }
        selectionBackRegistered = selectionActive
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Only called on Android 12 and older; newer versions use OnBackInvokedCallback.
        if (terminalView.hasSelection) {
            terminalView.clearSelection()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun showBackgroundHintIfLeaving() {
        val s = session ?: return
        if (isFinishing && s.state != SshSession.State.DISCONNECTED && SessionManager.get(s.id) != null &&
            !settings.backgroundHintShown
        ) {
            settings.backgroundHintShown = true
            toast(getString(R.string.session_continues))
        }
    }

    private fun attach(s: SshSession) {
        session?.removeListener(this)
        promptDialog?.dismiss()
        promptDialog = null
        shownPrompt = null
        session = s
        intent.putExtra(EXTRA_SESSION_ID, s.id)
        s.addListener(this)
        terminalView.emulator = s.emulator
        updateState()
        showPendingPrompt()
    }

    // ---------------------------------------------------------------- UI setup

    private fun applyColors() {
        val colors = TerminalColors.get(settings.colorScheme)
        terminalView.colors = colors
        findViewById<View>(R.id.terminal_frame).setBackgroundColor(colors.background)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupCommandInput() {
        var type = InputType.TYPE_CLASS_TEXT
        if (settings.noSuggestions) {
            type = type or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        commandInput.setRawInputType(type)
        commandInput.imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN
        commandInput.setHorizontallyScrolling(false)
        commandInput.maxLines = 6
        commandInput.setOnEditorActionListener { _, actionId, event ->
            val isEnter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER
            if (actionId == EditorInfo.IME_ACTION_SEND || isEnter) {
                if (isEnter && event!!.action != KeyEvent.ACTION_DOWN) return@setOnEditorActionListener true
                if (isEnter && event!!.isShiftPressed) {
                    insertIntoInput("\n")
                } else {
                    sendCommand()
                }
                true
            } else false
        }
        // Sticky CTRL / ALT from the extra keys apply to the next typed character.
        commandInput.filters = arrayOf(
            InputFilter { source, start, end, _, _, _ ->
                if ((ctrlSticky || altSticky) && end - start == 1) {
                    val mods = consumeModifiers()
                    val text = KeyEncoder.encodeText(
                        source.subSequence(start, end).toString(),
                        mods and KeyEncoder.MOD_CTRL != 0,
                        mods and KeyEncoder.MOD_ALT != 0,
                    )
                    session?.writeText(text)
                    terminalView.scrollToBottom()
                    ""
                } else null
            },
        )
    }

    private fun setupButtons() {
        btnMode.setOnClickListener { setDirectMode(!directMode, showKeyboard = true) }
        directHint.setOnClickListener {
            terminalView.requestFocus()
            terminalView.showKeyboard()
        }
        findViewById<View>(R.id.btn_history).setOnClickListener { showHistory() }
        val paste = findViewById<View>(R.id.btn_paste)
        paste.setOnClickListener { pasteClipboard(directMode) }
        paste.setOnLongClickListener {
            pasteClipboard(true)
            true
        }
        val send = findViewById<View>(R.id.btn_send)
        send.setOnClickListener {
            if (directMode) {
                session?.writeText("\r")
                terminalView.scrollToBottom()
            } else {
                sendCommand()
            }
        }
        send.setOnLongClickListener {
            // insert without pressing Enter
            val text = commandInput.text.toString()
            if (text.isNotEmpty()) {
                session?.paste(text)
                commandInput.setText("")
                terminalView.scrollToBottom()
                toast("Вставлено без Enter")
            }
            true
        }
        scrollBottom.setOnClickListener { terminalView.scrollToBottom() }

        findViewById<View>(R.id.sel_copy).setOnClickListener {
            val text = terminalView.getSelectedText()
            if (text.isNotEmpty()) copyToClipboard(text) else toast(getString(R.string.nothing_to_copy))
            terminalView.clearSelection()
        }
        findViewById<View>(R.id.sel_ai).setOnClickListener {
            val text = terminalView.getSelectedText()
            if (text.isNotBlank()) copyForAi(AiText.codeBlock(null, text)) else toast(getString(R.string.nothing_to_copy))
            terminalView.clearSelection()
        }
        findViewById<View>(R.id.sel_all).setOnClickListener { terminalView.selectAll() }
        findViewById<View>(R.id.sel_paste).setOnClickListener {
            terminalView.clearSelection()
            pasteClipboard(directMode)
        }
        findViewById<View>(R.id.sel_close).setOnClickListener { terminalView.clearSelection() }

        findViewById<View>(R.id.btn_reconnect).setOnClickListener { session?.reconnect() }
        findViewById<View>(R.id.btn_close_session).setOnClickListener {
            session?.let { SessionManager.close(this, it) }
            finish()
        }
    }

    private fun setDirectMode(direct: Boolean, showKeyboard: Boolean) {
        directMode = direct
        terminalView.directInput = direct
        commandInput.visibility = if (direct) View.GONE else View.VISIBLE
        directHint.visibility = if (direct) View.VISIBLE else View.GONE
        btnMode.setImageResource(if (direct) R.drawable.ic_edit else R.drawable.ic_keyboard)
        if (direct) {
            terminalView.requestFocus()
            imm.restartInput(terminalView)
            if (showKeyboard) handler.postDelayed({ terminalView.showKeyboard() }, 50)
        } else {
            commandInput.requestFocus()
            if (showKeyboard) handler.postDelayed({ imm.showSoftInput(commandInput, 0) }, 50)
        }
    }

    // ---------------------------------------------------------------- extra keys

    private sealed class ExtraKey(val label: String) {
        class Special(label: String, val key: TermKey, val repeat: Boolean = false) : ExtraKey(label)
        class Text(label: String, val text: String) : ExtraKey(label)
        class Control(label: String, val code: Int) : ExtraKey(label)
        object Ctrl : ExtraKey("CTRL")
        object Alt : ExtraKey("ALT")
    }

    private val keyRows: List<List<ExtraKey>> = listOf(
        listOf(
            ExtraKey.Special("ESC", TermKey.ESCAPE),
            ExtraKey.Control("^C", 3),
            ExtraKey.Text("/", "/"),
            ExtraKey.Text("|", "|"),
            ExtraKey.Text("-", "-"),
            ExtraKey.Text("~", "~"),
            ExtraKey.Special("Home", TermKey.HOME),
            ExtraKey.Special("↑", TermKey.UP, repeat = true),
            ExtraKey.Special("End", TermKey.END),
            ExtraKey.Special("PgUp", TermKey.PAGE_UP, repeat = true),
        ),
        listOf(
            ExtraKey.Special("TAB", TermKey.TAB),
            ExtraKey.Ctrl,
            ExtraKey.Alt,
            ExtraKey.Control("^D", 4),
            ExtraKey.Control("^Z", 26),
            ExtraKey.Control("^L", 12),
            ExtraKey.Special("←", TermKey.LEFT, repeat = true),
            ExtraKey.Special("↓", TermKey.DOWN, repeat = true),
            ExtraKey.Special("→", TermKey.RIGHT, repeat = true),
            ExtraKey.Special("PgDn", TermKey.PAGE_DOWN, repeat = true),
        ),
    )

    @SuppressLint("ClickableViewAccessibility")
    private fun buildExtraKeys() {
        extraKeys.removeAllViews()
        modifierViews.clear()
        extraKeys.visibility = if (settings.showExtraKeys) View.VISIBLE else View.GONE
        for (row in keyRows) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (key in row) {
                val tv = TextView(this, null, 0, R.style.ExtraKey).apply {
                    text = key.label
                    layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                        val m = dp(2)
                        setMargins(m, m, m, m)
                    }
                    if (key.label.length > 3) textSize = 11f
                }
                if (key is ExtraKey.Ctrl || key is ExtraKey.Alt) modifierViews[key] = tv
                if (key is ExtraKey.Special && key.repeat) {
                    tv.setOnTouchListener(RepeatListener { onExtraKey(key, tv) })
                } else {
                    tv.setOnClickListener { onExtraKey(key, tv) }
                }
                line.addView(tv)
            }
            extraKeys.addView(line)
        }
        updateModifierViews()
    }

    private fun onExtraKey(key: ExtraKey, view: View) {
        if (settings.vibrateOnKeys) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        val s = session ?: return
        when (key) {
            ExtraKey.Ctrl -> {
                ctrlSticky = !ctrlSticky
                updateModifierViews()
                return
            }
            ExtraKey.Alt -> {
                altSticky = !altSticky
                updateModifierViews()
                return
            }
            is ExtraKey.Special -> {
                val mods = consumeModifiers()
                val appCursor = synchronized(s.emulator) { s.emulator.applicationCursorKeys }
                s.writeText(KeyEncoder.encodeKey(key.key, mods, appCursor))
            }
            is ExtraKey.Text -> {
                val mods = consumeModifiers()
                s.writeText(
                    KeyEncoder.encodeText(key.text, mods and KeyEncoder.MOD_CTRL != 0, mods and KeyEncoder.MOD_ALT != 0),
                )
            }
            is ExtraKey.Control -> {
                val mods = consumeModifiers()
                val prefix = if (mods and KeyEncoder.MOD_ALT != 0) "\u001b" else ""
                s.writeText(prefix + key.code.toChar())
            }
        }
        terminalView.scrollToBottom()
        terminalView.clearSelection()
    }

    private fun updateModifierViews() {
        modifierViews.forEach { (key, view) ->
            val active = if (key is ExtraKey.Ctrl) ctrlSticky else altSticky
            view.setBackgroundResource(if (active) R.drawable.bg_extra_key_active else R.drawable.bg_extra_key)
        }
    }

    /** Repeats the action while the key is held. */
    private inner class RepeatListener(private val action: () -> Unit) : View.OnTouchListener {
        private var view: View? = null
        private val repeater = object : Runnable {
            override fun run() {
                action()
                handler.postDelayed(this, 60)
            }
        }

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view = v
                    v.isPressed = true
                    action()
                    handler.postDelayed(repeater, 400)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    handler.removeCallbacks(repeater)
                }
            }
            return true
        }
    }

    // ---------------------------------------------------------------- snippets

    private fun buildSnippets() {
        snippetsContainer.removeAllViews()
        snippetsScroll.visibility = if (settings.showSnippets) View.VISIBLE else View.GONE
        if (!settings.showSnippets) return
        for (snippet in app.snippets.all()) {
            snippetsContainer.addView(makeChip(snippet.label, snippet.autoRun).apply {
                setOnClickListener { runSnippet(snippet) }
                setOnLongClickListener {
                    showSnippetDialog(snippet)
                    true
                }
            })
        }
        snippetsContainer.addView(makeChip("+", false).apply {
            setOnClickListener { showSnippetDialog(null, commandInput.text.toString()) }
        })
    }

    private fun makeChip(label: String, autoRun: Boolean): TextView = TextView(this).apply {
        text = label
        setTextColor(getColor(R.color.term_key_text))
        textSize = 13f
        typeface = Typeface.MONOSPACE
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        maxWidth = dp(220)
        gravity = Gravity.CENTER
        setBackgroundResource(if (autoRun) R.drawable.bg_chip_autorun else R.drawable.bg_chip)
        setPadding(dp(14), dp(6), dp(14), dp(6))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(32),
        ).apply { setMargins(dp(3), 0, dp(3), 0) }
    }

    private fun runSnippet(snippet: Snippet) {
        val s = session ?: return
        if (settings.vibrateOnKeys) terminalView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (snippet.autoRun) {
            app.history.add(s.host.historyKey, snippet.command)
            s.sendCommand(snippet.command)
            terminalView.scrollToBottom()
        } else if (directMode) {
            s.paste(snippet.command)
            terminalView.scrollToBottom()
        } else {
            insertIntoInput(snippet.command)
            commandInput.requestFocus()
            imm.showSoftInput(commandInput, 0)
        }
    }

    // ---------------------------------------------------------------- commands

    private fun insertIntoInput(text: String) {
        val start = commandInput.selectionStart.coerceAtLeast(0)
        val end = commandInput.selectionEnd.coerceAtLeast(0)
        commandInput.text.replace(minOf(start, end), maxOf(start, end), text)
    }

    private fun sendCommand() {
        val s = session ?: return
        if (s.state != SshSession.State.CONNECTED) {
            toast("Нет соединения")
            return
        }
        val text = commandInput.text.toString()
        if (text.isEmpty()) {
            s.writeText("\r")
        } else {
            app.history.add(s.host.historyKey, text)
            s.sendCommand(text)
            commandInput.setText("")
        }
        terminalView.scrollToBottom()
        terminalView.clearSelection()
    }

    private fun pasteClipboard(toTerminal: Boolean) {
        val text = clipboardText()
        if (text.isNullOrEmpty()) {
            toast(getString(R.string.clipboard_empty))
            return
        }
        if (toTerminal) {
            session?.paste(text)
            terminalView.scrollToBottom()
        } else {
            var toInsert = text
            if (settings.smartPaste) {
                toInsert = AiText.cleanCommand(text)
                if (toInsert != text.trimEnd()) toast(getString(R.string.smart_paste_cleaned))
            }
            insertIntoInput(toInsert)
            commandInput.requestFocus()
        }
    }

    private fun showHistory() {
        val s = session ?: return
        val items = app.history.get(s.host.historyKey).reversed()
        if (items.isEmpty()) {
            toast(getString(R.string.history_empty))
            return
        }
        val list = ListView(this)
        list.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.typeface = Typeface.MONOSPACE
                v.textSize = 14f
                v.maxLines = 3
                v.ellipsize = TextUtils.TruncateAt.END
                return v
            }
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.history)
            .setView(list)
            .setNegativeButton(R.string.close, null)
            .setNeutralButton("Очистить") { _, _ ->
                confirm(null, "Очистить историю команд для этого сервера?", "Очистить") {
                    app.history.clear(s.host.historyKey)
                }
            }
            .create()
        list.setOnItemClickListener { _, _, position, _ ->
            dialog.dismiss()
            if (directMode) setDirectMode(false, showKeyboard = false)
            commandInput.setText(items[position])
            commandInput.setSelection(commandInput.text.length)
            commandInput.requestFocus()
            imm.showSoftInput(commandInput, 0)
        }
        list.setOnItemLongClickListener { _, view, position, _ ->
            val cmd = items[position]
            val popup = PopupMenu(this, view)
            popup.menu.add(0, 1, 0, "Выполнить")
            popup.menu.add(0, 2, 1, R.string.copy)
            popup.menu.add(0, 3, 2, R.string.menu_save_snippet)
            popup.menu.add(0, 4, 3, R.string.delete)
            popup.setOnMenuItemClickListener { mi ->
                when (mi.itemId) {
                    1 -> {
                        dialog.dismiss()
                        app.history.add(s.host.historyKey, cmd)
                        s.sendCommand(cmd)
                        terminalView.scrollToBottom()
                    }
                    2 -> copyToClipboard(cmd)
                    3 -> {
                        dialog.dismiss()
                        showSnippetDialog(null, cmd)
                    }
                    4 -> {
                        app.history.remove(s.host.historyKey, cmd)
                        dialog.dismiss()
                        showHistory()
                    }
                }
                true
            }
            popup.show()
            true
        }
        dialog.show()
    }

    // ---------------------------------------------------------------- menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, M_COPY_AI, 0, R.string.menu_copy_for_ai).setIcon(R.drawable.ic_copy)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, M_SELECT, 1, R.string.menu_select_text).setIcon(R.drawable.ic_select)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, M_SHARE_AI, 1, R.string.menu_share_to_ai)
        menu.add(0, M_COPY_LAST, 1, R.string.menu_copy_last)
        menu.add(0, M_COPY_SCREEN, 2, R.string.menu_copy_screen)
        menu.add(0, M_COPY_ALL, 3, R.string.menu_copy_all)
        menu.add(0, M_PASTE, 4, R.string.menu_paste_terminal)
        menu.add(0, M_SESSIONS, 5, R.string.menu_sessions)
        menu.add(0, M_FONT_UP, 6, R.string.menu_font_bigger)
        menu.add(0, M_FONT_DOWN, 7, R.string.menu_font_smaller)
        menu.add(0, M_KEEP_SCREEN, 8, R.string.menu_keep_screen_on).setCheckable(true).isChecked = settings.keepScreenOn
        menu.add(0, M_SAVE_SNIPPET, 9, R.string.menu_save_snippet)
        menu.add(0, M_CLEAR, 10, R.string.menu_clear_scrollback)
        menu.add(0, M_DISCONNECT, 11, R.string.menu_disconnect)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val s = session
        when (item.itemId) {
            android.R.id.home -> finish()
            M_COPY_LAST -> {
                val out = s?.lastCommandOutput()
                when {
                    out == null -> toast("Сначала выполните команду из строки ввода")
                    out.isBlank() -> toast("У последней команды нет вывода")
                    else -> copyToClipboard(out)
                }
            }
            M_COPY_AI -> {
                val payload = aiPayload()
                if (payload != null) copyForAi(payload) else toast(getString(R.string.nothing_to_copy))
            }
            M_SHARE_AI -> {
                val payload = aiPayload()
                if (payload != null) shareToAi(payload) else toast(getString(R.string.nothing_to_copy))
            }
            M_SELECT -> showSelectTextDialog()
            M_COPY_SCREEN -> s?.let {
                val text = synchronized(it.emulator) {
                    val e = it.emulator
                    e.getText(e.absoluteRow(0), 0, e.absoluteRow(e.rows - 1), Int.MAX_VALUE).trimEnd()
                }
                copyToClipboard(text)
            }
            M_COPY_ALL -> s?.let {
                val text = synchronized(it.emulator) { it.emulator.getTranscript() }
                copyToClipboard(text)
            }
            M_PASTE -> pasteClipboard(true)
            M_SESSIONS -> showSessions()
            M_FONT_UP -> changeFont(1f)
            M_FONT_DOWN -> changeFont(-1f)
            M_KEEP_SCREEN -> {
                val on = !item.isChecked
                item.isChecked = on
                settings.keepScreenOn = on
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            M_SAVE_SNIPPET -> showSnippetDialog(null, commandInput.text.toString())
            M_CLEAR -> s?.let {
                synchronized(it.emulator) { it.emulator.clearScrollback() }
                terminalView.scrollToBottom()
                terminalView.invalidate()
            }
            M_DISCONNECT -> s?.let {
                if (it.state == SshSession.State.DISCONNECTED) {
                    SessionManager.close(this, it)
                    finish()
                } else {
                    confirm(null, getString(R.string.disconnect_confirm), getString(R.string.menu_disconnect)) {
                        SessionManager.close(this, it)
                        finish()
                    }
                }
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    /** Last command with its output (or the visible screen) as a Markdown code block for an AI chat. */
    private fun aiPayload(): String? {
        val s = session ?: return null
        val out = s.lastCommandOutput()
        if (out != null) return AiText.codeBlock(s.lastCommand, out)
        val screen = synchronized(s.emulator) {
            val e = s.emulator
            e.getText(e.absoluteRow(0), 0, e.absoluteRow(e.rows - 1), Int.MAX_VALUE)
        }.trim('\n').trimEnd()
        return if (screen.isBlank()) null else AiText.codeBlock(null, screen)
    }

    private fun copyForAi(text: String) {
        copyToClipboard(text)
        if (android.os.Build.VERSION.SDK_INT >= 33) toast(getString(R.string.copied_for_ai))
    }

    private fun shareToAi(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        try {
            startActivity(Intent.createChooser(send, getString(R.string.share_to_ai_title)))
        } catch (e: Exception) {
            copyForAi(text)
        }
    }

    private fun changeFont(delta: Float) {
        terminalView.setFontSize(terminalView.fontSizeSp + delta)
        settings.fontSizeSp = terminalView.fontSizeSp
    }

    private fun showSessions() {
        val sessions = SessionManager.all()
        val labels = sessions.map { (if (it === session) "● " else "") + (it.terminalTitle ?: it.title) + " — " + it.statusMessage } +
            getString(R.string.menu_new_session)
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_sessions)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < sessions.size) {
                    attach(sessions[which])
                } else {
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    )
                }
            }
            .show()
    }

    private fun showSelectTextDialog() {
        val s = session ?: return
        var text = synchronized(s.emulator) { s.emulator.getTranscript() }
        if (text.length > MAX_SELECT_TEXT) text = text.substring(text.length - MAX_SELECT_TEXT)
        val tv = TextView(this).apply {
            setText(text)
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(0xFFE6E9EC.toInt())
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val hs = HorizontalScrollView(this).apply { addView(tv) }
        val scroll = ScrollView(this).apply { addView(hs) }
        AlertDialog.Builder(this)
            .setTitle(R.string.select_text_title)
            .setView(scroll)
            .setPositiveButton(R.string.menu_copy_all) { _, _ -> copyToClipboard(text) }
            .setNegativeButton(R.string.close, null)
            .show()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---------------------------------------------------------------- session state & prompts

    private fun updateState() {
        val s = session ?: return
        title = s.terminalTitle ?: s.title
        actionBar?.subtitle = when (s.state) {
            SshSession.State.CONNECTED -> s.host.address
            else -> s.statusMessage
        }
        val disconnected = s.state == SshSession.State.DISCONNECTED
        disconnectedPanel.visibility = if (disconnected) View.VISIBLE else View.GONE
        disconnectedText.text = s.statusMessage
    }

    private fun showPendingPrompt() {
        val s = session ?: return
        val prompt = s.pendingPrompt
        if (prompt == null || prompt.isDone) {
            if (shownPrompt != null) {
                promptDialog?.dismiss()
                promptDialog = null
                shownPrompt = null
            }
            return
        }
        if (prompt === shownPrompt || !resumed) return
        promptDialog?.dismiss()
        shownPrompt = prompt

        val box = dialogContainer()
        val edits = mutableListOf<EditText>()
        for (field in prompt.fields) {
            if (prompt.fields.size > 1 || field.label.isNotBlank()) box.addView(formLabel(field.label))
            val edit = formEdit(secret = field.secret, monospace = !field.secret)
            edit.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            box.addView(edit)
            edits.add(edit)
        }
        val save = if (prompt.offerSave) CheckBox(this).apply {
            text = getString(R.string.remember_password)
            setPadding(paddingLeft, dp(8), paddingRight, dp(8))
            box.addView(this)
        } else null

        val builder = AlertDialog.Builder(this)
            .setTitle(prompt.title)
            .setMessage(prompt.message)
            .setCancelable(false)
            .setPositiveButton(prompt.positive) { _, _ ->
                prompt.complete(true, edits.map { it.text.toString() }, save?.isChecked == true)
            }
            .setNegativeButton(prompt.negative) { _, _ -> prompt.complete(false) }
        if (prompt.warning) builder.setIcon(android.R.drawable.ic_dialog_alert)
        if (edits.isNotEmpty()) builder.setView(box)
        val dialog = builder.create()
        if (edits.isNotEmpty()) {
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            edits.last().setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    true
                } else false
            }
        }
        dialog.setOnDismissListener {
            if (promptDialog === dialog) {
                promptDialog = null
            }
        }
        promptDialog = dialog
        dialog.show()
        edits.firstOrNull()?.requestFocus()
    }

    // SshSession.Listener

    override fun onScreenChanged() {
        terminalView.invalidate()
    }

    override fun onStateChanged() {
        updateState()
        if (session?.state == SshSession.State.CONNECTED && !directMode && resumed) {
            commandInput.requestFocus()
        }
    }

    override fun onPromptChanged() {
        showPendingPrompt()
    }

    override fun onTitleChanged() {
        updateState()
    }

    // TerminalView.Client

    override fun onTerminalInput(data: ByteArray) {
        session?.write(data)
    }

    override fun onTerminalResize(cols: Int, rows: Int, widthPx: Int, heightPx: Int) {
        session?.resize(cols, rows, widthPx, heightPx)
    }

    override fun onTerminalTap() {
        // In command mode a tap keeps the keyboard and focus on the input line.
    }

    override fun onSelectionChanged(active: Boolean) {
        selectionBar.visibility = if (active) View.VISIBLE else View.GONE
        updateBackCallback(active)
    }

    override fun onScrollPositionChanged(scrolledBack: Boolean) {
        scrollBottom.visibility = if (scrolledBack) View.VISIBLE else View.GONE
    }

    override fun onFontSizeChanged(sizeSp: Float) {
        settings.fontSizeSp = sizeSp
    }

    override fun consumeModifiers(): Int {
        var m = 0
        if (ctrlSticky) m = m or KeyEncoder.MOD_CTRL
        if (altSticky) m = m or KeyEncoder.MOD_ALT
        if (m != 0) {
            ctrlSticky = false
            altSticky = false
            updateModifierViews()
        }
        return m
    }

    override fun onPasteShortcut() {
        pasteClipboard(true)
    }

    override fun onWordDoubleTapped(word: String) {
        if (directMode) {
            session?.paste(word)
            return
        }
        val pos = commandInput.selectionStart.coerceAtLeast(0)
        val before = commandInput.text.substring(0, pos)
        val prefix = if (before.isNotEmpty() && !before.endsWith(" ")) " " else ""
        insertIntoInput(prefix + word)
        commandInput.requestFocus()
    }

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
        private const val MAX_SELECT_TEXT = 400_000

        private const val M_COPY_LAST = 1
        private const val M_SELECT = 2
        private const val M_COPY_SCREEN = 3
        private const val M_COPY_ALL = 4
        private const val M_PASTE = 5
        private const val M_SESSIONS = 6
        private const val M_FONT_UP = 7
        private const val M_FONT_DOWN = 8
        private const val M_KEEP_SCREEN = 9
        private const val M_SAVE_SNIPPET = 10
        private const val M_CLEAR = 11
        private const val M_DISCONNECT = 12
        private const val M_COPY_AI = 13
        private const val M_SHARE_AI = 14
    }
}
